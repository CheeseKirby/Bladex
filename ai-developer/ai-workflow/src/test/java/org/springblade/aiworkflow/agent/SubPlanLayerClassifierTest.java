package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubPlanLayerClassifierTest {

    @Test
    void feignTargetLayerDoesNotAlsoBecomeMapperOrService() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "Feign \u8fdc\u7a0b\u8c03\u7528 - \u5347\u7ea7\u8bb0\u5f55\u8de8\u670d\u52a1\u63a5\u53e3",
                "## \u76ee\u6807\u5c42: Feign \u8fdc\u7a0b\u8c03\u7528 (API \u6a21\u5757\u63a5\u53e3 + Service \u6a21\u5757\u5b9e\u73b0)");

        assertTrue(result.feign());
        assertFalse(result.mapper());
        assertFalse(result.service());
        assertFalse(result.controller());
    }

    @Test
    void explicitEntityAndVoLayerDoesNotTreatApiModuleNoteAsController() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "anything",
                "## \u76ee\u6807\u5c42: Entity + VO (API \u6a21\u5757)");

        assertTrue(result.entity());
        assertTrue(result.vo());
        assertFalse(result.controller());
        assertFalse(result.wrapper());
        assertFalse(result.feign());
    }

    @Test
    void wrapperAndControllerLayerIgnoresServiceModuleQualifier() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "anything",
                "## \u76ee\u6807\u5c42: Wrapper + Controller (Service \u6a21\u5757)");

        assertTrue(result.wrapper());
        assertTrue(result.controller());
        assertFalse(result.mapper());
        assertFalse(result.service());
        assertFalse(result.feign());
    }

    @Test
    void serviceModulePrefixDoesNotCreateMapperOrServiceForControllerPlan() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "Wrapper and Controller layers",
                "## \u76ee\u6807\u5c42: Service \u6a21\u5757 (blade-specialperiod) - Wrapper + Controller");

        assertTrue(result.wrapper());
        assertTrue(result.controller());
        assertFalse(result.mapper());
        assertFalse(result.service());
    }

    @Test
    void excelLayerIgnoresServiceModuleQualifier() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "anything",
                "## \u76ee\u6807\u5c42: Excel \u5bfc\u5165\u5bfc\u51fa (Service \u6a21\u5757)");

        assertTrue(result.excel());
        assertFalse(result.mapper());
        assertFalse(result.service());
        assertFalse(result.controller());
        assertFalse(result.feign());
    }

    @Test
    void meaningfulParentheticalLayerTokenIsPreserved() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "anything",
                "## \u76ee\u6807\u5c42: \u8de8\u670d\u52a1\u63a5\u53e3 (Feign)");

        assertTrue(result.feign());
        assertFalse(result.mapper());
        assertFalse(result.service());
        assertFalse(result.controller());
    }


    @Test
    void genericApiModuleDeclarationKeepsSpecificEntityAndVoTitleSignals() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "Entity \u5b9e\u4f53\u4e0e VO \u89c6\u56fe\u5bf9\u8c61\u5b9a\u4e49",
                "## \u76ee\u6807\u5c42: API \u6a21\u5757 (blade-specialperiod-api)");

        assertTrue(result.entity());
        assertTrue(result.vo());
        assertFalse(result.controller());
        assertFalse(result.service());
    }

    @Test
    void mapperAndServiceTargetLayerKeepsTheCrudServiceStack() {
        SubPlanLayerClassifier.Classification result = SubPlanLayerClassifier.classify(
                "anything",
                "## \u76ee\u6807\u5c42: Mapper + Service (Service \u6a21\u5757)");

        assertTrue(result.mapper());
        assertTrue(result.service());
        assertFalse(result.feign());
    }
}
