package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springblade.aiworkflow.enums.ClassType;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceProjectSearchTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsBoundedSymbolsRelationsAnomaliesDecisionAndStableSnapshot() throws Exception {
        Path service = Files.createDirectories(tempDir.resolve("blade-service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project><modules><module>blade-specialperiod</module></modules></project>
                """);
        Files.createDirectories(tempDir.resolve("blade-service-api"));
        Files.writeString(tempDir.resolve("blade-service-api/pom.xml"), "<project/>");

        ReferenceProjectIndex index = new ReferenceProjectIndex();
        index.setPath(tempDir.toString());
        setCached(index, List.of(
                info("WorkOrderController", "org.springblade.safetycontrol.controller", ClassType.CONTROLLER,
                        "safetycontrol", null, List.of("org.springblade.safetycontrol.service.IWorkOrderService"), Map.of()),
                info("IWorkOrderService", "org.springblade.safetycontrol.service", ClassType.SERVICE,
                        "safetycontrol", null, List.of("org.springblade.safetycontrol.entity.WorkOrderTask"), Map.of()),
                info("WorkOrderTask", "org.springblade.safetycontrol.entity", ClassType.ENTITY,
                        "safetycontrol", "work_order_task", List.of(),
                        Map.of("hotWorkPlan", "String", "flowId", "String")),
                info("SkRiskDates", "org.springblade.safetycontrol.entity", ClassType.ENTITY,
                        "safetycontrol", "sk_risk_dates", List.of(),
                        Map.of("date", "LocalDate", "type", "Integer")),
                info("getHoliday", "org.springblade.checktime.util", ClassType.OTHER,
                        "checktime", null, List.of(), Map.of()),
                info("User", "org.springblade.system.user.entity", ClassType.ENTITY,
                        "system", "blade_user", List.of(), Map.of("name", "String"))));

        ReferenceSearchResult result = index.searchReference(
                "specialperiod hotwork riskdates holiday approval", 5, 2);

        assertTrue(result.snapshotId().startsWith("ref-"));
        assertEquals(result.snapshotId(), index.searchReference(
                "specialperiod hotwork riskdates holiday approval", 5, 2).snapshotId());
        assertTrue(result.symbols().stream().anyMatch(symbol -> "WorkOrderTask".equals(symbol.simpleName())));
        assertTrue(result.symbols().stream().anyMatch(symbol -> "SkRiskDates".equals(symbol.simpleName())));
        assertTrue(result.symbols().stream().anyMatch(symbol -> "getHoliday".equals(symbol.simpleName())));
        assertFalse(result.symbols().stream().anyMatch(symbol -> "User".equals(symbol.simpleName())));
        assertTrue(result.relations().stream().anyMatch(relation -> "CONTROLLER_SERVICE".equals(relation.type())));
        assertTrue(result.relations().stream().anyMatch(relation -> "ENTITY_TABLE".equals(relation.type())));
        assertTrue(result.relations().size() <= 120);
        assertTrue(result.anomalies().stream().anyMatch(anomaly -> "REF-DANGLING-MODULE".equals(anomaly.code())));
        assertEquals("ARCHITECTURE_DECISION_REQUIRED", result.decisions().get(0).decision());
        assertEquals("safetycontrol", result.decisions().get(0).targetModule());
    }

    @Test
    void relationExpandedSymbolsSerializeUnknownOwnershipAsEmptyStrings() throws Exception {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        index.setPath(tempDir.toString());
        IndexedClassInfo flowController = info("FlowController", "org.springblade.flow.controller",
                ClassType.CONTROLLER, "flow", null,
                List.of("com.xxl.job.admin.core.model.XxlJobRegistry"), Map.of());
        IndexedClassInfo externalRegistry = new IndexedClassInfo(
                "XxlJobRegistry", "com.xxl.job.admin.core.model", ClassType.ENTITY, false,
                null, null, null, "xxl-job-admin/src/main/java/XxlJobRegistry.java",
                "xxl_job_registry", List.of(), Map.of("id", "Integer"), List.of());
        setCached(index, List.of(flowController, externalRegistry));

        ReferenceSearchResult result = index.searchReference("flow workflow", 20, 2);
        ReferenceSearchResult.Symbol symbol = result.symbols().stream()
                .filter(candidate -> "XxlJobRegistry".equals(candidate.simpleName()))
                .findFirst().orElseThrow();

        assertTrue(symbol.relationExpanded());
        assertEquals("", symbol.module());
        assertEquals("", symbol.side());
        assertEquals("com.xxl.job.admin.core.model", symbol.packageName());
    }

    @Test
    void genericCrudFieldMatchesDoNotClaimReferenceOwnership() throws Exception {
        Path service = Files.createDirectories(tempDir.resolve("blade-service"));
        Files.writeString(service.resolve("pom.xml"),
                "<project><modules><module>blade-specialperiod</module></modules></project>");
        Files.createDirectories(tempDir.resolve("blade-service-api"));
        Files.writeString(tempDir.resolve("blade-service-api/pom.xml"), "<project/>");

        ReferenceProjectIndex index = new ReferenceProjectIndex();
        index.setPath(tempDir.toString());
        setCached(index, List.of(
                info("User", "org.springblade.system.user.entity", ClassType.ENTITY,
                        "system", "blade_user", List.of(), Map.of("phone", "String", "status", "Integer")),
                info("SpecialTaskItemUserRelation", "org.springblade.safetycontrol.entity", ClassType.ENTITY,
                        "safetycontrol", "safety_task_user", List.of(), Map.of("visitDate", "LocalDate"))));

        ReferenceSearchResult result = index.searchReference(
                "visitorappointment VisitorAppointment blade_visitor_appointment standalone CRUD status user phone date no integration", 10, 1);

        assertEquals("NEW", result.decisions().get(0).decision());
        assertEquals(null, result.decisions().get(0).targetModule());
    }

    private static IndexedClassInfo info(
            String name,
            String pkg,
            ClassType type,
            String module,
            String table,
            List<String> imports,
            Map<String, String> fields) {
        return new IndexedClassInfo(name, pkg, type, type == ClassType.SERVICE, module,
                type == ClassType.CONTROLLER ? "IMPL" : "API",
                "blade-service/blade-" + module,
                "src/" + name + ".java", table, List.of(), fields, imports);
    }

    private static void setCached(ReferenceProjectIndex index, List<IndexedClassInfo> classes) throws Exception {
        Field field = ReferenceProjectIndex.class.getDeclaredField("cachedFlat");
        field.setAccessible(true);
        field.set(index, classes);
    }
}
