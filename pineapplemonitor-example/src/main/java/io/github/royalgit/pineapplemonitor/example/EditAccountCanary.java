package io.github.royalgit.pineapplemonitor.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Value;

import io.github.royalgit.pineapplemonitor.account.EnvCredentials;
import io.github.royalgit.pineapplemonitor.api.Before;
import io.github.royalgit.pineapplemonitor.api.Canary;
import io.github.royalgit.pineapplemonitor.api.Cleanup;
import io.github.royalgit.pineapplemonitor.api.RunContext;
import io.github.royalgit.pineapplemonitor.api.Step;

/** Checks that editing an account takes effect: edit it, read it back, then restore the baseline. */
@Canary("edit-account")
public class EditAccountCanary {

    static final String ACCOUNT_ID = "canary-test-account";
    static final String BASELINE_NAME = "canary-baseline";

    private final String baseUrl;
    private AccountClient accounts;
    private String newName;

    public EditAccountCanary(@Value("http://127.0.0.1:${local.server.port}/example-api") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Before
    void createClient(RunContext ctx) {
        String apiKey = ctx.account().credentials(EnvCredentials.class).get("API_KEY");
        accounts = new AccountClient(baseUrl, ACCOUNT_ID, apiKey);
    }

    @Step(1)
    void edit(RunContext ctx) throws Exception {
        newName = "canary-" + ctx.runId();
        accounts.setDisplayName(newName);
    }

    @Step(2)
    void confirm(RunContext ctx) throws Exception {
        assertThat(accounts.displayName()).isEqualTo(newName);
        ctx.metrics().count("edits.confirmed", 1);
    }

    @Cleanup
    void restoreBaseline() throws Exception {
        if (accounts != null) {
            accounts.setDisplayName(BASELINE_NAME);
        }
    }
}
