package dev.canary.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import dev.canary.run.RunResult;
import dev.canary.run.StepResult;

/** Publishes metrics through OpenTelemetry, configured by the standard {@code OTEL_*} variables. */
public final class OtelMetricsSink implements MetricsSink, AutoCloseable {

    private static final String SCOPE = "dev.canary";
    private static final String CUSTOM_PREFIX = "canary.custom.";

    private static final AttributeKey<String> CANARY = AttributeKey.stringKey("canary");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> STEP = AttributeKey.stringKey("step");
    private static final AttributeKey<String> PHASE = AttributeKey.stringKey("phase");
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");

    private final OpenTelemetrySdk sdk;
    private final Meter meter;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final DoubleHistogram runLag;
    private final LongCounter steps;
    private final DoubleHistogram stepDuration;
    private final LongCounter skips;
    private final LongCounter heartbeats;
    private final Map<String, LongCounter> customCounters = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> customHistograms = new ConcurrentHashMap<>();

    /**
     * Bucket boundaries in seconds for the framework's duration histograms. The SDK's defaults suit
     * milliseconds, so every run and step would otherwise land in the first bucket.
     */
    static final List<Double> SECONDS_BUCKETS = List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0,
            7.5, 10.0, 30.0, 60.0, 300.0);

    /**
     * Creates a sink that exports metrics only. The standard {@code OTEL_*} variables override the
     * defaults here, such as {@code OTEL_EXPORTER_OTLP_PROTOCOL} or {@code OTEL_METRIC_EXPORT_INTERVAL}.
     */
    public static OtelMetricsSink forEndpoint(String endpoint, String serviceName) {
        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.exporter.otlp.endpoint", endpoint,
                        "otel.exporter.otlp.protocol", "http/protobuf",
                        "otel.service.name", serviceName,
                        "otel.traces.exporter", "none",
                        "otel.logs.exporter", "none"))
                .build()
                .getOpenTelemetrySdk();
        return new OtelMetricsSink(sdk);
    }

    public OtelMetricsSink(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        this.meter = sdk.getMeter(SCOPE);
        this.runs = meter.counterBuilder("canary.runs").build();
        this.runDuration = secondsHistogram("canary.run.duration");
        this.runLag = secondsHistogram("canary.run.lag");
        this.steps = meter.counterBuilder("canary.steps").build();
        this.stepDuration = secondsHistogram("canary.step.duration");
        this.skips = meter.counterBuilder("canary.skips").build();
        this.heartbeats = meter.counterBuilder("canary.heartbeats").build();
    }

    private DoubleHistogram secondsHistogram(String name) {
        return meter.histogramBuilder(name).setUnit("s").setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS).build();
    }

    @Override
    public void recordRun(RunResult result) {
        Attributes runAttributes = Attributes.of(CANARY, result.canary(), OUTCOME, result.outcome().name());
        runs.add(1, runAttributes);
        runDuration.record(seconds(result.duration()), runAttributes);
        runLag.record(Math.max(0, seconds(Duration.between(result.dueTime(), result.startedAt()))),
                Attributes.of(CANARY, result.canary()));

        for (StepResult step : result.steps()) {
            Attributes stepAttributes = Attributes.of(CANARY, result.canary(), STEP, step.step(),
                    PHASE, step.phase().name(), OUTCOME, step.outcome().name());
            steps.add(1, stepAttributes);
            stepDuration.record(seconds(step.duration()), stepAttributes);
        }
    }

    @Override
    public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
        skips.add(1, Attributes.of(CANARY, canaryName, REASON, reason.name()));
    }

    @Override
    public void recordCustom(CustomMetric metric) {
        String name = CUSTOM_PREFIX + metric.name();
        AttributesBuilder attributes = Attributes.builder();
        metric.attributes().forEach(attributes::put);
        attributes.put(CANARY, metric.canary()).put(STEP, metric.step());

        switch (metric.kind()) {
            case COUNTER -> customCounters.computeIfAbsent(name, n -> meter.counterBuilder(n).build())
                    .add((long) metric.value(), attributes.build());
            case HISTOGRAM -> customHistograms.computeIfAbsent(name, n -> meter.histogramBuilder(n).setUnit(metric.unit()).build())
                    .record(metric.value(), attributes.build());
        }
    }

    @Override
    public void recordHeartbeat() {
        heartbeats.add(1);
    }

    /** Flushes any remaining metrics and shuts the SDK down. */
    @Override
    public void close() {
        sdk.close();
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }
}
