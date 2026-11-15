package dev.canary.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.canary.RecordingMetricsSink;
import dev.canary.api.Account;
import dev.canary.api.After;
import dev.canary.api.Before;
import dev.canary.api.Canary;
import dev.canary.api.Cleanup;
import dev.canary.api.RunContext;
import dev.canary.api.Step;

class StepRunnerTest {

    private static final List<String> calls = new CopyOnWriteArrayList<>();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RecordingMetricsSink metrics = new RecordingMetricsSink();
    private final StepRunner runner = new StepRunner(Clock.systemUTC(), Duration.ofMillis(100));

    @BeforeEach
    void reset() {
        calls.clear();
    }

    private RunResult run(Object instance) {
        return run(instance, TIMEOUT);
    }

    private RunResult run(Object instance, Duration timeout) {
        return run(CanaryDefinition.of(instance.getClass()), timeout, () -> instance);
    }

    private RunResult run(CanaryDefinition definition, Duration timeout, Supplier<Object> instances) {
        DefaultRunContext context = new DefaultRunContext(definition.name(), "run-1", Instant.EPOCH, 0,
                new Account("test-account", "credentials"), metrics);
        return runner.run(definition, timeout, context, instances);
    }

    private static List<Outcome> outcomes(RunResult result) {
        return result.steps().stream().map(StepResult::outcome).toList();
    }

    @Canary("in-order")
    static class InOrder {
        @Before void setUp() { calls.add("before"); }
        @Step(2) void second() { calls.add("step2"); }
        @Step(1) void first() { calls.add("step1"); }
        @After void tearDown() { calls.add("after"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void runsEveryMethodInOrder() {
        RunResult result = run(new InOrder());

        assertThat(calls).containsExactly("before", "step1", "step2", "after", "cleanup");
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.steps()).extracting(StepResult::step).containsExactly("setUp", "first", "second", "tearDown", "cleanUp");
        assertThat(result.steps()).extracting(StepResult::phase)
                .containsExactly(Phase.BEFORE, Phase.STEP, Phase.STEP, Phase.AFTER, Phase.CLEANUP);
    }

    @Canary("step-fails")
    static class StepFails {
        @Step(1) void first() { throw new AssertionError("expected edit"); }
        @Step(2) void second() { calls.add("step2"); }
        @After void tearDown() { calls.add("after"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void failedStepSkipsLaterStepsButNotAfterOrCleanup() {
        RunResult result = run(new StepFails());

        assertThat(calls).containsExactly("after", "cleanup");
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(outcomes(result)).containsExactly(Outcome.FAILED, Outcome.NOT_RUN, Outcome.PASSED, Outcome.PASSED);
        assertThat(result.steps().get(0).failure()).contains("expected edit");
    }

    @Canary("before-fails")
    static class BeforeFails {
        @Before void setUp() { throw new IllegalStateException("no client"); }
        @Step(1) void first() { calls.add("step1"); }
        @After void tearDown() { calls.add("after"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void failedBeforeSkipsStepsAndAfterButNotCleanup() {
        RunResult result = run(new BeforeFails());

        assertThat(calls).containsExactly("cleanup");
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(outcomes(result)).containsExactly(Outcome.FAILED, Outcome.NOT_RUN, Outcome.NOT_RUN, Outcome.PASSED);
    }

    @Canary("cleanup-fails")
    static class CleanupFails {
        @Step(1) void first() {}
        @Cleanup void cleanUp() { throw new IllegalStateException("baseline not restored"); }
    }

    @Test
    void failedCleanupFailsTheRun() {
        assertThat(run(new CleanupFails()).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Canary("after-fails")
    static class AfterFails {
        @Step(1) void first() {}
        @After void tearDown() { throw new IllegalStateException("could not tear down"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void failedAfterFailsTheRunAndCleanupStillRuns() {
        RunResult result = run(new AfterFails());

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(calls).containsExactly("cleanup");
    }

    @Canary("slow")
    static class Slow {
        @Step(1) void first() throws InterruptedException { Thread.sleep(Duration.ofMinutes(1)); }
        @Step(2) void second() { calls.add("step2"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void timedOutStepStopsTheRunAndCleanupStillRuns() {
        RunResult result = run(new Slow(), Duration.ofMillis(50));

        assertThat(result.outcome()).isEqualTo(Outcome.TIMED_OUT);
        assertThat(outcomes(result)).containsExactly(Outcome.TIMED_OUT, Outcome.NOT_RUN, Outcome.PASSED);
        assertThat(calls).containsExactly("cleanup");
    }

    @Canary("ignores-interrupts")
    static class IgnoresInterrupts {
        final CountDownLatch release = new CountDownLatch(1);

        @Step(1) void first() {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // Keeps waiting, as badly behaved code might.
                }
            }
        }
    }

    @Test
    void methodThatIgnoresInterruptsCannotHoldUpTheRun() {
        IgnoresInterrupts canary = new IgnoresInterrupts();
        long start = System.nanoTime();

        RunResult result = run(canary, Duration.ofMillis(50));

        assertThat(result.outcome()).isEqualTo(Outcome.TIMED_OUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        canary.release.countDown();
    }

    @Canary("aborted")
    static class Aborted {
        final CountDownLatch started = new CountDownLatch(1);

        @Step(1) void first() throws InterruptedException {
            started.countDown();
            Thread.sleep(Duration.ofMinutes(1));
        }
        @Step(2) void second() { calls.add("step2"); }
        @After void tearDown() { calls.add("after"); }
        @Cleanup void cleanUp() { calls.add("cleanup"); }
    }

    @Test
    void interruptAbortsTheRunRunsCleanupAndRestoresTheFlag() throws Exception {
        Aborted canary = new Aborted();
        AtomicReference<RunResult> result = new AtomicReference<>();
        AtomicReference<Boolean> flagRestored = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(run(canary));
            flagRestored.set(Thread.currentThread().isInterrupted());
        });

        canary.started.await();
        caller.interrupt();
        caller.join();

        assertThat(result.get().outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(outcomes(result.get())).containsExactly(Outcome.ABORTED, Outcome.NOT_RUN, Outcome.PASSED, Outcome.PASSED);
        assertThat(calls).containsExactly("after", "cleanup");
        assertThat(flagRestored.get()).isTrue();
    }

    @Test
    void failureToCreateTheInstanceIsReportedAndNothingRuns() {
        RunResult result = run(CanaryDefinition.of(InOrder.class), TIMEOUT, () -> {
            throw new IllegalStateException("bad constructor");
        });

        assertThat(calls).isEmpty();
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.steps().get(0).step()).isEqualTo("constructor");
        assertThat(result.steps().get(0).failure()).contains("bad constructor");
        assertThat(outcomes(result).subList(1, result.steps().size())).containsOnly(Outcome.NOT_RUN);
    }

    @Canary("keeps-state")
    static class KeepsState {
        private final List<String> seen = new ArrayList<>();

        @Step(1) void first() { seen.add("first"); }
        @Step(2) void second() {
            calls.add("seen " + seen.size());
            seen.add("second");
        }
    }

    @Test
    void everyExecutionGetsANewInstanceAndStepsShareIt() {
        CanaryDefinition definition = CanaryDefinition.of(KeepsState.class);

        run(definition, TIMEOUT, KeepsState::new);
        run(definition, TIMEOUT, KeepsState::new);

        assertThat(calls).containsExactly("seen 1", "seen 1");
    }

    @Canary("custom-metrics")
    static class CustomMetrics {
        @Before void setUp(RunContext ctx) { ctx.metrics().count("setups", 1); }
        @Step(1) void first(RunContext ctx) { ctx.metrics().record("latency", 12.5, "ms"); }
    }

    @Test
    void customMetricsCarryTheCanaryAndCurrentStep() {
        run(new CustomMetrics());

        assertThat(metrics.custom).extracting(metric -> metric.name() + "@" + metric.canary() + "/" + metric.step())
                .containsExactly("setups@custom-metrics/setUp", "latency@custom-metrics/first");
    }
}
