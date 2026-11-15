package dev.canary.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one execution of a canary: {@code @Before}, the steps in order, {@code @After}, then
 * {@code @Cleanup}. Each method runs on its own virtual thread with a timeout.
 *
 * <p>Interrupting the calling thread aborts the current method. {@code @After} and {@code @Cleanup}
 * still run, and the thread's interrupt flag is restored before returning.
 */
public final class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    /** The name reported when the canary class cannot be created. */
    static final String CONSTRUCTOR = "constructor";

    private final Clock clock;
    private final Duration interruptGrace;

    /**
     * @param interruptGrace how long to wait for a method to stop once interrupted. A method that
     *     ignores interrupts is left running in the background so that it cannot hold up the run.
     */
    public StepRunner(Clock clock, Duration interruptGrace) {
        this.clock = clock;
        this.interruptGrace = interruptGrace;
    }

    public RunResult run(CanaryDefinition canary, Duration stepTimeout, DefaultRunContext context, Supplier<Object> instances) {
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        Execution execution = new Execution(canary, stepTimeout, context);
        try {
            execution.run(instances);
        } finally {
            if (execution.interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        return new RunResult(canary.name(), context.runId(), context.execution(), context.dueTime(), startedAt, duration,
                overallOutcome(execution.results), execution.results);
    }

    private static Outcome overallOutcome(List<StepResult> results) {
        return results.stream()
                .map(StepResult::outcome)
                .filter(outcome -> outcome != Outcome.PASSED && outcome != Outcome.NOT_RUN)
                .findFirst()
                .orElse(Outcome.PASSED);
    }

    private final class Execution {

        private final CanaryDefinition canary;
        private final Duration stepTimeout;
        private final DefaultRunContext context;
        private final List<StepResult> results = new ArrayList<>();
        private boolean interrupted;

        Execution(CanaryDefinition canary, Duration stepTimeout, DefaultRunContext context) {
            this.canary = canary;
            this.stepTimeout = stepTimeout;
            this.context = context;
        }

        void run(Supplier<Object> instances) {
            Object instance;
            try {
                instance = instances.get();
            } catch (RuntimeException e) {
                log.warn("canary={} runId={} could not be created", canary.name(), context.runId(), e);
                results.add(new StepResult(CONSTRUCTOR, Phase.BEFORE, Outcome.FAILED, Duration.ZERO, describe(e)));
                markNotRun(canary.before());
                canary.steps().forEach(this::markNotRun);
                markNotRun(canary.after());
                markNotRun(canary.cleanup());
                return;
            }

            boolean beforePassed = canary.before() == null || invoke(canary.before(), instance);
            boolean carryOn = beforePassed;
            for (CanaryMethod step : canary.steps()) {
                if (carryOn) {
                    carryOn = invoke(step, instance);
                } else {
                    markNotRun(step);
                }
            }
            if (beforePassed) {
                invokeIfPresent(canary.after(), instance);
            } else {
                markNotRun(canary.after());
            }
            invokeIfPresent(canary.cleanup(), instance);
        }

        private void invokeIfPresent(CanaryMethod method, Object instance) {
            if (method != null) {
                invoke(method, instance);
            }
        }

        private void markNotRun(CanaryMethod method) {
            if (method != null) {
                results.add(new StepResult(method.name(), method.phase(), Outcome.NOT_RUN, Duration.ZERO, null));
            }
        }

        /** Returns whether the method passed. */
        private boolean invoke(CanaryMethod method, Object instance) {
            context.currentStep(method.name());
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            long startNanos = System.nanoTime();
            Thread thread = Thread.ofVirtual().name("canary-" + canary.name() + "-" + method.name()).start(() -> {
                try {
                    method.invoke(instance, context);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });

            Outcome outcome;
            String failure;
            try {
                if (thread.join(stepTimeout)) {
                    Throwable t = thrown.get();
                    outcome = t == null ? Outcome.PASSED : Outcome.FAILED;
                    failure = t == null ? null : describe(t);
                    if (t != null) {
                        log.warn("canary={} runId={} step={} failed", canary.name(), context.runId(), method.name(), t);
                    }
                } else {
                    outcome = Outcome.TIMED_OUT;
                    failure = "Timed out after " + stepTimeout;
                    log.warn("canary={} runId={} step={} timed out after {}", canary.name(), context.runId(), method.name(), stepTimeout);
                    stop(thread, method);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                outcome = Outcome.ABORTED;
                failure = "Interrupted";
                log.info("canary={} runId={} step={} aborted", canary.name(), context.runId(), method.name());
                stop(thread, method);
            }

            results.add(new StepResult(method.name(), method.phase(), outcome,
                    Duration.ofNanos(System.nanoTime() - startNanos), failure));
            return outcome == Outcome.PASSED;
        }

        private void stop(Thread thread, CanaryMethod method) {
            thread.interrupt();
            boolean stopped;
            try {
                stopped = thread.join(interruptGrace);
            } catch (InterruptedException e) {
                interrupted = true;
                stopped = !thread.isAlive();
            }
            if (!stopped) {
                log.warn("canary={} runId={} step={} ignored its interrupt and is still running in the background",
                        canary.name(), context.runId(), method.name());
            }
        }

        private static String describe(Throwable t) {
            return t.getMessage() == null ? t.getClass().getName() : t.getClass().getName() + ": " + t.getMessage();
        }
    }
}
