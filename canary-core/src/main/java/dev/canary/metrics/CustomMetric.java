package dev.canary.metrics;

import java.util.Map;

/** A metric emitted by a canary through {@code ctx.metrics()}. */
public record CustomMetric(Kind kind, String name, double value, String unit, String canary, String step,
        Map<String, String> attributes) {

    public enum Kind {
        COUNTER,
        HISTOGRAM
    }

    public CustomMetric {
        attributes = Map.copyOf(attributes);
    }
}
