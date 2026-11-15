package dev.canary.account;

import java.util.Map;

/** Credential values read from environment variables. They never appear in {@link #toString()}. */
public final class EnvCredentials {

    private final Map<String, String> values;

    EnvCredentials(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public String get(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("No credential value named " + key);
        }
        return value;
    }

    @Override
    public String toString() {
        return "EnvCredentials[keys=" + values.keySet() + "]";
    }
}
