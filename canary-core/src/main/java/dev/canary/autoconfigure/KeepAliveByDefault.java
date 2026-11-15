package dev.canary.autoconfigure;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Canaries run on virtual threads, which do not keep the JVM alive, and the service has no web server
 * to do it. Without this, the process would exit as soon as it had started.
 */
public final class KeepAliveByDefault implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("canaryDefaults", Map.of("spring.main.keep-alive", "true")));
    }
}
