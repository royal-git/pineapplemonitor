package dev.canary.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account an execution runs as: the reference in its settings and the credentials
 * an {@code AccountProvider} resolved for it. Credentials never appear in {@link #toString()}.
 */
public final class Account implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Account.class);

    private final String reference;
    private final Object credentials;

    public Account(String reference, Object credentials) {
        this.reference = Objects.requireNonNull(reference);
        this.credentials = Objects.requireNonNull(credentials);
    }

    public String reference() {
        return reference;
    }

    /** Returns the credentials as the type this account's provider supplies. */
    public <T> T credentials(Class<T> type) {
        if (!type.isInstance(credentials)) {
            throw new IllegalStateException("Account " + reference + " does not have credentials of type "
                    + type.getName() + ", it has " + credentials.getClass().getName());
        }
        return type.cast(credentials);
    }

    /** Releases the credentials if they hold resources. */
    @Override
    public void close() {
        if (credentials instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Could not close credentials for account {}", reference, e);
            }
        }
    }

    @Override
    public String toString() {
        return "Account[" + reference + "]";
    }
}
