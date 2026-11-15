package dev.canary.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;

import dev.canary.account.EnvCredentials;
import dev.canary.api.Canary;
import dev.canary.api.RunContext;
import dev.canary.api.Step;

/** Checks that the status endpoint answers, and records how long it took as a custom metric. */
@Canary("status-check")
public class StatusCheckCanary {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final URI status;

    public StatusCheckCanary(@Value("http://127.0.0.1:${local.server.port}/example-api/status") String status) {
        this.status = URI.create(status);
    }

    @Step(1)
    void check(RunContext ctx) throws Exception {
        String apiKey = ctx.account().credentials(EnvCredentials.class).get("API_KEY");
        long start = System.nanoTime();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(status).header("X-Api-Key", apiKey).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        ctx.metrics().record("request.latency", (System.nanoTime() - start) / 1_000_000.0, "ms");

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
