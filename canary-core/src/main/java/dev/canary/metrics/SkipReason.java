package dev.canary.metrics;

public enum SkipReason {
    /** A lock call could not get an answer from the backend. */
    LOCK_ERROR,
    /** Credentials could not be obtained, so the API under test was not exercised. */
    ACCOUNT_ERROR
}
