package dev.canary.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.canary.MutableClock;
import dev.canary.lock.RunLock.Lease;

class InMemoryRunLockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryRunLock lock = new InMemoryRunLock(clock);

    @Test
    void newCanaryIsDueNowAndALiveLeaseBlocksOthers() {
        assertThat(lock.nextRunAt("a")).isEqualTo(T0);

        Lease lease = lock.tryAcquire("a", LEASE).orElseThrow();

        assertThat(lease.dueTime()).isEqualTo(T0);
        assertThat(lock.tryAcquire("a", LEASE)).isEmpty();
        assertThat(lock.tryAcquire("b", LEASE)).isPresent();
    }

    @Test
    void completeMovesTheDueTimeForward() {
        Instant next = T0.plusSeconds(300);
        lock.tryAcquire("a", LEASE).orElseThrow().complete(next);

        assertThat(lock.nextRunAt("a")).isEqualTo(next);
        assertThat(lock.tryAcquire("a", LEASE)).isEmpty();

        clock.set(next);
        assertThat(lock.tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(next);
    }

    @Test
    void releaseLeavesTheRoundDue() {
        lock.tryAcquire("a", LEASE).orElseThrow().release();

        assertThat(lock.tryAcquire("a", LEASE).orElseThrow().dueTime()).isEqualTo(T0);
    }

    @Test
    void renewalKeepsTheLeaseAlive() {
        Lease lease = lock.tryAcquire("a", LEASE).orElseThrow();

        clock.advance(Duration.ofSeconds(40));
        assertThat(lease.renew()).isTrue();
        clock.advance(Duration.ofSeconds(40));

        assertThat(lock.tryAcquire("a", LEASE)).isEmpty();
        assertThat(lease.renew()).isTrue();
    }

    @Test
    void expiredLeaseCanBeTakenOverAndTheOldOwnerHasNoEffect() {
        Lease dead = lock.tryAcquire("a", LEASE).orElseThrow();

        clock.advance(LEASE);
        assertThat(dead.renew()).isFalse();
        Lease takeover = lock.tryAcquire("a", LEASE).orElseThrow();
        assertThat(takeover.dueTime()).isEqualTo(T0);

        dead.complete(T0.plusSeconds(999));
        dead.release();

        assertThat(lock.nextRunAt("a")).isEqualTo(T0);
        assertThat(lock.tryAcquire("a", LEASE)).isEmpty();
        assertThat(dead.renew()).isFalse();
        assertThat(takeover.renew()).isTrue();
    }
}
