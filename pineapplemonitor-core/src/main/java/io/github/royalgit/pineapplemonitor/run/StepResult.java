package io.github.royalgit.pineapplemonitor.run;

import java.time.Duration;

/** The result of one annotated method. {@code failure} is null if the method passed. */
public record StepResult(String step, Phase phase, Outcome outcome, Duration duration, String failure) {
}
