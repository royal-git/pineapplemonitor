package io.github.royalgit.pineapplemonitor.run;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.royalgit.pineapplemonitor.api.RunContext;

/** One annotated method of a canary class. */
public record CanaryMethod(Method method, Phase phase) {

    public String name() {
        return method.getName();
    }

    void invoke(Object instance, RunContext context) throws Throwable {
        try {
            if (method.getParameterCount() == 0) {
                method.invoke(instance);
            } else {
                method.invoke(instance, context);
            }
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
