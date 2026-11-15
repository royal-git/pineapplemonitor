package dev.canary.api;

import java.time.Instant;

/** What a canary method can see about the current execution. */
public interface RunContext {

    String runId();

    Instant dueTime();

    /** The index of this execution among parallel executions, starting at 0. */
    int execution();

    Account account();

    CanaryMetrics metrics();
}
