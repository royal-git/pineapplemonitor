package io.github.royalgit.pineapplemonitor.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.github.royalgit.pineapplemonitor.MutableClock;
import io.github.royalgit.pineapplemonitor.lock.RunLock.Lease;

/**
 * The behaviour every {@link RunLock} must have. To check your own implementation, extend this class
 * in a test and implement {@link #createLock}:
 *
 * <pre>
 * class DynamoDbRunLockTest extends RunLockContract {
 *
 *     private final String table = "run-lock-test-" + UUID.randomUUID();
 *
 *     &#64;Override
 *     protected RunLock createLock(Clock clock) {
 *         return new DynamoDbRunLock(dynamoDb, table, clock);
 *     }
 * }
 * </pre>
 *
 * <p>Tests here act as two hosts, A and B, each with its own lock object. Your lock must read the time
 * from the clock it is given, because the tests move that clock by hand.
 */
public abstract class RunLockContract {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    protected static final Duration LEASE = Duration.ofSeconds(60);

    protected final MutableClock clock = new MutableClock(T0);

    /**
     * Returns a lock as one host would create it. Within a test, every call must share the same
     * storage, and each test must start with empty storage, for example by using a new table or key
     * prefix in each test instance. A lock for a single instance can return the same object each time.
     */
    protected abstract RunLock createLock(Clock clock);

    private RunLock hostA;
    private RunLock hostB;

    private RunLock hostA() {
        if (hostA == null) {
            hostA = createLock(clock);
        }
        return hostA;
    }

    private RunLock hostB() {
        if (hostB == null) {
            hostB = createLock(clock);
        }
        return hostB;
    }

    @Test
    void aCanaryWithNoRecordIsDueNow() {
        assertThat(hostA().nextRunAt("a")).isEqualTo(T0);

        assertThat(hostA().tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(T0);
    }

    @Test
    void aLiveLeaseBlocksEveryHostButOnlyForItsOwnCanary() {
        hostA().tryAcquire("a", LEASE).orElseThrow();

        assertThat(hostB().tryAcquire("a", LEASE)).isEmpty();
        assertThat(hostA().tryAcquire("a", LEASE)).isEmpty();
        assertThat(hostB().tryAcquire("b", LEASE)).isPresent();
    }

    @Test
    void completeMovesTheDueTimeForwardForEveryHost() {
        Instant next = T0.plusSeconds(300);
        hostA().tryAcquire("a", LEASE).orElseThrow().complete(next);

        assertThat(hostB().nextRunAt("a")).isEqualTo(next);
        clock.set(next.minusMillis(1));
        assertThat(hostB().tryAcquire("a", LEASE)).isEmpty();

        clock.set(next);
        assertThat(hostB().tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(next);
    }

    @Test
    void aLateAcquireStillReportsTheScheduledDueTime() {
        Instant next = T0.plusSeconds(300);
        hostA().tryAcquire("a", LEASE).orElseThrow().complete(next);

        clock.set(next.plusSeconds(42));

        assertThat(hostB().tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(next);
    }

    @Test
    void releaseLeavesTheRoundDueForAnyHost() {
        hostA().tryAcquire("a", LEASE).orElseThrow().release();

        assertThat(hostB().tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(T0);
    }

    @Test
    void renewingKeepsTheLeasePastItsFirstExpiry() {
        Lease lease = hostA().tryAcquire("a", LEASE).orElseThrow();

        clock.advance(Duration.ofSeconds(40));
        assertThat(lease.renew()).isTrue();
        clock.advance(Duration.ofSeconds(40));

        assertThat(hostB().tryAcquire("a", LEASE)).isEmpty();
        assertThat(lease.renew()).isTrue();
    }

    @Test
    void anExpiredLeaseCanBeTakenOverAndTheOldHolderCanNoLongerChangeAnything() {
        Lease dead = hostA().tryAcquire("a", LEASE).orElseThrow();

        clock.advance(LEASE);
        assertThat(dead.renew()).isFalse();
        Lease takeover = hostB().tryAcquire("a", LEASE).orElseThrow();
        assertThat(takeover.dueTime()).isEqualTo(T0);

        dead.complete(T0.plusSeconds(999));
        dead.release();

        assertThat(hostB().nextRunAt("a")).isEqualTo(T0);
        assertThat(hostA().tryAcquire("a", LEASE)).isEmpty();
        assertThat(dead.renew()).isFalse();
        assertThat(takeover.renew()).isTrue();
    }

    @Test
    void whenHostsRaceForTheSameRoundExactlyOneWins() throws Exception {
        int attemptsPerHost = 8;
        List<RunLock> locks = List.of(hostA(), hostB());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Lease>>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RunLock lock : locks) {
                for (int i = 0; i < attemptsPerHost; i++) {
                    attempts.add(executor.submit(() -> {
                        start.await();
                        return lock.tryAcquire("a", LEASE);
                    }));
                }
            }
            start.countDown();
        }

        long winners = 0;
        for (Future<Optional<Lease>> attempt : attempts) {
            if (attempt.get().isPresent()) {
                winners++;
            }
        }
        assertThat(winners).isEqualTo(1);
    }
}
