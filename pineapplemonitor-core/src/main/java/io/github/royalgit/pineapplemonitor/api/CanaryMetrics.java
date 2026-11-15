package io.github.royalgit.pineapplemonitor.api;

import java.util.Map;

/**
 * Custom metrics, published as {@code canary.custom.<name>} with {@code canary} and {@code step}
 * attributes added automatically. Attribute values should have few possible values, so no account
 * or run IDs. A failed call is logged and never fails the step.
 */
public interface CanaryMetrics {

    default void count(String name, long value) {
        count(name, value, Map.of());
    }

    void count(String name, long value, Map<String, String> attributes);

    default void record(String name, double value, String unit) {
        record(name, value, unit, Map.of());
    }

    void record(String name, double value, String unit, Map<String, String> attributes);
}
