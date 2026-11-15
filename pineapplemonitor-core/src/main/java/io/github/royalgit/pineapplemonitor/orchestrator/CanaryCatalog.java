package io.github.royalgit.pineapplemonitor.orchestrator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;

import io.github.royalgit.pineapplemonitor.api.Canary;
import io.github.royalgit.pineapplemonitor.run.CanaryDefinition;

/** The validated canary classes in this application, and a way to create an instance of each. */
public final class CanaryCatalog {

    private final List<CanaryDefinition> definitions;
    private final Function<CanaryDefinition, Object> instances;

    public CanaryCatalog(List<CanaryDefinition> definitions, Function<CanaryDefinition, Object> instances) {
        List<CanaryDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparing(CanaryDefinition::name));
        Set<String> names = new HashSet<>();
        for (CanaryDefinition definition : sorted) {
            if (!names.add(definition.name())) {
                throw new IllegalStateException("More than one canary is named " + definition.name());
            }
        }
        this.definitions = List.copyOf(sorted);
        this.instances = instances;
    }

    /** Finds and validates every {@link Canary} bean. Invalid classes stop startup. */
    public static CanaryCatalog fromBeans(ListableBeanFactory beans) {
        List<CanaryDefinition> definitions = new ArrayList<>();
        for (String beanName : beans.getBeanNamesForAnnotation(Canary.class)) {
            definitions.add(CanaryDefinition.of(ClassUtils.getUserClass(beans.getType(beanName))));
        }
        return new CanaryCatalog(definitions, definition -> beans.getBean(definition.type()));
    }

    public List<CanaryDefinition> definitions() {
        return definitions;
    }

    public Object newInstance(CanaryDefinition definition) {
        return instances.apply(definition);
    }
}
