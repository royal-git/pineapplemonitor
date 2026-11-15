package io.github.royalgit.pineapplemonitor.account;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.royalgit.pineapplemonitor.api.Account;

/**
 * The default provider, for local use and the example API. Reference {@code edit-account-test} reads
 * every variable starting {@code CANARY_ACCOUNT_EDIT_ACCOUNT_TEST_}, so
 * {@code CANARY_ACCOUNT_EDIT_ACCOUNT_TEST_API_KEY} becomes the value named {@code API_KEY}.
 */
public final class EnvAccountProvider implements AccountProvider {

    private static final String PREFIX = "CANARY_ACCOUNT_";

    private final Map<String, String> environment;

    public EnvAccountProvider() {
        this(System.getenv());
    }

    public EnvAccountProvider(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Account resolve(String reference, AccountRequest request) {
        String prefix = PREFIX + reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_";
        Map<String, String> values = new HashMap<>();
        environment.forEach((name, value) -> {
            if (name.startsWith(prefix)) {
                values.put(name.substring(prefix.length()), value);
            }
        });
        if (values.isEmpty()) {
            throw new IllegalStateException("No environment variables start with " + prefix);
        }
        return new Account(reference, new EnvCredentials(values));
    }
}
