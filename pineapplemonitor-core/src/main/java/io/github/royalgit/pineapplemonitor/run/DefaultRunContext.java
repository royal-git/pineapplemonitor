package io.github.royalgit.pineapplemonitor.run;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.royalgit.pineapplemonitor.api.Account;
import io.github.royalgit.pineapplemonitor.api.CanaryMetrics;
import io.github.royalgit.pineapplemonitor.api.RunContext;
import io.github.royalgit.pineapplemonitor.metrics.CustomMetric;
import io.github.royalgit.pineapplemonitor.metrics.MetricsSink;

/** The context of one execution. The step runner tells it which method is current. */
public final class DefaultRunContext implements RunContext, CanaryMetrics {

    private static final Logger log = LoggerFactory.getLogger(DefaultRunContext.class);

    private final String canary;
    private final String runId;
    private final Instant dueTime;
    private final int execution;
    private final Account account;
    private final MetricsSink metrics;
    private volatile String currentStep = "";

    public DefaultRunContext(String canary, String runId, Instant dueTime, int execution, Account account, MetricsSink metrics) {
        this.canary = canary;
        this.runId = runId;
        this.dueTime = dueTime;
        this.execution = execution;
        this.account = account;
        this.metrics = metrics;
    }

    @Override
    public String runId() {
        return runId;
    }

    @Override
    public Instant dueTime() {
        return dueTime;
    }

    @Override
    public int execution() {
        return execution;
    }

    @Override
    public Account account() {
        return account;
    }

    @Override
    public CanaryMetrics metrics() {
        return this;
    }

    @Override
    public void count(String name, long value, Map<String, String> attributes) {
        emit(CustomMetric.Kind.COUNTER, name, value, "", attributes);
    }

    @Override
    public void record(String name, double value, String unit, Map<String, String> attributes) {
        emit(CustomMetric.Kind.HISTOGRAM, name, value, unit, attributes);
    }

    private void emit(CustomMetric.Kind kind, String name, double value, String unit, Map<String, String> attributes) {
        try {
            metrics.recordCustom(new CustomMetric(kind, name, value, unit, canary, currentStep, attributes));
        } catch (RuntimeException e) {
            log.warn("canary={} step={} could not record custom metric {}", canary, currentStep, name, e);
        }
    }

    void currentStep(String step) {
        currentStep = step;
    }
}
