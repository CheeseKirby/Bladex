package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BladeXModuleSkeletonProfileTest {

    @Test
    void skeletonUsesReferenceVersionAndApplicationConvention() {
        GenerationIdentity identity = GenerationIdentity.of(
                "safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod");
        ReferenceFrameworkProfile profile = new ReferenceFrameworkProfile(
                "2.4.0.RELEASE", "1.8", "org.springblade", "blade-service-api", "blade-service",
                "2.4.0.RELEASE", "2.4.0.RELEASE", "2.4.0.RELEASE", "javax", "v2",
                "entity", Map.of("VO", "vo", "QVO", "vo.qvo", "IVO", "vo.ivo", "UVO", "vo.uvo", "EVO", "vo.evo"),
                "controller", "service", "service.impl", "mapper", "wrapper", "feign", "excel",
                true, "SPRING_CLOUD_APPLICATION", "blade_lxqt", "SPRING_PROFILES", "reference");
        GenerationContext context = new GenerationContext(identity, profile);

        String apiPom = BladeXModuleSkeleton.buildApiSide(context, false).get(0).getContent();
        String application = BladeXModuleSkeleton.buildImplSide(context).stream()
                .filter(file -> file.getFilePath().endsWith("Application.java")).findFirst().orElseThrow().getContent();
        String bootstrap = BladeXModuleSkeleton.buildImplSide(context).stream()
                .filter(file -> file.getFilePath().endsWith("bootstrap.yml")).findFirst().orElseThrow().getContent();

        assertTrue(apiPom.contains("<version>2.4.0.RELEASE</version>"));
        assertFalse(apiPom.contains("${revision}"));
        assertTrue(application.contains("@SpringCloudApplication"));
        assertTrue(application.contains("BladeApplication.run(\"blade-safeprod\""));
        assertTrue(bootstrap.contains("namespace: blade_lxqt"));
        assertTrue(BladeXModuleLayout.entityPath(context, "SpecialPeriod").contains("/entity/SpecialPeriod.java"));
        assertTrue(BladeXModuleLayout.voPath(context, "SpecialPeriod", "IVO").contains("/vo/ivo/SpecialPeriodIVO.java"));
    }
}
