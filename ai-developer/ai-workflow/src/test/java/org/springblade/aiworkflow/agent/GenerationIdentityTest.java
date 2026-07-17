package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationIdentityTest {

    @Test
    void explicitIdentityIsCanonicalizedOnce() {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("project-1");
        PlanReceiveRequest.MasterPlanVO master = new PlanReceiveRequest.MasterPlanVO();
        master.setContent("moduleName: pom\nentityName: Wrong");
        request.setMasterPlan(master);
        PlanReceiveRequest.GenerationIdentityVO supplied = new PlanReceiveRequest.GenerationIdentityVO();
        supplied.setModuleName("blade-safeprod-api");
        supplied.setEntityName("SpecialPeriod");
        supplied.setTableName("blade_special_period");
        supplied.setBasePackage("org.springblade.safeprod");
        request.setGenerationIdentity(supplied);

        GenerationIdentity identity = GenerationIdentityResolver.resolve(request);
        assertEquals("safeprod", identity.moduleName());
        assertEquals("SpecialPeriod", identity.entityName());
        assertEquals("blade-safeprod-api", identity.apiModuleName());
        assertEquals("blade-safeprod", identity.serviceModuleName());
    }

    @Test
    void reservedModuleNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GenerationIdentity.of("pom", "SpecialPeriod", "blade_special_period", "org.springblade.pom"));
    }
}
