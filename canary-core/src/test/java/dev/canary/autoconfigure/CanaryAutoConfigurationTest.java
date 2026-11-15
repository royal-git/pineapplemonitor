package dev.canary.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.canary.RecordingMetricsSink;
import dev.canary.account.AccountProvider;
import dev.canary.account.EnvAccountProvider;
import dev.canary.api.Account;
import dev.canary.api.Canary;
import dev.canary.api.Step;
import dev.canary.config.ConfigStore;
import dev.canary.config.FileConfigStore;
import dev.canary.lock.InMemoryRunLock;
import dev.canary.lock.RunLock;
import dev.canary.metrics.ConsoleMetricsSink;
import dev.canary.metrics.MetricsSink;
import dev.canary.metrics.OtelMetricsSink;
import dev.canary.orchestrator.CanaryCatalog;
import dev.canary.orchestrator.CanaryOrchestrator;

class CanaryAutoConfigurationTest {

    @TempDir
    Path directory;

    @BeforeEach
    void writeSettings() throws IOException {
        Files.writeString(directory.resolve("canary.yml"), """
                canaries:
                  valid:
                    account: test
                    interval: 5m
                    step-timeout: 10s
                """);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CanaryAutoConfiguration.class))
                .withPropertyValues("canary.config-file=" + directory.resolve("canary.yml"));
    }

    @Test
    void providesEveryDefault() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CanaryOrchestrator.class);
            assertThat(context.getBean(RunLock.class)).isInstanceOf(InMemoryRunLock.class);
            assertThat(context.getBean(ConfigStore.class)).isInstanceOf(FileConfigStore.class);
            assertThat(context.getBean(AccountProvider.class)).isInstanceOf(EnvAccountProvider.class);
            assertThat(context.getBean(MetricsSink.class)).isInstanceOf(ConsoleMetricsSink.class);
            assertThat(context.getBean(Clock.class)).isNotNull();
        });
    }

    @Test
    void usesOpenTelemetryWhenAnEndpointIsSet() {
        runner().withPropertyValues("otel.exporter.otlp.endpoint=http://127.0.0.1:4318").run(context ->
                assertThat(context.getBean(MetricsSink.class)).isInstanceOf(OtelMetricsSink.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Replacements {

        static final RunLock LOCK = new InMemoryRunLock(Clock.systemUTC());

        @Bean
        RunLock customLock() {
            return LOCK;
        }

        @Bean
        ConfigStore customStore() {
            return List::of;
        }

        @Bean
        AccountProvider customAccounts() {
            return (reference, request) -> new Account(reference, "credentials");
        }

        @Bean
        MetricsSink customMetrics() {
            return new RecordingMetricsSink();
        }
    }

    @Test
    void defaultsBackOffForYourOwnBeans() {
        runner().withUserConfiguration(Replacements.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RunLock.class);
            assertThat(context.getBean(RunLock.class)).isSameAs(Replacements.LOCK);
            assertThat(context).doesNotHaveBean(FileConfigStore.class);
            assertThat(context).doesNotHaveBean(EnvAccountProvider.class);
            assertThat(context.getBean(MetricsSink.class)).isInstanceOf(RecordingMetricsSink.class);
        });
    }

    @Canary("valid")
    static class ValidCanary {
        @Step(1) void step() {}
    }

    @Test
    void findsCanaryClassesAndCreatesANewInstanceEachTime() {
        runner().withUserConfiguration(ValidCanary.class).run(context -> {
            CanaryCatalog catalog = context.getBean(CanaryCatalog.class);

            assertThat(catalog.definitions()).singleElement().satisfies(definition -> {
                assertThat(definition.name()).isEqualTo("valid");
                assertThat(catalog.newInstance(definition)).isNotSameAs(catalog.newInstance(definition));
            });
        });
    }

    @Test
    void invalidSettingsFileStopsStartup() throws IOException {
        Files.writeString(directory.resolve("canary.yml"), "canaries:\n  valid:\n    interval: soon\n");

        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("soon");
        });
    }

    @Test
    void missingSettingsFileStopsStartup() throws IOException {
        Files.delete(directory.resolve("canary.yml"));

        runner().run(context -> assertThat(context).hasFailed());
    }

    @Canary("invalid")
    static class InvalidCanary {
        @Step(1) int returnsValue() { return 1; }
    }

    @Test
    void invalidCanaryStopsStartup() {
        runner().withUserConfiguration(InvalidCanary.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("returnsValue must return void");
        });
    }

    @Canary("same")
    static class FirstWithSameName {
        @Step(1) void step() {}
    }

    @Canary("same")
    static class SecondWithSameName {
        @Step(1) void step() {}
    }

    @Test
    void duplicateCanaryNamesStopStartup() {
        runner().withBean("first", FirstWithSameName.class).withBean("second", SecondWithSameName.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("More than one canary is named same");
        });
    }
}
