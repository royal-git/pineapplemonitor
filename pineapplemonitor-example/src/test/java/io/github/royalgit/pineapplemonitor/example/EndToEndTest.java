package io.github.royalgit.pineapplemonitor.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.royalgit.pineapplemonitor.account.AccountProvider;
import io.github.royalgit.pineapplemonitor.account.EnvAccountProvider;
import io.github.royalgit.pineapplemonitor.config.CanaryConfig;
import io.github.royalgit.pineapplemonitor.config.FileConfigStore;
import io.github.royalgit.pineapplemonitor.metrics.CustomMetric;
import io.github.royalgit.pineapplemonitor.metrics.MetricsSink;
import io.github.royalgit.pineapplemonitor.metrics.SkipReason;
import io.github.royalgit.pineapplemonitor.run.Outcome;
import io.github.royalgit.pineapplemonitor.run.RunResult;
import io.github.royalgit.pineapplemonitor.run.StepResult;

/** Runs the real service: settings come from a file, and the canary calls the example API over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"canary.config-reload-interval=100ms", "canary.poll-interval=100ms"})
@Import(EndToEndTest.TestBeans.class)
class EndToEndTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        writeSettings(false);
        registry.add("canary.config-file", () -> directory.resolve("canary.yml").toString());
    }

    private static void writeSettings(boolean enabled) throws Exception {
        writeFile("""
                canaries:
                  edit-account:
                    enabled: %s
                    account: edit-account-test
                    interval: 1s
                    step-timeout: 5s
                """.formatted(enabled));
    }

    /** Replaces the file in one step, as saving through version control or a deploy would. */
    private static void writeFile(String contents) throws Exception {
        Path temporary = directory.resolve("canary.yml.tmp");
        Files.writeString(temporary, contents);
        Files.move(temporary, directory.resolve("canary.yml"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static final class Recording implements MetricsSink {

        final List<RunResult> runs = new CopyOnWriteArrayList<>();
        final List<CustomMetric> custom = new CopyOnWriteArrayList<>();

        @Override
        public void recordRun(RunResult result) {
            runs.add(result);
        }

        @Override
        public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
        }

        @Override
        public void recordCustom(CustomMetric metric) {
            custom.add(metric);
        }

        @Override
        public void recordHeartbeat() {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        AccountProvider accountProvider() {
            return new EnvAccountProvider(Map.of("CANARY_ACCOUNT_EDIT_ACCOUNT_TEST_API_KEY", "demo-key"));
        }

        @Bean
        MetricsSink metricsSink() {
            return new Recording();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    MetricsSink metrics;

    @Autowired
    Environment environment;

    private Recording recording() {
        return (Recording) metrics;
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private URI displayName() {
        return url("/example-api/accounts/" + EditAccountCanary.ACCOUNT_ID + "/display-name");
    }

    private String readDisplayName() throws Exception {
        return http.send(HttpRequest.newBuilder(displayName()).header("X-Api-Key", "demo-key").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void writeDisplayName(String name) throws Exception {
        http.send(HttpRequest.newBuilder(displayName()).header("X-Api-Key", "demo-key").header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(name)).build(), HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void staysAliveWithoutRelyingOnAWebServer() {
        assertThat(environment.getProperty("spring.main.keep-alive")).isEqualTo("true");
    }

    @Test
    void theBundledSettingsFileIsValid() {
        assertThat(new FileConfigStore(Path.of("canary.yml")).loadAll()).extracting(CanaryConfig::canary).containsExactly("edit-account", "status-check");
    }

    @Test
    void wrongApiKeyIsRejectedByTheExampleApi() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(displayName()).header("X-Api-Key", "wrong").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void settingsInTheFileMakeTheCanaryRunAndItRecoversFromACrash() throws Exception {
        writeSettings(true);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(recording().runs).isNotEmpty());
        RunResult first = recording().runs.get(0);
        assertThat(first.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(first.steps()).extracting(StepResult::step).containsExactly("createClient", "edit", "confirm", "restoreBaseline");
        assertThat(recording().custom).anySatisfy(metric -> {
            assertThat(metric.name()).isEqualTo("edits.confirmed");
            assertThat(metric.canary()).isEqualTo("edit-account");
            assertThat(metric.step()).isEqualTo("confirm");
        });

        // A broken edit to the file leaves the canary running on its last good settings.
        writeFile("canaries:\n  edit-account:\n    interval: soon\n");
        int runsBeforeBreaking = recording().runs.size();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(recording().runs.size()).isGreaterThan(runsBeforeBreaking + 1));

        // Stop the canary, then leave the account as a host that died mid-edit would.
        writeSettings(false);
        Thread.sleep(500);
        assertThat(readDisplayName()).isEqualTo(EditAccountCanary.BASELINE_NAME);
        int runsBefore = recording().runs.size();
        writeDisplayName("canary-left-behind-by-a-crash");

        writeSettings(true);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(recording().runs.size()).isGreaterThan(runsBefore));
        assertThat(recording().runs.get(runsBefore).outcome()).isEqualTo(Outcome.PASSED);

        writeSettings(false);
        Thread.sleep(500);
        assertThat(readDisplayName()).isEqualTo(EditAccountCanary.BASELINE_NAME);
    }
}
