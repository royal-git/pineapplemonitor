package io.github.royalgit.pineapplemonitor.metrics;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import io.github.royalgit.pineapplemonitor.run.Outcome;
import io.github.royalgit.pineapplemonitor.run.Phase;
import io.github.royalgit.pineapplemonitor.run.RunResult;
import io.github.royalgit.pineapplemonitor.run.StepResult;

class OtelMetricsSinkTest {

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final OtelMetricsSink sink = new OtelMetricsSink(OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build());

    @AfterEach
    void close() {
        sink.close();
    }

    private MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void publishesRunAndStepMetricsWithTheirAttributes() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        sink.recordRun(new RunResult("edit-account", "run-1", 0, due, due.plusSeconds(2), Duration.ofSeconds(3), Outcome.FAILED,
                List.of(new StepResult("confirm", Phase.STEP, Outcome.FAILED, Duration.ofSeconds(1), "boom"))));

        Collection<MetricData> metrics = reader.collectAllMetrics();

        assertThat(metric(metrics, "canary.runs")).hasLongSumSatisfying(sum -> sum.isMonotonic().hasPointsSatisfying(point -> point
                .hasValue(1)
                .hasAttribute(AttributeKey.stringKey("canary"), "edit-account")
                .hasAttribute(AttributeKey.stringKey("outcome"), "FAILED")));
        assertThat(metric(metrics, "canary.run.lag")).hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(point -> point
                .hasSum(2.0)));
        assertThat(metric(metrics, "canary.run.duration").getUnit()).isEqualTo("s");
        assertThat(metric(metrics, "canary.steps")).hasLongSumSatisfying(sum -> sum.hasPointsSatisfying(point -> point
                .hasValue(1)
                .hasAttribute(AttributeKey.stringKey("step"), "confirm")
                .hasAttribute(AttributeKey.stringKey("phase"), "STEP")
                .hasAttribute(AttributeKey.stringKey("outcome"), "FAILED")));
        assertThat(metric(metrics, "canary.step.duration")).hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(point -> point
                .hasSum(1.0)));
    }

    @Test
    void durationHistogramsUseBucketsSizedForSeconds() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        sink.recordRun(new RunResult("edit-account", "run-1", 0, due, due, Duration.ofMillis(120), Outcome.PASSED,
                List.of(new StepResult("confirm", Phase.STEP, Outcome.PASSED, Duration.ofMillis(30), null))));

        Collection<MetricData> metrics = reader.collectAllMetrics();

        for (String name : List.of("canary.run.duration", "canary.run.lag", "canary.step.duration")) {
            assertThat(metric(metrics, name).getHistogramData().getPoints())
                    .allSatisfy(point -> assertThat(point.getBoundaries()).isEqualTo(OtelMetricsSink.SECONDS_BUCKETS));
        }
    }

    @Test
    void runIdsAndFailureTextNeverBecomeAttributes() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        sink.recordRun(new RunResult("edit-account", "run-1", 0, due, due, Duration.ofSeconds(1), Outcome.FAILED,
                List.of(new StepResult("confirm", Phase.STEP, Outcome.FAILED, Duration.ofSeconds(1), "boom"))));

        assertThat(reader.collectAllMetrics().toString()).doesNotContain("run-1").doesNotContain("boom");
    }

    @Test
    void publishesSkipsAndHeartbeats() {
        sink.recordSkip("edit-account", Instant.EPOCH, SkipReason.LOCK_ERROR);
        sink.recordHeartbeat();

        Collection<MetricData> metrics = reader.collectAllMetrics();

        assertThat(metric(metrics, "canary.skips")).hasLongSumSatisfying(sum -> sum.hasPointsSatisfying(point -> point
                .hasValue(1)
                .hasAttribute(AttributeKey.stringKey("reason"), "LOCK_ERROR")));
        assertThat(metric(metrics, "canary.heartbeats")).hasLongSumSatisfying(sum -> sum.hasPointsSatisfying(point -> point.hasValue(1)));
    }

    @Test
    void publishesCustomMetricsUnderTheirOwnPrefix() {
        sink.recordCustom(new CustomMetric(CustomMetric.Kind.COUNTER, "edits.confirmed", 2, "", "edit-account", "confirm", Map.of("tier", "free")));
        sink.recordCustom(new CustomMetric(CustomMetric.Kind.HISTOGRAM, "api.latency", 12.5, "ms", "edit-account", "edit", Map.of()));

        Collection<MetricData> metrics = reader.collectAllMetrics();

        assertThat(metric(metrics, "canary.custom.edits.confirmed")).hasLongSumSatisfying(sum -> sum.hasPointsSatisfying(point -> point
                .hasValue(2)
                .hasAttribute(AttributeKey.stringKey("canary"), "edit-account")
                .hasAttribute(AttributeKey.stringKey("step"), "confirm")
                .hasAttribute(AttributeKey.stringKey("tier"), "free")));
        assertThat(metric(metrics, "canary.custom.api.latency").getUnit()).isEqualTo("ms");
    }

    @Test
    void exportsOverOtlpWhenGivenAnEndpoint() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
        try (OtelMetricsSink exporting = OtelMetricsSink.forEndpoint("http://127.0.0.1:" + collector.getAddress().getPort(), "canary-test")) {
            exporting.recordHeartbeat();
        } finally {
            collector.stop(0);
        }

        assertThat(paths).contains("/v1/metrics");
    }
}
