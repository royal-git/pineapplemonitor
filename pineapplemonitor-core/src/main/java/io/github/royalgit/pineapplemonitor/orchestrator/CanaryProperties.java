package io.github.royalgit.pineapplemonitor.orchestrator;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings for the service itself. Settings for each canary live in the file at {@code configFile}. */
@ConfigurationProperties("canary")
public record CanaryProperties(
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue("20s") Duration renewInterval,
        @DefaultValue("30s") Duration configReloadInterval,
        @DefaultValue("30s") Duration heartbeatInterval,
        @DefaultValue("20s") Duration shutdownGrace,
        @DefaultValue("1s") Duration interruptGrace,
        @DefaultValue("canary.yml") Path configFile) {
}
