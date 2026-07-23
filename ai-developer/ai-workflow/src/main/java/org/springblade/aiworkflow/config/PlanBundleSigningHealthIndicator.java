package org.springblade.aiworkflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness gate for trusted Canonical Plan Contract v2 bundle intake. */
@Component("planBundleSigning")
public class PlanBundleSigningHealthIndicator implements HealthIndicator {

    private final String signingSecret;

    public PlanBundleSigningHealthIndicator(
            @Value("${ai-workflow.bundle-signing-secret:}") String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
    }

    @Override
    public Health health() {
        if (signingSecret.isEmpty()) {
            return Health.down()
                    .withDetail("code", "PLAN_BUNDLE_SIGNING_SECRET_MISSING")
                    .withDetail("canonicalV2Intake", "FAIL_CLOSED")
                    .build();
        }
        return Health.up().withDetail("canonicalV2Intake", "READY").build();
    }
}
