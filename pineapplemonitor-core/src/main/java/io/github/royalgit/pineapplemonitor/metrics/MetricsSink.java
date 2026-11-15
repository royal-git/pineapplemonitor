package io.github.royalgit.pineapplemonitor.metrics;

import java.time.Instant;

import io.github.royalgit.pineapplemonitor.run.RunResult;

/** Where the framework sends all metrics. */
public interface MetricsSink {

    void recordRun(RunResult result);

    void recordSkip(String canaryName, Instant dueTime, SkipReason reason);

    void recordCustom(CustomMetric metric);

    void recordHeartbeat();
}
