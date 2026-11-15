package io.github.royalgit.pineapplemonitor.metrics;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.royalgit.pineapplemonitor.run.RunResult;

/** Logs metric failures so that they can never fail a run or stop the orchestrator. */
public final class SafeMetricsSink implements MetricsSink {

    private static final Logger log = LoggerFactory.getLogger(SafeMetricsSink.class);

    private final MetricsSink delegate;

    public SafeMetricsSink(MetricsSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void recordRun(RunResult result) {
        safely(() -> delegate.recordRun(result));
    }

    @Override
    public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
        safely(() -> delegate.recordSkip(canaryName, dueTime, reason));
    }

    @Override
    public void recordCustom(CustomMetric metric) {
        safely(() -> delegate.recordCustom(metric));
    }

    @Override
    public void recordHeartbeat() {
        safely(delegate::recordHeartbeat);
    }

    private static void safely(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("Could not record a metric", e);
        }
    }
}
