package com.j11a.argus.probe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container health check: the runtime image has no shell, curl or wget. */
public final class HealthProbe {

    static final String DEFAULT_URL = "http://localhost:8080/actuator/health/liveness";
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int HTTP_OK = 200;

    private HealthProbe() {
    }

    public static void main(String[] args) {
        URI target = URI.create(args.length > 0 ? args[0] : DEFAULT_URL);
        System.exit(check(target, TIMEOUT));
    }

    static int check(URI target, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout).GET().build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == HTTP_OK) {
                return 0;
            }
            return fail("unexpected status " + status);
        } catch (IOException e) {
            return fail(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(e);
        }
    }

    private static int fail(Exception e) {
        String type = e.getClass().getSimpleName();
        return fail(e.getMessage() == null ? type : type + ": " + e.getMessage());
    }

    private static int fail(String reason) {
        System.err.println("health probe failed: " + reason);
        return 1;
    }
}
