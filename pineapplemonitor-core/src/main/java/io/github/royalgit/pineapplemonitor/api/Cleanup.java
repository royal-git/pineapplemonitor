package io.github.royalgit.pineapplemonitor.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs last, even if {@link Before} failed. A process crash can skip it, so a canary must work from
 * whatever state the last run left behind, and baseline values must come from code rather than
 * being read back from the account.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cleanup {
}
