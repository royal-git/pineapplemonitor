package dev.canary.orchestrator;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import dev.canary.account.AccountProvider;
import dev.canary.config.CanaryConfig;
import dev.canary.config.ConfigStore;
import dev.canary.lock.RunLock;
import dev.canary.metrics.MetricsSink;
import dev.canary.metrics.SafeMetricsSink;
import dev.canary.run.CanaryDefinition;
import dev.canary.run.StepRunner;

/**
 * Owns one thread per canary, the settings reload, the heartbeat and graceful shutdown. It starts
 * after the web server and stops before it.
 */
public final class CanaryOrchestrator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CanaryOrchestrator.class);

    private final ConfigStore configStore;
    private final AccountProvider accounts;
    private final MetricsSink metrics;
    private final CanaryProperties properties;
    private final List<CanaryWorker> workers;

    private final List<Thread> workerThreads = new ArrayList<>();
    private final List<Thread> backgroundThreads = new ArrayList<>();
    private volatile boolean running;

    public CanaryOrchestrator(CanaryCatalog catalog, ConfigStore configStore, RunLock lock, AccountProvider accounts,
            MetricsSink metrics, CanaryProperties properties, Clock clock) {
        this.configStore = configStore;
        this.accounts = accounts;
        this.metrics = new SafeMetricsSink(metrics);
        this.properties = properties;

        StepRunner runner = new StepRunner(clock, properties.interruptGrace());
        CanaryWorker.Settings settings = new CanaryWorker.Settings(properties.pollInterval(), properties.leaseDuration(),
                properties.renewInterval());
        List<CanaryWorker> created = new ArrayList<>();
        for (CanaryDefinition definition : catalog.definitions()) {
            created.add(new CanaryWorker(definition, catalog, lock, runner, accounts, this.metrics, settings, clock));
        }
        this.workers = List.copyOf(created);
    }

    @Override
    public synchronized void start() {
        // Unlike a reload, a bad settings file stops startup, so the mistake surfaces at deploy.
        Map<String, CanaryConfig> configs = loadConfig();
        warnAboutRemovedCanaries(configs);
        workers.forEach(worker -> worker.updateConfig(configs.get(worker.canaryName())));
        for (CanaryWorker worker : workers) {
            workerThreads.add(Thread.ofVirtual().name("canary-" + worker.canaryName()).start(worker::runLoop));
        }
        backgroundThreads.add(startEvery("canary-config-reload", properties.configReloadInterval(), this::reloadConfig));
        backgroundThreads.add(startEvery("canary-heartbeat", properties.heartbeatInterval(), metrics::recordHeartbeat));
        running = true;
        log.info("Started {} canaries", workers.size());
    }

    /** Lets in-flight runs finish within the grace period, then interrupts them. Their cleanup still runs. */
    @Override
    public synchronized void stop() {
        running = false;
        backgroundThreads.forEach(Thread::interrupt);
        workers.forEach(CanaryWorker::stop);
        try {
            long deadline = System.nanoTime() + properties.shutdownGrace().toNanos();
            for (Thread thread : workerThreads) {
                thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            }
            workerThreads.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
            for (Thread thread : workerThreads) {
                thread.join(properties.shutdownGrace());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workerThreads.clear();
        backgroundThreads.clear();
        log.info("Stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Applies the latest settings. If the store cannot be read, the last good settings stay in use. */
    void reloadConfig() {
        Map<String, CanaryConfig> configs;
        try {
            configs = loadConfig();
        } catch (RuntimeException e) {
            log.warn("Could not load canary settings, so the last good settings stay in use", e);
            return;
        }
        workers.forEach(worker -> worker.updateConfig(configs.get(worker.canaryName())));
    }

    private Map<String, CanaryConfig> loadConfig() {
        List<CanaryConfig> loaded = configStore.loadAll();
        loaded.forEach(config -> accounts.validate(config.account()));
        return loaded.stream().collect(Collectors.toMap(CanaryConfig::canary, Function.identity()));
    }

    private void warnAboutRemovedCanaries(Map<String, CanaryConfig> configs) {
        Set<String> known = workers.stream().map(CanaryWorker::canaryName).collect(Collectors.toSet());
        configs.keySet().stream().filter(name -> !known.contains(name))
                .forEach(name -> log.warn("Ignoring settings for {}, because no canary has that name", name));
    }

    private static Thread startEvery(String name, Duration interval, Runnable task) {
        return Thread.ofVirtual().name(name).start(() -> {
            try {
                while (true) {
                    Thread.sleep(interval);
                    task.run();
                }
            } catch (InterruptedException e) {
                // Shutting down.
            }
        });
    }
}
