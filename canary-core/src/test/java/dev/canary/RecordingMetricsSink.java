package dev.canary;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.canary.metrics.CustomMetric;
import dev.canary.metrics.MetricsSink;
import dev.canary.metrics.SkipReason;
import dev.canary.run.RunResult;

/** Keeps everything it is given, for tests to check. */
public final class RecordingMetricsSink implements MetricsSink {

    public record Skip(String canary, Instant dueTime, SkipReason reason) {
    }

    public final List<RunResult> runs = new CopyOnWriteArrayList<>();
    public final List<Skip> skips = new CopyOnWriteArrayList<>();
    public final List<CustomMetric> custom = new CopyOnWriteArrayList<>();
    public final AtomicInteger heartbeats = new AtomicInteger();

    @Override
    public void recordRun(RunResult result) {
        runs.add(result);
    }

    @Override
    public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
        skips.add(new Skip(canaryName, dueTime, reason));
    }

    @Override
    public void recordCustom(CustomMetric metric) {
        custom.add(metric);
    }

    @Override
    public void recordHeartbeat() {
        heartbeats.incrementAndGet();
    }
}
