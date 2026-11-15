package io.github.royalgit.pineapplemonitor.orchestrator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.royalgit.pineapplemonitor.account.AccountProvider;
import io.github.royalgit.pineapplemonitor.account.AccountRequest;
import io.github.royalgit.pineapplemonitor.api.Account;
import io.github.royalgit.pineapplemonitor.config.CanaryConfig;
import io.github.royalgit.pineapplemonitor.lock.RunLock;
import io.github.royalgit.pineapplemonitor.lock.RunLock.Lease;
import io.github.royalgit.pineapplemonitor.metrics.MetricsSink;
import io.github.royalgit.pineapplemonitor.metrics.SkipReason;
import io.github.royalgit.pineapplemonitor.run.CanaryDefinition;
import io.github.royalgit.pineapplemonitor.run.DefaultRunContext;
import io.github.royalgit.pineapplemonitor.run.RunResult;
import io.github.royalgit.pineapplemonitor.run.StepRunner;

/** The loop behind one canary's thread: poll the lock, and run the canary when it is due. */
final class CanaryWorker {

    private static final Logger log = LoggerFactory.getLogger(CanaryWorker.class);

    record Settings(Duration pollInterval, Duration leaseDuration, Duration renewInterval) {
    }

    private final CanaryDefinition canary;
    private final CanaryCatalog catalog;
    private final RunLock lock;
    private final StepRunner runner;
    private final AccountProvider accounts;
    private final MetricsSink metrics;
    private final Settings settings;
    private final Clock clock;
    private final Semaphore wake = new Semaphore(0);

    private volatile CanaryConfig config;
    private volatile boolean running = true;

    CanaryWorker(CanaryDefinition canary, CanaryCatalog catalog, RunLock lock, StepRunner runner, AccountProvider accounts,
            MetricsSink metrics, Settings settings, Clock clock) {
        this.canary = canary;
        this.catalog = catalog;
        this.lock = lock;
        this.runner = runner;
        this.accounts = accounts;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    String canaryName() {
        return canary.name();
    }

    void updateConfig(CanaryConfig newConfig) {
        if (!Objects.equals(config, newConfig)) {
            config = newConfig;
            wake.release();
        }
    }

    void stop() {
        running = false;
        wake.release();
    }

    void runLoop() {
        try {
            while (running) {
                Duration sleep = pollOnce();
                if (!sleep.isZero() && wake.tryAcquire(sleep.toMillis(), TimeUnit.MILLISECONDS)) {
                    wake.drainPermits();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Makes one attempt to run the canary, and returns how long to wait before the next one. */
    Duration pollOnce() {
        CanaryConfig current = config;
        if (current == null || !current.enabled()) {
            return settings.pollInterval();
        }

        Optional<Lease> lease;
        try {
            lease = lock.tryAcquire(canary.name(), settings.leaseDuration());
        } catch (RuntimeException e) {
            return lockError(e);
        }
        if (lease.isPresent()) {
            runRound(lease.get(), current);
            return Duration.ZERO;
        }

        Instant nextRunAt;
        try {
            nextRunAt = lock.nextRunAt(canary.name());
        } catch (RuntimeException e) {
            return lockError(e);
        }
        Instant now = clock.instant();
        return nextRunAt.isAfter(now) ? Duration.between(now, nextRunAt) : jitteredPollInterval();
    }

    private Duration lockError(RuntimeException e) {
        log.warn("canary={} lock error", canary.name(), e);
        metrics.recordSkip(canary.name(), clock.instant(), SkipReason.LOCK_ERROR);
        return settings.pollInterval();
    }

    /** Varies the wait by up to a quarter either way, so hosts do not poll in step. */
    private Duration jitteredPollInterval() {
        long millis = settings.pollInterval().toMillis();
        long spread = millis / 4;
        return Duration.ofMillis(spread == 0 ? millis : millis - spread + ThreadLocalRandom.current().nextLong(2 * spread));
    }

    private void runRound(Lease lease, CanaryConfig current) {
        Instant startedAt = clock.instant();
        List<Thread> executions = new ArrayList<>();
        for (int i = 0; i < current.parallelism(); i++) {
            int execution = i;
            executions.add(Thread.ofVirtual().name("canary-" + canary.name() + "-execution-" + i)
                    .start(() -> execute(lease, current, execution)));
        }

        AtomicBoolean leaseLost = new AtomicBoolean();
        Thread renewer = Thread.ofVirtual().name("canary-" + canary.name() + "-renewer")
                .start(() -> renewUntilInterrupted(lease, executions, leaseLost));

        boolean interrupted = false;
        for (Thread execution : executions) {
            while (true) {
                try {
                    execution.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    executions.forEach(Thread::interrupt);
                }
            }
        }
        renewer.interrupt();

        try {
            if (leaseLost.get()) {
                log.warn("canary={} lost its lease, so another host may rerun this round", canary.name());
            } else if (interrupted) {
                lease.release();
            } else {
                lease.complete(new Schedule(current.interval()).nextDue(startedAt, clock.instant()));
            }
        } catch (RuntimeException e) {
            lockError(e);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void execute(Lease lease, CanaryConfig current, int execution) {
        String runId = UUID.randomUUID().toString();
        Account account;
        try {
            account = accounts.resolve(current.account(), new AccountRequest(canary.name(), runId));
        } catch (RuntimeException e) {
            log.warn("canary={} runId={} could not get credentials for account {}", canary.name(), runId, current.account(), e);
            metrics.recordSkip(canary.name(), lease.dueTime(), SkipReason.ACCOUNT_ERROR);
            return;
        }
        try (account) {
            DefaultRunContext context = new DefaultRunContext(canary.name(), runId, lease.dueTime(), execution, account, metrics);
            RunResult result = runner.run(canary, current.stepTimeout(), context, () -> catalog.newInstance(canary));
            metrics.recordRun(result);
        }
    }

    /**
     * Stops the executions if the lease is lost. A renewal that throws is not a lost lease, so the
     * run continues until a lease that could not be renewed has expired.
     */
    private void renewUntilInterrupted(Lease lease, List<Thread> executions, AtomicBoolean leaseLost) {
        Instant lastRenewed = clock.instant();
        try {
            while (true) {
                Thread.sleep(settings.renewInterval());
                boolean lost;
                try {
                    lost = !lease.renew();
                    lastRenewed = clock.instant();
                } catch (RuntimeException e) {
                    log.warn("canary={} could not renew its lease", canary.name(), e);
                    lost = !Duration.between(lastRenewed, clock.instant()).minus(settings.leaseDuration()).isNegative();
                }
                if (lost) {
                    leaseLost.set(true);
                    executions.forEach(Thread::interrupt);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // The round has finished.
        }
    }
}
