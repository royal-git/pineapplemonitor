package dev.canary.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileConfigStoreTest {

    @TempDir
    Path directory;

    private FileConfigStore storeWith(String yaml) throws IOException {
        Path file = directory.resolve("canary.yml");
        Files.writeString(file, yaml);
        return new FileConfigStore(file);
    }

    @Test
    void readsEverySetting() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  edit-account:
                    enabled: true
                    account: arn:aws:iam::123456789012:role/canary-test-edit-account
                    interval: 5m
                    parallelism: 3
                    step-timeout: 1500ms
                """);

        assertThat(store.loadAll()).containsExactly(new CanaryConfig("edit-account", true,
                "arn:aws:iam::123456789012:role/canary-test-edit-account", Duration.ofMinutes(5), 3, Duration.ofMillis(1500)));
    }

    @Test
    void canariesStartOffAndParallelismDefaultsToOne() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  edit-account:
                    account: test
                    interval: 5m
                    step-timeout: 10s
                """);

        assertThat(store.loadAll()).containsExactly(new CanaryConfig("edit-account", false, "test", Duration.ofMinutes(5), 1, Duration.ofSeconds(10)));
    }

    @Test
    void readsSeveralCanaries() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  first:
                    account: a
                    interval: 1m
                    step-timeout: 1s
                  second:
                    account: b
                    interval: 2m
                    step-timeout: 2s
                """);

        assertThat(store.loadAll()).extracting(CanaryConfig::canary).containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void seesChangesToTheFile() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  a:
                    account: first
                    interval: 1m
                    step-timeout: 1s
                """);
        assertThat(store.loadAll()).extracting(CanaryConfig::account).containsExactly("first");

        storeWith("""
                canaries:
                  a:
                    account: second
                    interval: 1m
                    step-timeout: 1s
                """);

        assertThat(store.loadAll()).extracting(CanaryConfig::account).containsExactly("second");
    }

    @Test
    void reportsEveryProblemAtOnce() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  a:
                    interval: 500ms
                    parallelism: 101
                    step-timeout: 0s
                  b:
                    account: "  "
                """);

        assertThatThrownBy(store::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a needs an account")
                .hasMessageContaining("a has an interval below 1s")
                .hasMessageContaining("a needs a parallelism from 1 to 100")
                .hasMessageContaining("a needs a positive step-timeout")
                .hasMessageContaining("b needs an interval")
                .hasMessageContaining("b needs a step-timeout");
    }

    @Test
    void rejectsAMistypedSetting() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  a:
                    enabeld: true
                    account: test
                    interval: 1m
                    step-timeout: 1s
                """);

        assertThatThrownBy(store::loadAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("enabeld");
    }

    @Test
    void rejectsAValueThatIsNotADuration() throws IOException {
        FileConfigStore store = storeWith("""
                canaries:
                  a:
                    account: test
                    interval: soon
                    step-timeout: 1s
                """);

        assertThatThrownBy(store::loadAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("canaries.a.interval");
    }

    @Test
    void rejectsAFileWithNoCanariesSection() throws IOException {
        assertThatThrownBy(storeWith("other: value\n")::loadAll).hasMessageContaining("has no canaries section");
    }

    @Test
    void rejectsAnEmptyFileSoAHalfWrittenOneCannotSwitchEverythingOff() throws IOException {
        assertThatThrownBy(storeWith("")::loadAll).hasMessageContaining("is empty");
    }

    @Test
    void rejectsMalformedYaml() throws IOException {
        assertThatThrownBy(storeWith("canaries: [unclosed\n")::loadAll).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAMissingFile() {
        FileConfigStore store = new FileConfigStore(directory.resolve("missing.yml"));

        assertThatThrownBy(store::loadAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("missing.yml");
    }
}
