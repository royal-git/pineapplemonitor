package dev.canary.account;

/** Which execution needs credentials, so that providers can tie their use back to it. */
public record AccountRequest(String canary, String runId) {
}
