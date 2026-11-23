package gr.pnet.em.it;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;

/** Stands in for the Open5GS AMF (ue-info) and SMF (pdu-info) metrics endpoints. */
final class MetricsStub {

    private static final String NO_ITEMS = "{\"items\":[],\"pager\":{\"page\":0,\"page_size\":100,\"count\":0}}";

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();

    private MetricsStub(HttpServer server) {
        this.server = server;
    }

    static MetricsStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MetricsStub stub = new MetricsStub(server);
            server.createContext("/", exchange -> {
                Response response = stub.responses.getOrDefault(exchange.getRequestURI().getPath(),
                        new Response(404, ""));
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            stub.reset();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Both endpoints answer with no UEs. */
    void reset() {
        responses.put("/ue-info", new Response(200, NO_ITEMS));
        responses.put("/pdu-info", new Response(200, NO_ITEMS));
    }

    /** The endpoint answers with these items (JSON objects). */
    void items(String path, String... items) {
        responses.put(path, new Response(200, "{\"items\":[" + String.join(",", items) + "]}"));
    }

    void fail(String path, int status) {
        responses.put(path, new Response(status, ""));
    }
}
