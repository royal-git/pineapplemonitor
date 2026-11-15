package dev.canary.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.sts.StsClient;

import dev.canary.account.AccountProvider;
import dev.canary.autoconfigure.CanaryAutoConfiguration;

/** Assumes roles once {@code canary.aws.allowed-role-prefix} says which roles settings may use. */
@AutoConfiguration(before = CanaryAutoConfiguration.class)
@ConditionalOnProperty("canary.aws.allowed-role-prefix")
public class CanaryAwsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    StsClient canaryStsClient() {
        return StsClient.create();
    }

    @Bean
    @ConditionalOnMissingBean
    AccountProvider assumeRoleAccountProvider(StsClient sts, @Value("${canary.aws.allowed-role-prefix}") String allowedRolePrefix) {
        return new AssumeRoleAccountProvider(sts, allowedRolePrefix);
    }
}
