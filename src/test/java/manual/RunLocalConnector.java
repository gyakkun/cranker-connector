package manual;

import com.hsbc.cranker.connector.*;
import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import com.hsbc.cranker.mucranker.FavIconHandler;
import io.muserver.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Http2ConfigBuilder.http2EnabledIfAvailable;
import static io.muserver.MuServerBuilder.muServer;
import static io.muserver.Routes.route;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;

public class RunLocalConnector {

    private static final Logger log = LoggerFactory.getLogger(RunLocalConnector.class);
    private static final int ROUTER_REG_PORT = 12000;
    private static final int ROUTER_VISIT_PORT = 12002;
    private static final int TARGET_VISIT_PORT = 14444;
    private static final String COMPONENT_NAME = "example";
    private static final String _ROUTE = COMPONENT_NAME;
    public static final String HTTP_LOCALHOST_COLON = "http://localhost:";
    public static final String WS_LOCALHOST_COLON = "ws://localhost:";
    public static final String SLASH = "/";

    static {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        startRouter(); // https://github.com/hsbc/mu-cranker-router/blob/master/src/test/java/RunLocal.java
        startTarget(); // GET: /example/hello , WS: /example/echo
        startConnector();

        startCommonGet("cli-tgt", HTTP_LOCALHOST_COLON + TARGET_VISIT_PORT + SLASH + _ROUTE + SLASH + "hello");
        startCommonGet("cli-router", HTTP_LOCALHOST_COLON + ROUTER_VISIT_PORT + SLASH + _ROUTE + SLASH + "hello");

        startWsCli("cli-tgt", WS_LOCALHOST_COLON + TARGET_VISIT_PORT + SLASH + _ROUTE + SLASH + "echo");
        startWsCli("cli-router", WS_LOCALHOST_COLON + ROUTER_VISIT_PORT + SLASH + _ROUTE + SLASH + "echo");
    }

    private static void startCommonGet(String logPrefix, String uri) throws IOException, InterruptedException {
        HttpClient cli = HttpClient.newBuilder().build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(uri))
            .build();
        log.info("[{}] sending common GET req: {}", logPrefix, uri);
        HttpResponse<String> res = cli.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("[{}] response of GET req: {}", logPrefix, res.body());
    }

    private static void startWsCli(String logPrefix, String uri) throws IOException, InterruptedException {
        WebSocket cli = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create(uri), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    log.info("[{}] ws client onText: {}", logPrefix, data);
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }
            })
            .join();
        String txt = "hello world!";
        log.info("[{}] ws sending text req: {}", logPrefix, txt);
        cli.sendText(txt, true);
    }

    private static void startTarget() {
        muServer()
            .withHttpPort(TARGET_VISIT_PORT)
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(context(_ROUTE)
                .addHandler(route(Method.GET, "hello", (req, res, pathParams) -> {
                    log.info("[tgt-svr] common GET req: /hello");
                    res.write("Hi there!");
                }))
                .addHandler(webSocketHandler()
                    .withWebSocketFactory((request, responseHeaders) -> new BaseWebSocket() {
                        @Override
                        public void onText(String message, boolean isLast, DoneCallback onComplete) {
                            log.info("[tgt-svr] ws receive text: {}, isLast: {}", message, isLast);
                            session().sendText("ECHO BACK: " + message, onComplete);
                        }
                    })
                )
            ).start()
        ;
    }

    // Copy from: https://github.com/hsbc/mu-cranker-router/blob/master/src/test/java/RunLocal.java
    public static void startRouter() throws IOException {

        // This is an example of a cranker router implementation.

        // Use the mu-cranker-router builder to create a router object.
        CrankerRouter router = CrankerRouterBuilder.crankerRouter()
            .withIdleTimeout(5, TimeUnit.MINUTES)
            .withRegistrationIpValidator(ip -> true)
            .start();

        // Create a server for connectors to register to. As you create this server, you can control
        // whether it is HTTP or HTTPS, the ports used, and you can add your own handlers to do things
        // like health diagnostics, extra authentication or logging etc.
        // The last handler added is the registration handler that the CrankerRouter object supplies.
        MuServer registrationServer = muServer()
            .withHttp2Config(http2EnabledIfAvailable())
            .withHttpPort(ROUTER_REG_PORT)
            .addHandler(Method.GET, "/health", new HealthHandler(router))
            .addHandler(Method.GET, "/health/connections", (request, response, pathParams) -> {
                response.contentType("text/plain;charset=utf-8");
                for (HttpConnection con : request.server().activeConnections()) {
                    response.sendChunk(con.httpsProtocol() + " " + con.remoteAddress() + "\n");
                    for (MuRequest activeRequest : con.activeRequests()) {
                        response.sendChunk("   " + activeRequest + "\n");
                    }
                    response.sendChunk("\n");
                }
                response.sendChunk("-------");
            })
            .addHandler(Method.GET, "/health/connectors", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write(new JSONObject()
                    .put("services", router.collectInfo().toMap())
                    .toString(2));
            })
            .addHandler(router.createRegistrationHandler())
            .start();

        // Next create the server that HTTP clients will connect to. In this example, HTTP2 is enabled,
        // a favicon is enabled, and then the handler that the CrankerRouter object supplies is added last.
        MuServer httpServer = muServer()
            .withHttpPort(ROUTER_VISIT_PORT)
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(FavIconHandler.fromClassPath("/favicon.ico"))
            .addHandler(router.createHttpHandler())
            .start();

        log.info("Registration URL is ws" + registrationServer.uri().toString().substring(4));
        log.info("Health diagnostics are at " + registrationServer.uri().resolve("/health"));
        log.info("The HTTP endpoint for clients is available at " + httpServer.uri());

        // Now web servers can use a connector to register to the registration URL and they will be
        // exposed on the HTTP endpoint.


        // Shutdown order is HTTP server, then registration server, and finally the router.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop();
            registrationServer.stop();
            router.stop();
        }));

    }

    private static void startConnector() {
        final CrankerConnector connector = CrankerConnectorBuilder.connector()
            // .withPreferredProtocols(List.of(CRANKER_PROTOCOL_3))
            // .withDomain("127.0.0.1")
            .withRouterUris(() -> List.of(URI.create(WS_LOCALHOST_COLON + ROUTER_REG_PORT)))
            .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
            .withComponentName(COMPONENT_NAME)
            .withRoute(_ROUTE)
            .withTarget(URI.create(HTTP_LOCALHOST_COLON + TARGET_VISIT_PORT))
            .withRouterRegistrationListener(new RouterEventListener() {
                public void onRegistrationChanged(ChangeData data) {
                    log.info("Router registration changed: " + data);
                }

                public void onSocketConnectionError(RouterRegistration router1, Throwable exception) {
                    log.warn("Error connecting to " + router1, exception);
                }
            })
            .withProxyEventListener(new ProxyEventListener() {
                @Override
                public void onProxyError(HttpRequest request, Throwable error) {
                    log.warn("onProxyError, request=" + request, error);
                }
            })
            .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            final boolean stop = connector.stop(5, TimeUnit.SECONDS);
            log.info("connector stop, state={}", stop);
        }));

        log.info("connector started");
    }


}

class HealthHandler implements RouteHandler {
    private final CrankerRouter router;

    public HealthHandler(CrankerRouter router) {
        this.router = router;
    }

    @Override
    public void handle(MuRequest req, MuResponse resp, Map<String, String> pathParams) {
        resp.contentType("application/json");
        MuStats stats = req.server().stats();
        JSONObject health = new JSONObject()
            .put("activeRequests", stats.activeConnections())
            .put("activeConnections", stats.activeRequests().size())
            .put("completedRequests", stats.completedRequests())
            .put("bytesSent", stats.bytesSent())
            .put("bytesReceived", stats.bytesRead())
            .put("invalidRequests", stats.invalidHttpRequests())
            .put("crankerVersion", CrankerRouter.muCrankerVersion())
            .put("services", router.collectInfo().toMap());
        resp.write(health.toString(2));
    }
}
