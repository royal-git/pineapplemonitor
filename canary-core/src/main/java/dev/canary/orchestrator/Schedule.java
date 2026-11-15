package dev.canary.orchestrator;

import java.time.Duration;
import java.time.Instant;

/** A fixed interval aligned to the epoch, so the schedule does not drift with run length. */
public record Schedule(Duration interval) {

    public Schedule {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("The interval must be positive: " + interval);
        }
    }

    /** Returns the first aligned time strictly after the given time. */
    public Instant next(Instant after) {
        long intervalMillis = interval.toMillis();
        long slot = Math.floorDiv(after.toEpochMilli(), intervalMillis) + 1;
        return Instant.ofEpochMilli(slot * intervalMillis);
    }

    /**
     * Returns when a canary is next due after finishing a round that started at {@code startedAt}.
     * If rounds came due while it ran, this is the latest of them, so the canary runs straight away,
     * once, and rounds cannot queue up.
     */
    public Instant nextDue(Instant startedAt, Instant now) {
        Instant next = next(startedAt);
        if (next.isAfter(now)) {
            return next;
        }
        long intervalMillis = interval.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), intervalMillis) * intervalMillis);
    }
}
