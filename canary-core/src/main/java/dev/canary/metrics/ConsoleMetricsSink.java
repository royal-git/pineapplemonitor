package dev.canary.metrics;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.canary.run.RunResult;
import dev.canary.run.StepResult;

/** Writes metrics to the log, for local development. */
public final class ConsoleMetricsSink implements MetricsSink {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMetricsSink.class);

    @Override
    public void recordRun(RunResult result) {
        log.info("run canary={} outcome={} duration={}ms", result.canary(), result.outcome(), result.duration().toMillis());
        for (StepResult step : result.steps()) {
            log.info("step canary={} step={} phase={} outcome={} duration={}ms", result.canary(), step.step(), step.phase(),
                    step.outcome(), step.duration().toMillis());
        }
    }

    @Override
    public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
        log.info("skip canary={} reason={}", canaryName, reason);
    }

    @Override
    public void recordCustom(CustomMetric metric) {
        log.info("custom canary={} step={} name={} kind={} value={} unit={} attributes={}", metric.canary(), metric.step(),
                metric.name(), metric.kind(), metric.value(), metric.unit(), metric.attributes());
    }

    @Override
    public void recordHeartbeat() {
        log.debug("heartbeat");
    }
}
