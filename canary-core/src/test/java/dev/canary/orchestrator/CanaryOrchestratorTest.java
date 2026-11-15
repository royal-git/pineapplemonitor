package dev.canary.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import dev.canary.RecordingMetricsSink;
import dev.canary.account.AccountProvider;
import dev.canary.account.AccountRequest;
import dev.canary.api.Account;
import dev.canary.api.Canary;
import dev.canary.api.Cleanup;
import dev.canary.api.Step;
import dev.canary.config.CanaryConfig;
import dev.canary.config.ConfigStore;
import dev.canary.lock.InMemoryRunLock;
import dev.canary.run.CanaryDefinition;
import dev.canary.run.Outcome;

class CanaryOrchestratorTest {

    private static volatile Duration stepLength = Duration.ZERO;
    private static volatile CountDownLatch stepStarted = new CountDownLatch(1);
    private static volatile CountDownLatch cleanedUp = new CountDownLatch(1);

    @Canary("sleeper")
    static class Sleeper {
        @Step(1) void step() throws InterruptedException {
            stepStarted.countDown();
            Thread.sleep(stepLength);
        }
        @Cleanup void cleanUp() {
            cleanedUp.countDown();
        }
    }

    @Canary("failing")
    static class Failing {
        @Step(1) void step() {
            throw new IllegalStateException("always fails");
        }
    }

    private static final class FakeStore implements ConfigStore {

        final Map<String, CanaryConfig> configs = new ConcurrentHashMap<>();
        volatile boolean failing;

        @Override
        public List<CanaryConfig> loadAll() {
            if (failing) {
                throw new IllegalStateException("store unavailable");
            }
            return List.copyOf(configs.values());
        }

        void save(CanaryConfig config) {
            configs.put(config.canary(), config);
        }
    }

    private final FakeStore store = new FakeStore();
    private final RecordingMetricsSink metrics = new RecordingMetricsSink();
    private CanaryOrchestrator orchestrator;

    @BeforeEach
    void reset() {
        stepLength = Duration.ZERO;
        stepStarted = new CountDownLatch(1);
        cleanedUp = new CountDownLatch(1);
        orchestrator = orchestrator(Duration.ofSeconds(20));
    }

    @AfterEach
    void stop() {
        orchestrator.stop();
    }

    private CanaryOrchestrator orchestrator(Duration shutdownGrace) {
        List<CanaryDefinition> definitions = List.of(CanaryDefinition.of(Sleeper.class), CanaryDefinition.of(Failing.class));
        CanaryCatalog catalog = new CanaryCatalog(definitions, d -> BeanUtils.instantiateClass(d.type()));
        CanaryProperties properties = new CanaryProperties(Duration.ofMillis(20), Duration.ofSeconds(60), Duration.ofSeconds(20),
                Duration.ofMillis(20), Duration.ofMillis(20), shutdownGrace, Duration.ofMillis(100), Path.of("unused"));
        Clock clock = Clock.systemUTC();
        AccountProvider accounts = new AccountProvider() {
            @Override
            public Account resolve(String reference, AccountRequest request) {
                return new Account(reference, "x");
            }

            @Override
            public void validate(String reference) {
                if (reference.equals("not-allowed")) {
                    throw new IllegalArgumentException("Account not allowed");
                }
            }
        };
        return new CanaryOrchestrator(catalog, store, new InMemoryRunLock(clock), accounts, metrics, properties, clock);
    }

    private static CanaryConfig enabled(Duration interval) {
        return new CanaryConfig("sleeper", true, "test-account", interval, 1, Duration.ofMinutes(1));
    }

    @Test
    void savedSettingsAreAppliedWithoutARestart() {
        orchestrator.start();
        assertThat(orchestrator.isRunning()).isTrue();

        store.save(enabled(Duration.ofMinutes(5)));

        await().untilAsserted(() -> assertThat(metrics.runs).isNotEmpty());
        assertThat(metrics.runs.get(0).outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void storeFailureKeepsTheLastGoodSettings() {
        store.save(enabled(Duration.ofMillis(50)));
        orchestrator.start();
        await().untilAsserted(() -> assertThat(metrics.runs).isNotEmpty());

        store.failing = true;
        int before = metrics.runs.size();

        await().untilAsserted(() -> assertThat(metrics.runs.size()).isGreaterThan(before + 2));
    }

    @Test
    void oneFailingCanaryDoesNotAffectAnother() {
        store.save(enabled(Duration.ofMillis(50)));
        store.save(new CanaryConfig("failing", true, "test-account", Duration.ofMillis(50), 1, Duration.ofMinutes(1)));
        orchestrator.start();

        await().untilAsserted(() -> {
            assertThat(metrics.runs).filteredOn(run -> run.canary().equals("failing")).hasSizeGreaterThan(2)
                    .allSatisfy(run -> assertThat(run.outcome()).isEqualTo(Outcome.FAILED));
            assertThat(metrics.runs).filteredOn(run -> run.canary().equals("sleeper")).hasSizeGreaterThan(2)
                    .allSatisfy(run -> assertThat(run.outcome()).isEqualTo(Outcome.PASSED));
        });
    }

    @Test
    void settingsForARemovedCanaryAreIgnored() {
        store.save(new CanaryConfig("removed", true, "test-account", Duration.ofMillis(50), 1, Duration.ofMinutes(1)));
        store.save(enabled(Duration.ofMillis(50)));
        orchestrator.start();

        await().untilAsserted(() -> assertThat(metrics.runs.size()).isGreaterThan(2));

        assertThat(metrics.runs).noneSatisfy(run -> assertThat(run.canary()).isEqualTo("removed"));
    }

    @Test
    void badSettingsStopStartup() {
        store.failing = true;

        assertThatThrownBy(orchestrator::start).hasMessageContaining("store unavailable");
    }

    @Test
    void accountThatIsNotAllowedStopsStartup() {
        store.save(new CanaryConfig("sleeper", true, "not-allowed", Duration.ofMinutes(5), 1, Duration.ofMinutes(1)));

        assertThatThrownBy(orchestrator::start).hasMessageContaining("Account not allowed");
    }

    @Test
    void reloadWithAnAccountThatIsNotAllowedKeepsTheLastGoodSettings() {
        store.save(enabled(Duration.ofMillis(50)));
        orchestrator.start();
        await().untilAsserted(() -> assertThat(metrics.runs).isNotEmpty());

        store.save(new CanaryConfig("sleeper", true, "not-allowed", Duration.ofMillis(50), 1, Duration.ofMinutes(1)));
        int before = metrics.runs.size();

        await().untilAsserted(() -> assertThat(metrics.runs.size()).isGreaterThan(before + 2));
        assertThat(metrics.skips).isEmpty();
    }

    @Test
    void heartbeatsAreRecorded() {
        orchestrator.start();

        await().untilAsserted(() -> assertThat(metrics.heartbeats.get()).isPositive());
    }

    @Test
    void shutdownLetsARunFinishWithinTheGracePeriod() throws Exception {
        stepLength = Duration.ofMillis(300);
        store.save(enabled(Duration.ofMinutes(5)));
        orchestrator.start();
        stepStarted.await();

        orchestrator.stop();

        assertThat(orchestrator.isRunning()).isFalse();
        assertThat(metrics.runs).singleElement().satisfies(run -> assertThat(run.outcome()).isEqualTo(Outcome.PASSED));
    }

    @Test
    void shutdownInterruptsARunPastTheGracePeriodAndCleanupStillRuns() throws Exception {
        orchestrator = orchestrator(Duration.ofMillis(50));
        stepLength = Duration.ofMinutes(5);
        store.save(enabled(Duration.ofMinutes(5)));
        orchestrator.start();
        stepStarted.await();

        orchestrator.stop();

        assertThat(cleanedUp.getCount()).isZero();
        assertThat(metrics.runs).singleElement().satisfies(run -> assertThat(run.outcome()).isEqualTo(Outcome.ABORTED));
    }
}
