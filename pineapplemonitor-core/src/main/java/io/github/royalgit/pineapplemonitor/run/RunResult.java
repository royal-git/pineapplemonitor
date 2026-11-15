package io.github.royalgit.pineapplemonitor.run;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The result of one execution, with a {@link StepResult} for every annotated method. */
public record RunResult(String canary, String runId, int execution, Instant dueTime, Instant startedAt,
        Duration duration, Outcome outcome, List<StepResult> steps) {

    public RunResult {
        steps = List.copyOf(steps);
    }
}
