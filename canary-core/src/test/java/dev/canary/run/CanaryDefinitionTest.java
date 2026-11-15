package dev.canary.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.canary.api.After;
import dev.canary.api.Before;
import dev.canary.api.Canary;
import dev.canary.api.Cleanup;
import dev.canary.api.RunContext;
import dev.canary.api.Step;

class CanaryDefinitionTest {

    @Canary("valid")
    static class Valid {
        @Before void setUp() {}
        @Step(2) void second(RunContext ctx) {}
        @Step(1) void first() {}
        @After void tearDown(RunContext ctx) {}
        @Cleanup void cleanUp() {}
    }

    @Test
    void readsMethodsAndSortsStepsByNumber() {
        CanaryDefinition definition = CanaryDefinition.of(Valid.class);

        assertThat(definition.name()).isEqualTo("valid");
        assertThat(definition.before().name()).isEqualTo("setUp");
        assertThat(definition.steps()).extracting(CanaryMethod::name).containsExactly("first", "second");
        assertThat(definition.after().name()).isEqualTo("tearDown");
        assertThat(definition.cleanup().name()).isEqualTo("cleanUp");
    }

    @Canary("steps-only")
    static class StepsOnly {
        @Step(1) void only() {}
    }

    @Test
    void lifecycleMethodsAreOptional() {
        CanaryDefinition definition = CanaryDefinition.of(StepsOnly.class);

        assertThat(definition.before()).isNull();
        assertThat(definition.after()).isNull();
        assertThat(definition.cleanup()).isNull();
    }

    static class NotAnnotated {
        @Step(1) void step() {}
    }

    @Canary("no-steps")
    static class NoSteps {
        @Before void setUp() {}
    }

    @Canary("bad")
    static class ManyProblems {
        @Before void one() {}
        @Before void two() {}
        @Step(1) int returnsValue() { return 1; }
        @Step(1) void duplicateNumber() {}
        @Step(0) void numberTooLow() {}
        @Step(3) void wrongArgument(String value) {}
        @Step(4) static void isStatic() {}
        @Step(5) @Cleanup void twoAnnotations() {}
    }

    @Test
    void rejectsMissingAnnotation() {
        assertThatThrownBy(() -> CanaryDefinition.of(NotAnnotated.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not annotated with @Canary");
    }

    @Test
    void rejectsClassWithoutSteps() {
        assertThatThrownBy(() -> CanaryDefinition.of(NoSteps.class)).hasMessageContaining("it has no @Step");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> CanaryDefinition.of(ManyProblems.class))
                .hasMessageContaining("more than one @Before")
                .hasMessageContaining("returnsValue must return void")
                .hasMessageContaining("step number 1 is used more than once")
                .hasMessageContaining("numberTooLow has step number 0")
                .hasMessageContaining("wrongArgument must take no arguments or a RunContext")
                .hasMessageContaining("isStatic must not be static")
                .hasMessageContaining("twoAnnotations has more than one canary annotation");
    }
}
