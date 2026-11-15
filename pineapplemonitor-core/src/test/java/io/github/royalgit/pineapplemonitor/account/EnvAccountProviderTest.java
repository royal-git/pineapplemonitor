package io.github.royalgit.pineapplemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.royalgit.pineapplemonitor.api.Account;

class EnvAccountProviderTest {

    private final AccountRequest request = new AccountRequest("edit-account", "run-1");
    private final EnvAccountProvider provider = new EnvAccountProvider(Map.of(
            "CANARY_ACCOUNT_EDIT_ACCOUNT_TEST_API_KEY", "secret-value",
            "CANARY_ACCOUNT_OTHER_API_KEY", "other-value",
            "PATH", "/bin"));

    @Test
    void readsOnlyTheVariablesForThatReference() {
        Account account = provider.resolve("edit-account-test", request);

        EnvCredentials credentials = account.credentials(EnvCredentials.class);
        assertThat(credentials.get("API_KEY")).isEqualTo("secret-value");
        assertThat(account.reference()).isEqualTo("edit-account-test");
    }

    @Test
    void unknownReferenceThrowsSoTheExecutionIsSkipped() {
        assertThatThrownBy(() -> provider.resolve("missing", request)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secretsNeverAppearInToString() {
        Account account = provider.resolve("edit-account-test", request);

        assertThat(account.toString()).doesNotContain("secret-value");
        assertThat(account.credentials(EnvCredentials.class).toString()).doesNotContain("secret-value");
    }
}
