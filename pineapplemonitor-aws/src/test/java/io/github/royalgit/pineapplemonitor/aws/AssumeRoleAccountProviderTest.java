package io.github.royalgit.pineapplemonitor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import io.github.royalgit.pineapplemonitor.account.AccountProvider;
import io.github.royalgit.pineapplemonitor.account.AccountRequest;
import io.github.royalgit.pineapplemonitor.account.EnvAccountProvider;
import io.github.royalgit.pineapplemonitor.api.Account;
import io.github.royalgit.pineapplemonitor.autoconfigure.CanaryAutoConfiguration;

class AssumeRoleAccountProviderTest {

    private static final String PREFIX = "arn:aws:iam::123456789012:role/canary-test-";
    private static final String ROLE = PREFIX + "edit-account";

    private static final class FakeSts implements StsClient {

        final List<AssumeRoleRequest> requests = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public AssumeRoleResponse assumeRole(AssumeRoleRequest request) {
            requests.add(request);
            if (failing) {
                throw StsException.builder().message("AccessDenied").build();
            }
            return AssumeRoleResponse.builder()
                    .credentials(Credentials.builder()
                            .accessKeyId("AKIDEXAMPLE")
                            .secretAccessKey("example-secret")
                            .sessionToken("example-token")
                            .expiration(Instant.now().plus(1, ChronoUnit.HOURS))
                            .build())
                    .build();
        }

        @Override
        public String serviceName() {
            return "sts";
        }

        @Override
        public void close() {
        }
    }

    private final FakeSts sts = new FakeSts();
    private final AssumeRoleAccountProvider provider = new AssumeRoleAccountProvider(sts, PREFIX);
    private final AccountRequest request = new AccountRequest("edit-account", "11111111-2222-3333-4444-555555555555");

    @Test
    void assumesTheRoleWithASessionNamedAfterTheRun() {
        try (Account account = provider.resolve(ROLE, request)) {
            assertThat(account.credentials(AwsCredentialsProvider.class).resolveCredentials().accessKeyId()).isEqualTo("AKIDEXAMPLE");
        }

        assertThat(sts.requests).hasSize(1);
        AssumeRoleRequest sent = sts.requests.get(0);
        assertThat(sent.roleArn()).isEqualTo(ROLE);
        assertThat(sent.roleSessionName()).isEqualTo("edit-account-11111111-2222-3333-4444-555555555555");
        assertThat(sent.durationSeconds()).isEqualTo(900);
    }

    @Test
    void credentialsDoNotAppearInToString() {
        try (Account account = provider.resolve(ROLE, request)) {
            assertThat(account.toString()).doesNotContain("example-secret");
        }
    }

    @Test
    void failureSurfacesWhenResolvingSoTheExecutionIsSkipped() {
        sts.failing = true;

        assertThatThrownBy(() -> provider.resolve(ROLE, request)).hasMessageContaining("AccessDenied");
    }

    @Test
    void rolesOutsideThePrefixAreRejected() {
        String other = "arn:aws:iam::123456789012:role/production-admin";

        assertThatThrownBy(() -> provider.validate(other)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.resolve(other, request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sts.requests).isEmpty();
    }

    @Test
    void sessionNameIsLegalAndFitsTheLimit() {
        String name = AssumeRoleAccountProvider.sessionName(new AccountRequest("a canary/with odd:chars " + "x".repeat(100), request.runId()));

        assertThat(name).hasSizeLessThanOrEqualTo(64).matches("[\\w+=,.@-]+").endsWith(request.runId());
    }

    @TempDir
    Path directory;

    @BeforeEach
    void writeSettings() throws IOException {
        Files.writeString(directory.resolve("canary.yml"), """
                canaries:
                  edit-account:
                    account: %s
                    interval: 5m
                    step-timeout: 10s
                """.formatted(ROLE));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CanaryAutoConfiguration.class, CanaryAwsAutoConfiguration.class))
                .withPropertyValues("canary.config-file=" + directory.resolve("canary.yml"))
                .withBean(StsClient.class, () -> sts);
    }

    @Test
    void replacesTheDefaultProviderOnceAPrefixIsSet() {
        runner().withPropertyValues("canary.aws.allowed-role-prefix=" + PREFIX).run(context ->
                assertThat(context.getBean(AccountProvider.class)).isInstanceOf(AssumeRoleAccountProvider.class));
    }

    @Test
    void roleOutsideThePrefixInTheSettingsFileStopsStartup() throws IOException {
        Files.writeString(directory.resolve("canary.yml"), """
                canaries:
                  edit-account:
                    account: arn:aws:iam::123456789012:role/production-admin
                    interval: 5m
                    step-timeout: 10s
                """);

        runner().withPropertyValues("canary.aws.allowed-role-prefix=" + PREFIX).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("Account must be a role starting with");
        });
    }

    @Test
    void leavesTheDefaultProviderWithoutAPrefix() {
        runner().run(context -> assertThat(context.getBean(AccountProvider.class)).isInstanceOf(EnvAccountProvider.class));
    }
}
