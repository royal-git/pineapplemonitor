package dev.canary.run;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.util.ReflectionUtils;

import dev.canary.api.After;
import dev.canary.api.Before;
import dev.canary.api.Canary;
import dev.canary.api.Cleanup;
import dev.canary.api.RunContext;
import dev.canary.api.Step;

/** A canary class that has passed validation. {@code before}, {@code after} and {@code cleanup} may be null. */
public record CanaryDefinition(String name, Class<?> type, CanaryMethod before, List<CanaryMethod> steps,
        CanaryMethod after, CanaryMethod cleanup) {

    public CanaryDefinition {
        steps = List.copyOf(steps);
    }

    /** Reads and validates a canary class, reporting every problem at once. */
    public static CanaryDefinition of(Class<?> type) {
        List<String> problems = new ArrayList<>();

        Canary canary = type.getAnnotation(Canary.class);
        String name = canary == null ? null : canary.value();
        if (canary == null) {
            problems.add("it is not annotated with @Canary");
        } else if (name.isBlank()) {
            problems.add("its @Canary name is blank");
        }

        List<Method> befores = new ArrayList<>();
        List<Method> afters = new ArrayList<>();
        List<Method> cleanups = new ArrayList<>();
        List<Method> steps = new ArrayList<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            boolean isBefore = method.isAnnotationPresent(Before.class);
            boolean isAfter = method.isAnnotationPresent(After.class);
            boolean isCleanup = method.isAnnotationPresent(Cleanup.class);
            boolean isStep = method.isAnnotationPresent(Step.class);
            int annotations = (isBefore ? 1 : 0) + (isAfter ? 1 : 0) + (isCleanup ? 1 : 0) + (isStep ? 1 : 0);
            if (annotations == 0) {
                continue;
            }
            if (annotations > 1) {
                problems.add(method.getName() + " has more than one canary annotation");
            }
            checkSignature(method, problems);
            if (isBefore) befores.add(method);
            if (isAfter) afters.add(method);
            if (isCleanup) cleanups.add(method);
            if (isStep) steps.add(method);
        }

        checkAtMostOne(befores, Before.class, problems);
        checkAtMostOne(afters, After.class, problems);
        checkAtMostOne(cleanups, Cleanup.class, problems);
        checkSteps(steps, problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid canary class " + type.getName() + ": " + String.join("; ", problems));
        }

        steps.sort(Comparator.comparingInt(method -> method.getAnnotation(Step.class).value()));
        return new CanaryDefinition(name, type, single(befores, Phase.BEFORE),
                steps.stream().map(method -> method(method, Phase.STEP)).toList(),
                single(afters, Phase.AFTER), single(cleanups, Phase.CLEANUP));
    }

    private static void checkSignature(Method method, List<String> problems) {
        if (Modifier.isStatic(method.getModifiers())) {
            problems.add(method.getName() + " must not be static");
        }
        if (method.getReturnType() != void.class) {
            problems.add(method.getName() + " must return void");
        }
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length > 1 || (parameters.length == 1 && parameters[0] != RunContext.class)) {
            problems.add(method.getName() + " must take no arguments or a RunContext");
        }
    }

    private static void checkAtMostOne(List<Method> methods, Class<? extends Annotation> annotation, List<String> problems) {
        if (methods.size() > 1) {
            problems.add("it has more than one @" + annotation.getSimpleName());
        }
    }

    private static void checkSteps(List<Method> steps, List<String> problems) {
        if (steps.isEmpty()) {
            problems.add("it has no @Step");
        }
        Set<Integer> seen = new HashSet<>();
        for (Method step : steps) {
            int order = step.getAnnotation(Step.class).value();
            if (order < 1) {
                problems.add(step.getName() + " has step number " + order + ", but numbers start at 1");
            }
            if (!seen.add(order)) {
                problems.add("step number " + order + " is used more than once");
            }
        }
    }

    private static CanaryMethod single(List<Method> methods, Phase phase) {
        return methods.isEmpty() ? null : method(methods.get(0), phase);
    }

    private static CanaryMethod method(Method method, Phase phase) {
        method.setAccessible(true);
        return new CanaryMethod(method, phase);
    }
}
