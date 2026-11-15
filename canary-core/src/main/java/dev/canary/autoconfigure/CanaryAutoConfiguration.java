package dev.canary.autoconfigure;

import java.time.Clock;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import dev.canary.account.AccountProvider;
import dev.canary.account.EnvAccountProvider;
import dev.canary.config.ConfigStore;
import dev.canary.config.FileConfigStore;
import dev.canary.lock.InMemoryRunLock;
import dev.canary.lock.RunLock;
import dev.canary.metrics.ConsoleMetricsSink;
import dev.canary.metrics.MetricsSink;
import dev.canary.metrics.OtelMetricsSink;
import dev.canary.orchestrator.CanaryCatalog;
import dev.canary.orchestrator.CanaryOrchestrator;
import dev.canary.orchestrator.CanaryProperties;

/** The defaults for each pluggable part. Defining your own bean replaces the default. */
@AutoConfiguration
@EnableConfigurationProperties(CanaryProperties.class)
public class CanaryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock canaryClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    RunLock runLock(Clock clock) {
        return new InMemoryRunLock(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ConfigStore configStore(CanaryProperties properties) {
        return new FileConfigStore(properties.configFile());
    }

    @Bean
    @ConditionalOnMissingBean
    AccountProvider accountProvider() {
        return new EnvAccountProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    MetricsSink metricsSink(Environment environment) {
        // Spring also finds this as the OTEL_EXPORTER_OTLP_ENDPOINT variable.
        String endpoint = environment.getProperty("otel.exporter.otlp.endpoint");
        if (endpoint == null) {
            return new ConsoleMetricsSink();
        }
        return OtelMetricsSink.forEndpoint(endpoint, environment.getProperty("spring.application.name", "canary"));
    }

    @Bean
    CanaryCatalog canaryCatalog(ListableBeanFactory beans) {
        return CanaryCatalog.fromBeans(beans);
    }

    @Bean
    CanaryOrchestrator canaryOrchestrator(CanaryCatalog catalog, ConfigStore configStore, RunLock lock, AccountProvider accounts,
            MetricsSink metrics, CanaryProperties properties, Clock clock) {
        return new CanaryOrchestrator(catalog, configStore, lock, accounts, metrics, properties, clock);
    }
}
