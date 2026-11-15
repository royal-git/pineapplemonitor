package io.github.royalgit.pineapplemonitor.metrics;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.royalgit.pineapplemonitor.run.Outcome;
import io.github.royalgit.pineapplemonitor.run.RunResult;

class SafeMetricsSinkTest {

    private static final class ThrowingSink implements MetricsSink {

        @Override
        public void recordRun(RunResult result) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public void recordSkip(String canaryName, Instant dueTime, SkipReason reason) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public void recordCustom(CustomMetric metric) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public void recordHeartbeat() {
            throw new IllegalStateException("backend down");
        }
    }

    @Test
    void metricFailuresNeverReachTheCaller() {
        SafeMetricsSink sink = new SafeMetricsSink(new ThrowingSink());

        assertThatCode(() -> {
            sink.recordRun(new RunResult("a", "run", 0, Instant.EPOCH, Instant.EPOCH, Duration.ZERO, Outcome.PASSED, List.of()));
            sink.recordSkip("a", Instant.EPOCH, SkipReason.LOCK_ERROR);
            sink.recordCustom(new CustomMetric(CustomMetric.Kind.COUNTER, "n", 1, "", "a", "s", Map.of()));
            sink.recordHeartbeat();
        }).doesNotThrowAnyException();
    }
}
