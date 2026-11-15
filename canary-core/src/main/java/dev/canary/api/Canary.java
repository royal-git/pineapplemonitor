package dev.canary.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

/**
 * Marks a class as a canary. It is a prototype-scoped bean, so each execution gets a new instance
 * and fields can pass values between methods.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public @interface Canary {

    /** The canary's name, which links it to metrics, its settings and lock keys. */
    @AliasFor(annotation = Component.class, attribute = "value")
    String value();
}
