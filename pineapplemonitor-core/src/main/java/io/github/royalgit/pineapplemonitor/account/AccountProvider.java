package io.github.royalgit.pineapplemonitor.account;

import io.github.royalgit.pineapplemonitor.api.Account;

/** Turns the account reference in a canary's settings into credentials at run time. */
public interface AccountProvider {

    /** Returns credentials for this execution, or throws, which skips it with {@code ACCOUNT_ERROR}. */
    Account resolve(String reference, AccountRequest request);

    /** Throws {@link IllegalArgumentException} if settings may not use this reference. */
    default void validate(String reference) {
    }
}
