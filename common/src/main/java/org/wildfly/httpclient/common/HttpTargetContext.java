package org.wildfly.httpclient.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;
import org.xnio.channels.Channels;
import org.xnio.streams.ChannelInputStream;
import org.xnio.streams.ChannelOutputStream;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.Cookies;
import io.undertow.util.FlexBase64;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class HttpTargetContext extends AbstractAttachable {

    private static final String EXCEPTION_TYPE = "application/x-wf-jbmar-exception";

    private static final String JSESSIONID = "JSESSIONID";
    static final MarshallerFactory MARSHALLER_FACTORY = new RiverMarshallerFactory();

    private final HttpConnectionPool connectionPool;
    private final boolean eagerlyAcquireAffinity;
    private volatile CountDownLatch sessionAffinityLatch = new CountDownLatch(1);
    private volatile String sessionId;

    private final AtomicBoolean affinityRequestSent = new AtomicBoolean();

    private static final AuthenticationContextConfigurationClient AUTH_CONTEXT_CLIENT;

    static {
        AUTH_CONTEXT_CLIENT = AccessController.doPrivileged(new PrivilegedAction<AuthenticationContextConfigurationClient>() {
            @Override
            public AuthenticationContextConfigurationClient run() {
                return new AuthenticationContextConfigurationClient();
            }
        });
    }


    HttpTargetContext(HttpConnectionPool connectionPool, boolean eagerlyAcquireAffinity) {
        this.connectionPool = connectionPool;
        this.eagerlyAcquireAffinity = eagerlyAcquireAffinity;
    }

    void init() {
        if (eagerlyAcquireAffinity) {
            acquireAffinitiy();
        }
    }

    private void acquireAffinitiy() {
        if (affinityRequestSent.compareAndSet(false, true)) {
            connectionPool.getConnection(connection -> {
                        acquireSessionAffinity(connection, sessionAffinityLatch);
                    },
                    (t) -> {
                        sessionAffinityLatch.countDown();
                        HttpClientMessages.MESSAGES.failedToAcquireSession(t);
                    }, false);
        }
    }


    private void acquireSessionAffinity(HttpConnectionPool.ConnectionHandle connection, CountDownLatch latch) {
        ClientRequest clientRequest = new ClientRequest();
        clientRequest.setMethod(Methods.GET);
        clientRequest.setPath(connection.getUri().getPath() + "/common/v1/affinity");

        sendRequest(connection, clientRequest, null, null, (e) -> {
            latch.countDown();
            HttpClientMessages.MESSAGES.failedToAcquireSession(e);
        }, null, latch::countDown);
    }

    public Unmarshaller createUnmarshaller(MarshallingConfiguration marshallingConfiguration) throws IOException {
        return MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
    }

    public Marshaller createMarshaller(MarshallingConfiguration marshallingConfiguration) throws IOException {
        return MARSHALLER_FACTORY.createMarshaller(marshallingConfiguration);
    }

    public void sendRequest(final HttpConnectionPool.ConnectionHandle connection, ClientRequest request, HttpMarshaller httpMarshaller, HttpResultHandler httpResultHandler, HttpFailureHandler failureHandler, ContentType expectedResponse, Runnable completedTask) {
        if(sessionId != null) {
            request.getRequestHeaders().add(Headers.COOKIE, "JSESSIONID=" + sessionId);
        }

        //TODO: how do we configure this?
        AuthenticationContext context = AuthenticationContext.captureCurrent();
        AuthenticationConfiguration config = AUTH_CONTEXT_CLIENT.getAuthenticationConfiguration(connection.getUri(), context);
        Principal principal = AUTH_CONTEXT_CLIENT.getPrincipal(config);
        boolean ok = true;
        PasswordCallback callback = new PasswordCallback("password", false);
        try {
            AUTH_CONTEXT_CLIENT.getCallbackHandler(config).handle(new Callback[]{callback});
        } catch (IOException | UnsupportedCallbackException e) {
            ok = false;
        }
        if(ok) {
            char[] password = callback.getPassword();
            String challenge = principal.getName() + ":" + new String(password);
            request.getRequestHeaders().put(Headers.AUTHORIZATION, "basic " + FlexBase64.encodeString(challenge.getBytes(StandardCharsets.UTF_8), false));
        }
        request.getRequestHeaders().put(Headers.HOST, connection.getUri().getHost());
        connection.getConnection().sendRequest(request, new ClientCallback<ClientExchange>() {
            @Override
            public void completed(ClientExchange result) {
                result.setResponseListener(new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(ClientExchange result) {
                        connection.getConnection().getWorker().execute(() -> {
                            ClientResponse response = result.getResponse();
                            ContentType type = ContentType.parse(response.getResponseHeaders().getFirst(Headers.CONTENT_TYPE));
                            final boolean ok;
                            final boolean isException;
                            if (type == null) {
                                ok = expectedResponse == null;
                                isException = false;
                            } else {
                                if (type.getType().equals(EXCEPTION_TYPE)) {
                                    ok = true;
                                    isException = true;
                                } else if (expectedResponse == null) {
                                    ok = false;
                                    isException = false;
                                } else {
                                    ok = expectedResponse.getType().equals(type.getType()) && expectedResponse.getVersion() >= type.getVersion();
                                    isException = false;
                                }
                            }

                            if (!ok) {
                                if (response.getResponseCode() >= 400) {
                                    failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseCode(response.getResponseCode(), response));
                                } else {
                                    failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseType(type));
                                }
                                //close the connection to be safe
                                connection.done(true);
                                return;
                            }
                            try {
                                //handle session affinity
                                HeaderValues cookies = response.getResponseHeaders().get(Headers.SET_COOKIE);
                                if (cookies != null) {
                                    for (String cookie : cookies) {
                                        Cookie c = Cookies.parseSetCookieHeader(cookie);
                                        if (c.getName().equals(JSESSIONID)) {
                                            setSessionId(c.getValue());
                                        }
                                    }
                                }

                                if (isException) {
                                    final MarshallingConfiguration marshallingConfiguration = createExceptionMarshallingConfig();
                                    final Unmarshaller unmarshaller = MARSHALLER_FACTORY.createUnmarshaller(marshallingConfiguration);
                                    unmarshaller.start(new InputStreamByteInput(new BufferedInputStream(new ChannelInputStream(result.getResponseChannel()))));
                                    Exception exception = (Exception) unmarshaller.readObject();
                                    Map<String, Object> attachments = readAttachments(unmarshaller);
                                    if (unmarshaller.read() != -1) {
                                        HttpClientMessages.MESSAGES.debugf("Unexpected data when reading exception from %s", response);
                                        connection.done(true);
                                    } else {
                                        connection.done(false);
                                    }
                                    failureHandler.handleFailure(exception);
                                    return;
                                } else if (response.getResponseCode() >= 400) {
                                    //unknown error
                                    failureHandler.handleFailure(HttpClientMessages.MESSAGES.invalidResponseCode(response.getResponseCode(), response));
                                    //close the connection to be safe
                                    connection.done(true);

                                } else {
                                    if (httpResultHandler != null) {
                                        if (response.getResponseCode() == StatusCodes.NO_CONTENT) {
                                            Channels.drain(result.getResponseChannel(), Long.MAX_VALUE);
                                            httpResultHandler.handleResult(null, response);
                                        } else {
                                            httpResultHandler.handleResult(new BufferedInputStream(new ChannelInputStream(result.getResponseChannel())), response);
                                        }
                                    }
                                    if (completedTask != null) {
                                        completedTask.run();
                                    }
                                }

                            } catch (Exception e) {
                                connection.done(true);
                                failureHandler.handleFailure(e);
                            }
                        });
                    }

                    @Override
                    public void failed(IOException e) {
                        connection.done(true);
                        failureHandler.handleFailure(e);
                    }
                });

                if (httpMarshaller != null) {
                    //marshalling is blocking, we need to delegate, otherwise we may need to buffer arbitrarily large requests
                    connection.getConnection().getWorker().execute(() -> {
                        try (OutputStream outputStream = new BufferedOutputStream(new ChannelOutputStream(result.getRequestChannel()))) {

                            // marshall the locator and method params
                            final ByteOutput byteOutput = Marshalling.createByteOutput(outputStream);
                            // start the marshaller
                            httpMarshaller.marshall(byteOutput);

                        } catch (IOException e) {
                            connection.done(true);
                            failureHandler.handleFailure(e);
                        }
                    });
                }
            }

            @Override
            public void failed(IOException e) {
                connection.done(true);
                failureHandler.handleFailure(e);
            }
        });
    }

    /**
     * Exceptions don't use an object/class table, as they are common across protocols
     *
     * @return
     */
    MarshallingConfiguration createExceptionMarshallingConfig() {
        final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
        marshallingConfiguration.setVersion(2);
        return marshallingConfiguration;
    }

    private static Map<String, Object> readAttachments(final ObjectInput input) throws IOException, ClassNotFoundException {
        final int numAttachments = input.readByte();
        if (numAttachments == 0) {
            return null;
        }
        final Map<String, Object> attachments = new HashMap<>(numAttachments);
        for (int i = 0; i < numAttachments; i++) {
            // read the key
            final String key = (String) input.readObject();
            // read the attachment value
            final Object val = input.readObject();
            attachments.put(key, val);
        }
        return attachments;
    }

    public HttpConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public String getSessionId() {
        return sessionId;
    }

    void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }


    public void clearSessionId() {
        synchronized (this) {
            CountDownLatch old = sessionAffinityLatch;
            sessionAffinityLatch = new CountDownLatch(1);
            old.countDown();
            this.affinityRequestSent.set(false);
            this.sessionId = null;
        }
    }
    public String awaitSessionId(boolean required) {
        if(required) {
            acquireAffinitiy();
        }
        if(affinityRequestSent.get()) {
            try {
                sessionAffinityLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return sessionId;
    }

    public interface HttpMarshaller {
        void marshall(ByteOutput output) throws IOException;
    }

    public interface HttpResultHandler {
        void handleResult(InputStream result, ClientResponse response);
    }

    public interface HttpFailureHandler {
        void handleFailure(Throwable throwable);
    }
}