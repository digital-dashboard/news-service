package com.j11a.argus.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthProbeTest {

    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI startServer(int status, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(LIVENESS_PATH, exchange -> {
            try {
                Thread.sleep(delayMillis);
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + LIVENESS_PATH);
    }

    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void returnsZeroWhenLivenessIsUp() throws IOException {
        assertThat(HealthProbe.check(startServer(200, 0), SHORT_TIMEOUT)).isZero();
    }

    @Test
    void returnsOneOnServiceUnavailableAndReportsTheStatus() throws IOException {
        URI target = startServer(503, 0);

        assertThat(stderrOf(() -> assertThat(HealthProbe.check(target, SHORT_TIMEOUT)).isEqualTo(1)))
                .isEqualTo("health probe failed: unexpected status 503" + System.lineSeparator());
    }

    @Test
    void returnsOneWhenTheConnectionIsRefused() throws IOException {
        URI unreachable = startServer(200, 0);
        server.stop(0);

        String stderr = stderrOf(() -> assertThat(HealthProbe.check(unreachable, SHORT_TIMEOUT)).isEqualTo(1));

        assertThat(stderr).startsWith("health probe failed: ConnectException").hasLineCount(1);
    }

    @Test
    void returnsOneOnTimeout() throws IOException {
        URI slow = startServer(200, 2000);

        String stderr = stderrOf(() -> assertThat(HealthProbe.check(slow, Duration.ofMillis(200))).isEqualTo(1));

        assertThat(stderr).contains("health probe failed: ").contains("Timeout");
    }

    @Test
    void staysSilentWhenLivenessIsUp() throws IOException {
        URI target = startServer(200, 0);

        assertThat(stderrOf(() -> HealthProbe.check(target, SHORT_TIMEOUT))).isEmpty();
    }

    @Test
    void defaultTargetIsTheLivenessGroupOnPort8080() {
        assertThat(HealthProbe.DEFAULT_URL).isEqualTo("http://localhost:8080" + LIVENESS_PATH);
    }
}
