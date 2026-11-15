package io.github.royalgit.pineapplemonitor.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import io.github.royalgit.pineapplemonitor.MutableClock;
import io.github.royalgit.pineapplemonitor.RecordingMetricsSink;
import io.github.royalgit.pineapplemonitor.account.AccountProvider;
import io.github.royalgit.pineapplemonitor.api.Account;
import io.github.royalgit.pineapplemonitor.api.Canary;
import io.github.royalgit.pineapplemonitor.api.Cleanup;
import io.github.royalgit.pineapplemonitor.api.RunContext;
import io.github.royalgit.pineapplemonitor.api.Step;
import io.github.royalgit.pineapplemonitor.config.CanaryConfig;
import io.github.royalgit.pineapplemonitor.lock.InMemoryRunLock;
import io.github.royalgit.pineapplemonitor.lock.RunLock;
import io.github.royalgit.pineapplemonitor.metrics.SkipReason;
import io.github.royalgit.pineapplemonitor.run.CanaryDefinition;
import io.github.royalgit.pineapplemonitor.run.Outcome;
import io.github.royalgit.pineapplemonitor.run.RunResult;
import io.github.royalgit.pineapplemonitor.run.StepRunner;

class CanaryWorkerTest {

    /** Aligned to the five-minute schedule the tests use. */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final CanaryWorker.Settings SETTINGS = new CanaryWorker.Settings(Duration.ofSeconds(30), LEASE, Duration.ofSeconds(20));

    private static final AtomicInteger stepRuns = new AtomicInteger();
    private static final List<String> cleanups = new CopyOnWriteArrayList<>();
    private static volatile Runnable onStep = () -> {};

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryRunLock lock = new InMemoryRunLock(clock);
    private final RecordingMetricsSink metrics = new RecordingMetricsSink();
    private final AccountProvider accounts = (reference, request) -> new Account(reference, "credentials");

    @BeforeEach
    void reset() {
        stepRuns.set(0);
        cleanups.clear();
        onStep = () -> {};
    }

    @Canary("counting")
    static class Counting {
        @Step(1) void step() {
            stepRuns.incrementAndGet();
            onStep.run();
        }
        @Cleanup void cleanUp() {
            cleanups.add("cleanup");
        }
    }

    private CanaryWorker worker(RunLock runLock, AccountProvider accountProvider, CanaryWorker.Settings settings) {
        CanaryDefinition definition = CanaryDefinition.of(Counting.class);
        CanaryCatalog catalog = new CanaryCatalog(List.of(definition), d -> BeanUtils.instantiateClass(d.type()));
        StepRunner runner = new StepRunner(clock, Duration.ofMillis(100));
        return new CanaryWorker(definition, catalog, runLock, runner, accountProvider, metrics, settings, clock);
    }

    private CanaryWorker enabledWorker() {
        CanaryWorker worker = worker(lock, accounts, SETTINGS);
        worker.updateConfig(config(true, 1));
        return worker;
    }

    private static CanaryConfig config(boolean enabled, int parallelism) {
        return new CanaryConfig("counting", enabled, "test-account", FIVE_MINUTES, parallelism, Duration.ofSeconds(5));
    }

    @Test
    void doesNotRunUntilDue() {
        CanaryWorker worker = enabledWorker();

        worker.pollOnce();
        assertThat(stepRuns).hasValue(1);

        assertThat(worker.pollOnce()).isEqualTo(FIVE_MINUTES);
        clock.advance(FIVE_MINUTES.minusSeconds(1));
        assertThat(worker.pollOnce()).isEqualTo(Duration.ofSeconds(1));
        assertThat(stepRuns).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        worker.pollOnce();
        assertThat(stepRuns).hasValue(2);
        assertThat(metrics.runs.get(1).dueTime()).isEqualTo(T0.plus(FIVE_MINUTES));
    }

    @Test
    void overdueCanaryRunsOnce() {
        CanaryWorker worker = enabledWorker();
        worker.pollOnce();

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        worker.pollOnce();
        Duration wait = worker.pollOnce();

        assertThat(stepRuns).hasValue(2);
        assertThat(metrics.runs.get(1).dueTime()).isEqualTo(T0.plus(FIVE_MINUTES));
        assertThat(wait).isEqualTo(FIVE_MINUTES.minusSeconds(1));
    }

    @Test
    void roundThatOverranRunsAgainOnceAtTheLatestSlot() {
        CanaryWorker worker = enabledWorker();
        onStep = () -> {
            onStep = () -> {};
            clock.advance(Duration.ofMinutes(12));
        };

        worker.pollOnce();
        worker.pollOnce();
        worker.pollOnce();

        assertThat(stepRuns).hasValue(2);
        assertThat(metrics.runs.get(1).dueTime()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void disabledOrUnconfiguredCanaryDoesNotRun() {
        CanaryWorker worker = worker(lock, accounts, SETTINGS);

        assertThat(worker.pollOnce()).isEqualTo(SETTINGS.pollInterval());
        worker.updateConfig(config(false, 1));
        assertThat(worker.pollOnce()).isEqualTo(SETTINGS.pollInterval());

        assertThat(stepRuns).hasValue(0);
    }

    @Test
    void twoWorkersSharingALockRunEachRoundOnce() {
        CanaryWorker first = enabledWorker();
        CanaryWorker second = enabledWorker();

        first.pollOnce();
        second.pollOnce();
        assertThat(stepRuns).hasValue(1);

        clock.advance(FIVE_MINUTES);
        second.pollOnce();
        first.pollOnce();
        assertThat(stepRuns).hasValue(2);
    }

    @Test
    void anotherHostTakesOverWhenTheLeaseExpires() {
        lock.tryAcquire("counting", LEASE).orElseThrow();
        CanaryWorker worker = enabledWorker();

        Duration wait = worker.pollOnce();
        assertThat(stepRuns).hasValue(0);
        assertThat(wait).isBetween(Duration.ofMillis(22_500), Duration.ofMillis(37_500));

        clock.advance(LEASE);
        worker.pollOnce();

        assertThat(stepRuns).hasValue(1);
        assertThat(metrics.runs.get(0).dueTime()).isEqualTo(T0);
        assertThat(metrics.runs.get(0).startedAt()).isEqualTo(T0.plus(LEASE));
    }

    /** Lets a test decide how leases behave. */
    private final class ScriptedLock implements RunLock {

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean released = new AtomicBoolean();
        volatile Runnable onRenew = () -> {};
        volatile boolean renewResult = true;

        @Override
        public Optional<Lease> tryAcquire(String canaryName, Duration leaseDuration) {
            return Optional.of(new Lease() {
                @Override
                public Instant dueTime() {
                    return T0;
                }

                @Override
                public boolean renew() {
                    onRenew.run();
                    return renewResult;
                }

                @Override
                public void complete(Instant nextRunAt) {
                    completed.set(true);
                }

                @Override
                public void release() {
                    released.set(true);
                }
            });
        }

        @Override
        public Instant nextRunAt(String canaryName) {
            return clock.instant();
        }
    }

    private static final CanaryWorker.Settings FAST_RENEWAL = new CanaryWorker.Settings(Duration.ofSeconds(30), LEASE, Duration.ofMillis(10));

    @Test
    void lostLeaseAbortsTheRunAndCleanupStillRuns() {
        onStep = () -> sleep(Duration.ofSeconds(4));
        ScriptedLock scripted = new ScriptedLock();
        scripted.renewResult = false;
        CanaryWorker worker = worker(scripted, accounts, FAST_RENEWAL);
        worker.updateConfig(config(true, 1));

        worker.pollOnce();

        RunResult result = metrics.runs.get(0);
        assertThat(result.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(cleanups).hasSize(1);
        assertThat(scripted.completed).isFalse();
        assertThat(scripted.released).isFalse();
    }

    @Test
    void renewalErrorDoesNotStopTheRunWhileTheLeaseIsStillLive() {
        onStep = () -> sleep(Duration.ofMillis(100));
        ScriptedLock scripted = new ScriptedLock();
        scripted.onRenew = () -> {
            throw new IllegalStateException("backend down");
        };
        CanaryWorker worker = worker(scripted, accounts, FAST_RENEWAL);
        worker.updateConfig(config(true, 1));

        worker.pollOnce();

        assertThat(metrics.runs.get(0).outcome()).isEqualTo(Outcome.PASSED);
        assertThat(scripted.completed).isTrue();
    }

    @Test
    void renewalErrorPastTheLeaseAbortsTheRun() {
        onStep = () -> sleep(Duration.ofSeconds(4));
        ScriptedLock scripted = new ScriptedLock();
        scripted.onRenew = () -> {
            clock.advance(LEASE);
            throw new IllegalStateException("backend down");
        };
        CanaryWorker worker = worker(scripted, accounts, FAST_RENEWAL);
        worker.updateConfig(config(true, 1));

        worker.pollOnce();

        assertThat(metrics.runs.get(0).outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(scripted.completed).isFalse();
    }

    @Test
    void lockErrorSkipsAndRetriesAtTheNextPoll() {
        RunLock broken = new RunLock() {
            @Override
            public Optional<Lease> tryAcquire(String canaryName, Duration leaseDuration) {
                throw new IllegalStateException("backend down");
            }

            @Override
            public Instant nextRunAt(String canaryName) {
                throw new IllegalStateException("backend down");
            }
        };
        CanaryWorker worker = worker(broken, accounts, SETTINGS);
        worker.updateConfig(config(true, 1));

        assertThat(worker.pollOnce()).isEqualTo(SETTINGS.pollInterval());

        assertThat(stepRuns).hasValue(0);
        assertThat(metrics.skips).containsExactly(new RecordingMetricsSink.Skip("counting", T0, SkipReason.LOCK_ERROR));
    }

    @Test
    void parallelismRunsConcurrentExecutionsOfOneRound() {
        CountDownLatch allStarted = new CountDownLatch(3);
        AtomicBoolean ranTogether = new AtomicBoolean(true);
        onStep = () -> {
            allStarted.countDown();
            try {
                ranTogether.compareAndSet(true, allStarted.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        CanaryWorker worker = worker(lock, accounts, SETTINGS);
        worker.updateConfig(config(true, 3));

        worker.pollOnce();

        assertThat(ranTogether).isTrue();
        assertThat(metrics.runs).extracting(RunResult::execution).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(metrics.runs).extracting(RunResult::runId).doesNotHaveDuplicates();
        assertThat(metrics.runs).extracting(RunResult::dueTime).containsOnly(T0);
        assertThat(worker.pollOnce()).isEqualTo(FIVE_MINUTES);
    }

    @Test
    void accountErrorSkipsTheExecutionAndTheScheduleMovesOn() {
        AccountProvider broken = (reference, request) -> {
            throw new IllegalStateException("cannot assume role");
        };
        CanaryWorker worker = worker(lock, broken, SETTINGS);
        worker.updateConfig(config(true, 1));

        worker.pollOnce();

        assertThat(stepRuns).hasValue(0);
        assertThat(metrics.runs).isEmpty();
        assertThat(metrics.skips).containsExactly(new RecordingMetricsSink.Skip("counting", T0, SkipReason.ACCOUNT_ERROR));
        assertThat(worker.pollOnce()).isEqualTo(FIVE_MINUTES);
    }

    @Test
    void runInProgressKeepsTheSettingsItStartedWith() {
        CanaryWorker worker = enabledWorker();
        onStep = () -> worker.updateConfig(config(false, 5));

        worker.pollOnce();

        assertThat(metrics.runs).singleElement().satisfies(run -> assertThat(run.outcome()).isEqualTo(Outcome.PASSED));
        clock.advance(FIVE_MINUTES);
        worker.pollOnce();
        assertThat(stepRuns).hasValue(1);
    }

    @Test
    void newIntervalAppliesOnceTheCurrentDueTimeIsReached() {
        CanaryWorker worker = enabledWorker();
        worker.pollOnce();

        worker.updateConfig(new CanaryConfig("counting", true, "test-account", Duration.ofHours(1), 1, Duration.ofSeconds(5)));
        assertThat(worker.pollOnce()).isEqualTo(FIVE_MINUTES);

        clock.advance(FIVE_MINUTES);
        worker.pollOnce();
        assertThat(stepRuns).hasValue(2);
        assertThat(worker.pollOnce()).isEqualTo(Duration.ofMinutes(55));
    }

    @Test
    void credentialsAreClosedAfterEachExecution() {
        AtomicInteger closed = new AtomicInteger();
        AccountProvider closing = (reference, request) -> new Account(reference, (AutoCloseable) closed::incrementAndGet);
        CanaryWorker worker = worker(lock, closing, SETTINGS);
        worker.updateConfig(config(true, 2));

        worker.pollOnce();

        assertThat(closed).hasValue(2);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
