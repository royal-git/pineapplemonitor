package dev.canary.config;

import java.time.Duration;

/** The settings for one canary, kept apart from its code. */
public record CanaryConfig(String canary, boolean enabled, String account, Duration interval, int parallelism,
        Duration stepTimeout) {
}
