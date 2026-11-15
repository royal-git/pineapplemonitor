package io.github.royalgit.pineapplemonitor.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class ScheduleTest {

    private static final Instant SLOT = Instant.parse("2026-01-01T00:00:00Z");
    private final Schedule everyFiveMinutes = new Schedule(Duration.ofMinutes(5));

    @Test
    void nextIsAlignedAndStrictlyLater() {
        assertThat(everyFiveMinutes.next(SLOT)).isEqualTo(SLOT.plus(Duration.ofMinutes(5)));
        assertThat(everyFiveMinutes.next(SLOT.plusMillis(1))).isEqualTo(SLOT.plus(Duration.ofMinutes(5)));
        assertThat(everyFiveMinutes.next(SLOT.minusMillis(1))).isEqualTo(SLOT);
    }

    @Test
    void roundThatFinishesInTimeIsDueAtTheNextSlot() {
        Instant startedAt = SLOT.plusSeconds(2);

        assertThat(everyFiveMinutes.nextDue(startedAt, startedAt.plusSeconds(30))).isEqualTo(SLOT.plus(Duration.ofMinutes(5)));
    }

    @Test
    void lateStartDoesNotLeaveAnotherRoundDue() {
        Instant startedAt = SLOT.plus(Duration.ofHours(3)).plusSeconds(1);

        assertThat(everyFiveMinutes.nextDue(startedAt, startedAt.plusSeconds(1))).isEqualTo(startedAt.minusSeconds(1).plus(Duration.ofMinutes(5)));
    }

    @Test
    void roundThatOverranIsDueOnceAtTheLatestSlot() {
        Instant finished = SLOT.plus(Duration.ofMinutes(12));

        assertThat(everyFiveMinutes.nextDue(SLOT, finished)).isEqualTo(SLOT.plus(Duration.ofMinutes(10)));
    }

    @Test
    void intervalMustBePositive() {
        assertThatThrownBy(() -> new Schedule(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
