package dev.canary.run;

public enum Outcome {
    PASSED,
    FAILED,
    TIMED_OUT,
    /** The service shut down mid-method, or the run lost its lease. Alarms should ignore it. */
    ABORTED,
    /** An earlier method did not pass. Applies to methods only, never to a whole run. */
    NOT_RUN
}
