package dev.canary.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Coordinates who runs a canary and when. Each canary has one record holding its lease and next due
 * time, so another host can take over from a dead one and the schedule survives.
 *
 * <p>Any method may throw if the backend cannot answer. That is a lock error, not a held lease.
 */
public interface RunLock {

    /** Returns a lease if the canary is due and no live lease exists, or empty otherwise. */
    Optional<Lease> tryAcquire(String canaryName, Duration leaseDuration);

    /** Returns the time the canary is next due. A canary with no record yet is due now. */
    Instant nextRunAt(String canaryName);

    interface Lease {

        /** The due time of the round this lease covers. */
        Instant dueTime();

        /** Extends the lease. Returns false if the lease was lost, so the run must stop. */
        boolean renew();

        /** Moves the due time forward and releases the lease. */
        void complete(Instant nextRunAt);

        /** Releases the lease without moving the due time, so the round can be rerun. */
        void release();
    }
}
