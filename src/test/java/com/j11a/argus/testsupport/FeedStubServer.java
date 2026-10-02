package com.j11a.argus.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback HTTP server that stands in for a feed host; records every request it receives. */
public final class FeedStubServer implements AutoCloseable {

    public record Request(String method, String path, String query, Map<String, String> headers) {

        public String header(String name) {
            return headers.get(name);
        }
    }

    private static final String RSS_TYPE = "application/rss+xml; charset=utf-8";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Responder> responders = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    public FeedStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requestsTo(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    public FeedStubServer serveFixture(String path, String fixtureName) {
        return serve(path, 200, RSS_TYPE, Fixtures.feed(fixtureName), Map.of());
    }

    public FeedStubServer serve(String path, int status, String contentType, byte[] body) {
        return serve(path, status, contentType, body, Map.of());
    }

    public FeedStubServer serve(String path, int status, String contentType, byte[] body,
            Map<String, String> extraHeaders) {
        responders.put(path, exchange -> {
            addHeaders(exchange, contentType, extraHeaders);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        });
        return this;
    }

    public FeedStubServer redirect(String path, int status, String location) {
        responders.put(path, exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(status, -1);
        });
        return this;
    }

    /** No Content-Length: the body goes out chunked. */
    public FeedStubServer serveChunked(String path, String contentType, byte[] body) {
        responders.put(path, exchange -> {
            addHeaders(exchange, contentType, Map.of());
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
        });
        return this;
    }

    /** Waits before sending anything, so the client's wait for response headers times out. */
    public FeedStubServer stallBeforeHeaders(String path, long stallMillis) {
        responders.put(path, exchange -> {
            sleep(stallMillis);
            exchange.sendResponseHeaders(200, -1);
        });
        return this;
    }

    /** Sends the headers at once, then the body in chunks with a pause before each one. */
    public FeedStubServer drip(String path, String contentType, byte[] body, int chunkSize, long pauseMillis) {
        responders.put(path, exchange -> {
            addHeaders(exchange, contentType, Map.of());
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.flush();
            for (int offset = 0; offset < body.length; offset += chunkSize) {
                sleep(pauseMillis);
                out.write(body, offset, Math.min(chunkSize, body.length - offset));
                out.flush();
            }
        });
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Collections.unmodifiableMap(headers)));
        Responder responder = responders.get(exchange.getRequestURI().getPath());
        try (exchange) {
            if (responder == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            responder.respond(exchange);
        } catch (IOException clientWentAway) {
            // The fetcher aborting a connection mid-body (timeout, size cap) is expected.
        }
    }

    private static void addHeaders(HttpExchange exchange, String contentType, Map<String, String> extra) {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        extra.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
