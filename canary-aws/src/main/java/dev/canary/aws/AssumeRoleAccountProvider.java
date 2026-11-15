package dev.canary.aws;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import dev.canary.account.AccountProvider;
import dev.canary.account.AccountRequest;
import dev.canary.api.Account;

/**
 * Assumes a dedicated test role for each execution, so AWS-authenticated APIs need no stored secrets.
 * Canaries get an {@link AwsCredentialsProvider} from {@code ctx.account()}.
 *
 * <p>The service role's own policy should also limit it to the same prefix. The check here gives a
 * clear error for a bad settings file and is not the security boundary.
 */
public final class AssumeRoleAccountProvider implements AccountProvider {

    /** The shortest session STS allows. The SDK refreshes it during longer runs. */
    private static final int SESSION_SECONDS = 900;

    private static final int MAX_SESSION_NAME = 64;

    private final StsClient sts;
    private final String allowedRolePrefix;

    public AssumeRoleAccountProvider(StsClient sts, String allowedRolePrefix) {
        this.sts = sts;
        this.allowedRolePrefix = allowedRolePrefix;
    }

    @Override
    public void validate(String reference) {
        if (!reference.startsWith(allowedRolePrefix)) {
            throw new IllegalArgumentException("Account must be a role starting with " + allowedRolePrefix + ".");
        }
    }

    @Override
    public Account resolve(String reference, AccountRequest request) {
        validate(reference);
        StsAssumeRoleCredentialsProvider provider = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .refreshRequest(assumeRole -> assumeRole
                        .roleArn(reference)
                        .roleSessionName(sessionName(request))
                        .durationSeconds(SESSION_SECONDS))
                .build();
        try {
            // Fetches now, so a failure skips the execution rather than failing a canary method.
            provider.resolveCredentials();
        } catch (RuntimeException e) {
            provider.close();
            throw e;
        }
        return new Account(reference, provider);
    }

    /** The session name ties each CloudTrail entry to a canary and run. */
    static String sessionName(AccountRequest request) {
        String runId = request.runId();
        String canary = request.canary().replaceAll("[^\\w+=,.@-]", "-");
        int room = MAX_SESSION_NAME - runId.length() - 1;
        return (canary.length() > room ? canary.substring(0, room) : canary) + "-" + runId;
    }
}
