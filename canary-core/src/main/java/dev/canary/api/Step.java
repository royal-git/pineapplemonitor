package dev.canary.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A test step. Steps run in ascending order, and the first one that fails ends the test phase. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Step {

    /** The step's position, starting at 1. */
    int value();
}
