package com.cotp.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Dependency-free HTTP server for the COTP web demo.
 *
 * <p>Uses only the JDK: {@link com.sun.net.httpserver.HttpServer} serves both the small JSON/REST API
 * and the single static frontend ({@code web/index.html}) that is loaded from the classpath (the
 * build script copies it next to the compiled classes).</p>
 *
 * <p>Usage: {@code java -cp <classes> com.cotp.demo.DemoServer [port]}</p>
 */
public final class DemoServer {

    private static final String INDEX_RESOURCE = "/web/index.html";

    private final DemoSession session = new DemoSession();

    private DemoServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "', defaulting to 8080");
            }
        }

        DemoServer demo = new DemoServer();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", demo::handleRoot);
            server.createContext("/api/", demo::handleApi);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
        } catch (IOException e) {
            System.err.println("======================================================");
            System.err.println(" COTP web demo failed to start");
            System.err.println(" Could not bind to port " + port + ": " + e.getMessage());
            System.err.println(" Is another process already using that port?");
            System.err.println(" Try another port, e.g. -Port 9090.");
            System.err.println("======================================================");
            throw e;
        }

        String url = "http://localhost:" + server.getAddress().getPort();
        System.out.println("======================================================");
        System.out.println(" COTP web demo is running");
        System.out.println(" Open:  " + url);
        System.out.println(" API:   " + url + "/api/state");
        System.out.println(" Press Ctrl+C to stop.");
        System.out.println("======================================================");
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopping COTP demo...");
            server.stop(0);
        }));
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ routing

    private void handleRoot(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveIndex(exchange);
            } else if (path.equals("/favicon.ico")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                sendJson(exchange, 404, Map.of("error", "Not found: " + path));
            }
        } catch (RuntimeException e) {
            sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Throwable t) {
            System.err.println("Unhandled error serving " + exchange.getRequestURI() + ": " + t);
            t.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "Internal error: " + t));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/api/state") && method.equals("GET")) {
                sendJson(exchange, 200, session.state());
                return;
            }
            if (!method.equals("POST")) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed: " + method));
                return;
            }

            Map<String, Object> body = readJsonBody(exchange);
            Map<String, Object> result = switch (path) {
                case "/api/enroll" -> session.enroll(body);
                case "/api/challenge" -> session.challenge(body);
                case "/api/verify" -> session.verify(body);
                case "/api/reset" -> session.reset();
                default -> null;
            };

            if (result == null) {
                sendJson(exchange, 404, Map.of("error", "Unknown endpoint: " + path));
            } else {
                sendJson(exchange, 200, result);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Throwable t) {
            // Never let an Error (e.g. NoClassDefFoundError for a missing nested class) drop the
            // connection without a response — that surfaces in the browser as a bare "Failed to fetch".
            System.err.println("Unhandled error handling " + exchange.getRequestURI() + ": " + t);
            t.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "Internal error: " + t));
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ static file

    private void serveIndex(HttpExchange exchange) throws IOException {
        byte[] bytes = loadIndex();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private byte[] loadIndex() throws IOException {
        try (InputStream in = DemoServer.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        // Fallback for running from the project root without the copied resource.
        Path fallback = Path.of("cotp-demo", "web", "index.html");
        if (!Files.exists(fallback)) {
            fallback = Path.of("web", "index.html");
        }
        if (Files.exists(fallback)) {
            return Files.readAllBytes(fallback);
        }
        String html = "<html><body><h1>COTP demo</h1><p>index.html not found.</p></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readAllBytes();
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        return Json.parseObject(body);
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> payload)
            throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
