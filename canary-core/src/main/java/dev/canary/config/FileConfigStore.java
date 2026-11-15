package dev.canary.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Reads settings from a YAML file that lives apart from the canary code:
 *
 * <pre>
 * canaries:
 *   edit-account:
 *     enabled: true
 *     account: edit-account-test
 *     interval: 5m
 *     parallelism: 1
 *     step-timeout: 10s
 * </pre>
 *
 * <p>{@code enabled} defaults to false and {@code parallelism} to 1. The other settings are required.
 * Any mistake in the file throws, reporting every problem found.
 */
public final class FileConfigStore implements ConfigStore {

    private static final int MAX_PARALLELISM = 100;
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final Path file;

    public FileConfigStore(Path file) {
        this.file = file;
    }

    /** Not private, because Spring cannot bind through a private nested constructor. */
    record Entry(@DefaultValue("false") boolean enabled, String account, Duration interval,
            @DefaultValue("1") int parallelism, Duration stepTimeout) {
    }

    @Override
    public List<CanaryConfig> loadAll() {
        Map<String, Entry> entries = read();
        List<String> problems = new ArrayList<>();
        List<CanaryConfig> configs = new ArrayList<>();
        entries.forEach((name, entry) -> {
            check(name, entry, problems);
            configs.add(new CanaryConfig(name, entry.enabled(), entry.account(), entry.interval(), entry.parallelism(), entry.stepTimeout()));
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid canary settings in " + file + ": " + String.join("; ", problems));
        }
        return configs;
    }

    private Map<String, Entry> read() {
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load("canary-settings", new FileSystemResource(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read canary settings from " + file, e);
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("Canary settings file " + file + " is empty");
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        BindHandler rejectUnknownSettings = new NoUnboundElementsBindHandler(BindHandler.DEFAULT);
        try {
            return binder.bind("canaries", Bindable.mapOf(String.class, Entry.class), rejectUnknownSettings)
                    .orElseThrow(() -> new IllegalStateException("Canary settings file " + file + " has no canaries section"));
        } catch (BindException e) {
            throw new IllegalStateException("Invalid canary settings in " + file + ": " + e.getName() + ": " + rootMessage(e), e);
        }
    }

    private static void check(String name, Entry entry, List<String> problems) {
        if (entry.account() == null || entry.account().isBlank()) {
            problems.add(name + " needs an account");
        }
        if (entry.interval() == null) {
            problems.add(name + " needs an interval");
        } else if (entry.interval().compareTo(MIN_INTERVAL) < 0) {
            problems.add(name + " has an interval below 1s");
        }
        if (entry.stepTimeout() == null) {
            problems.add(name + " needs a step-timeout");
        } else if (entry.stepTimeout().isZero() || entry.stepTimeout().isNegative()) {
            problems.add(name + " needs a positive step-timeout");
        }
        if (entry.parallelism() < 1 || entry.parallelism() > MAX_PARALLELISM) {
            problems.add(name + " needs a parallelism from 1 to " + MAX_PARALLELISM);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
