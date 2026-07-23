package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.ClassType;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceArtifactBinderTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("specialperiod", "SpecialPeriod", "blade_special_period",
                    "org.springblade.specialperiod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void bindsCurrentModuleDtoAndProviderToDifferentModuleSides() {
        List<PlannedArtifact> artifacts = List.of(
                artifact("SpecialPeriodCheckDTO", ArtifactKind.DTO, ArtifactAction.CREATE,
                        "specialperiod", ModuleSide.API, "org.springblade.specialperiod.dto"),
                artifact("SpecialPeriodClient", ArtifactKind.FEIGN_PROVIDER, ArtifactAction.CREATE,
                        "specialperiod", ModuleSide.IMPL, "org.springblade.specialperiod.feign"));

        ReferenceArtifactBinder.BindingResult result = new ReferenceArtifactBinder()
                .bind(artifacts, context, null);

        assertTrue(result.issues().isEmpty());
        assertEquals("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/dto/SpecialPeriodCheckDTO.java",
                result.artifacts().get(0).targetPath());
        assertEquals("blade-service/blade-specialperiod/src/main/java/org/springblade/specialperiod/feign/SpecialPeriodClient.java",
                result.artifacts().get(1).targetPath());
    }

    @Test
    void bindsCurrentModuleServiceContributionsToCanonicalInterfaceAndImplementationPaths() {
        List<PlannedArtifact> artifacts = List.of(
                artifact("ISpecialPeriodService", ArtifactKind.SERVICE_INTERFACE, ArtifactAction.MODIFY,
                        "specialperiod", ModuleSide.IMPL, null),
                artifact("SpecialPeriodServiceImpl", ArtifactKind.SERVICE_IMPL, ArtifactAction.MODIFY,
                        "specialperiod", ModuleSide.IMPL, null));

        ReferenceArtifactBinder.BindingResult result = new ReferenceArtifactBinder()
                .bind(artifacts, context, null);

        assertTrue(result.issues().isEmpty());
        assertEquals(BladeXModuleLayout.serviceInterfacePath(context, "SpecialPeriod"),
                result.artifacts().get(0).targetPath());
        assertEquals(BladeXModuleLayout.serviceImplPath(context, "SpecialPeriod"),
                result.artifacts().get(1).targetPath());
    }

    @Test
    void bindsModifyToExactReferencePathAndRejectsMissingTarget() throws Exception {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        setCached(index, List.of(
                info("ExistingController", "existing",
                        "blade-service/blade-existing/src/main/java/org/springblade/existing/controller/ExistingController.java"),
                info("HotworkAuditController", "hotwork",
                        "blade-service/blade-hotwork/src/main/java/org/springblade/hotwork/controller/HotworkAuditController.java")));
        List<PlannedArtifact> artifacts = List.of(
                artifact("ExistingController", ArtifactKind.CONTROLLER, ArtifactAction.MODIFY,
                        "existing", ModuleSide.IMPL, null),
                artifact("HotworkController", ArtifactKind.CONTROLLER, ArtifactAction.MODIFY,
                        "hotwork", ModuleSide.IMPL, null));

        ReferenceArtifactBinder.BindingResult result = new ReferenceArtifactBinder()
                .bind(artifacts, context, index);

        assertEquals("blade-service/blade-existing/src/main/java/org/springblade/existing/controller/ExistingController.java",
                result.artifacts().get(0).targetPath());
        assertTrue(result.issues().stream().anyMatch(issue -> "PLAN-TARGET-NOT-FOUND".equals(issue.rule())));
        assertFalse(result.artifacts().stream().anyMatch(item -> "HotworkController".equals(item.name())
                && item.targetPath() != null));
    }


    @Test
    void reportsMissingCrossModuleBeforeAttemptingToGuessAPath() throws Exception {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        setCached(index, List.of(info("ExistingController", "existing",
                "blade-service/blade-existing/src/main/java/org/springblade/existing/controller/ExistingController.java")));

        ReferenceArtifactBinder.BindingResult result = new ReferenceArtifactBinder().bind(
                List.of(artifact("HotworkController", ArtifactKind.CONTROLLER, ArtifactAction.MODIFY,
                        "hotwork", ModuleSide.IMPL, null)), context, index);

        assertTrue(result.issues().stream().anyMatch(issue -> "PLAN-MODULE-NOT-FOUND".equals(issue.rule())));
        assertFalse(result.artifacts().stream().anyMatch(item -> item.targetPath() != null));
    }

    private PlannedArtifact artifact(String name, ArtifactKind kind, ArtifactAction action,
                                     String module, ModuleSide side, String pkg) {
        return new PlannedArtifact(1L, name, kind, action, module, side, pkg,
                null, true, "test");
    }

    private IndexedClassInfo info(String name, String module, String path) {
        return new IndexedClassInfo(name, "org.springblade." + module + ".controller", ClassType.CONTROLLER,
                false, module, "IMPL", "blade-service/blade-" + module, path,
                null, List.of(), Map.of(), List.of());
    }

    private static void setCached(ReferenceProjectIndex index, List<IndexedClassInfo> classes) throws Exception {
        Field field = ReferenceProjectIndex.class.getDeclaredField("cachedFlat");
        field.setAccessible(true);
        field.set(index, classes);
    }
}
