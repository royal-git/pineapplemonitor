package io.github.royalgit.pineapplemonitor.lock;

import java.time.Clock;

/** An in-memory lock serves a single instance, so both hosts in the contract share one object. */
class InMemoryRunLockTest extends RunLockContract {

    private InMemoryRunLock lock;

    @Override
    protected RunLock createLock(Clock clock) {
        if (lock == null) {
            lock = new InMemoryRunLock(clock);
        }
        return lock;
    }
}
