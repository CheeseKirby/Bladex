package org.springblade.aiworkflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanBundleSigningHealthIndicatorTest {

    @Test
    void missingSecretMakesCanonicalIntakeReadinessDown() {
        PlanBundleSigningHealthIndicator indicator = new PlanBundleSigningHealthIndicator("  ");
        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals("PLAN_BUNDLE_SIGNING_SECRET_MISSING", indicator.health().getDetails().get("code"));
    }

    @Test
    void configuredSecretMakesCanonicalIntakeReady() {
        PlanBundleSigningHealthIndicator indicator = new PlanBundleSigningHealthIndicator("shared-secret");
        assertEquals(Status.UP, indicator.health().getStatus());
    }
}
