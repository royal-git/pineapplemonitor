package dev.canary.metrics;

import java.time.Instant;

import dev.canary.run.RunResult;

/** Where the framework sends all metrics. */
public interface MetricsSink {

    void recordRun(RunResult result);

    void recordSkip(String canaryName, Instant dueTime, SkipReason reason);

    void recordCustom(CustomMetric metric);

    void recordHeartbeat();
}
