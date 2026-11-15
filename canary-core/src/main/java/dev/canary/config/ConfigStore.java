package dev.canary.config;

import java.util.List;

/** Where canary settings live. Throws if they cannot be read, rather than returning none. */
public interface ConfigStore {

    List<CanaryConfig> loadAll();
}
