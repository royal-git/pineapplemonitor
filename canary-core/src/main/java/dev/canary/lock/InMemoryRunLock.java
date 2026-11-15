package dev.canary.lock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The default lock for a single instance. It keeps records in memory, so it never blocks anyone
 * else, and after a restart every canary is due at once.
 */
public final class InMemoryRunLock implements RunLock {

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public InMemoryRunLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Optional<Lease> tryAcquire(String canaryName, Duration leaseDuration) {
        Instant now = clock.instant();
        Entry entry = entries.computeIfAbsent(canaryName, name -> new Entry(now));
        if (entry.nextRunAt.isAfter(now)) {
            return Optional.empty();
        }
        if (entry.owner != null && entry.leaseExpiresAt.isAfter(now)) {
            return Optional.empty();
        }
        InMemoryLease lease = new InMemoryLease(entry, entry.nextRunAt, leaseDuration);
        entry.owner = lease;
        entry.leaseExpiresAt = now.plus(leaseDuration);
        return Optional.of(lease);
    }

    @Override
    public synchronized Instant nextRunAt(String canaryName) {
        Entry entry = entries.get(canaryName);
        return entry == null ? clock.instant() : entry.nextRunAt;
    }

    private static final class Entry {

        private Instant nextRunAt;
        private InMemoryLease owner;
        private Instant leaseExpiresAt;

        Entry(Instant nextRunAt) {
            this.nextRunAt = nextRunAt;
        }
    }

    private final class InMemoryLease implements Lease {

        private final Entry entry;
        private final Instant dueTime;
        private final Duration leaseDuration;

        InMemoryLease(Entry entry, Instant dueTime, Duration leaseDuration) {
            this.entry = entry;
            this.dueTime = dueTime;
            this.leaseDuration = leaseDuration;
        }

        @Override
        public Instant dueTime() {
            return dueTime;
        }

        @Override
        public boolean renew() {
            synchronized (InMemoryRunLock.this) {
                Instant now = clock.instant();
                if (entry.owner != this || !entry.leaseExpiresAt.isAfter(now)) {
                    return false;
                }
                entry.leaseExpiresAt = now.plus(leaseDuration);
                return true;
            }
        }

        @Override
        public void complete(Instant nextRunAt) {
            synchronized (InMemoryRunLock.this) {
                if (entry.owner == this) {
                    entry.nextRunAt = nextRunAt;
                    entry.owner = null;
                }
            }
        }

        @Override
        public void release() {
            synchronized (InMemoryRunLock.this) {
                if (entry.owner == this) {
                    entry.owner = null;
                }
            }
        }
    }
}
