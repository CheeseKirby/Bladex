package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.ClassType;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceProjectIndexSelectionTest {

    @Test
    void prefersMatchingModuleAndEntityName() throws Exception {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        IndexedClassInfo order = info("OrderController", "order", ClassType.CONTROLLER);
        IndexedClassInfo user = info("UserController", "user", ClassType.CONTROLLER);
        setCached(index, List.of(order, user));

        var selected = index.findBestReferenceExample(ClassType.CONTROLLER, "user", "User");

        assertTrue(selected.isPresent());
        assertEquals("UserController", selected.get().simpleName());
    }

    @Test
    void classifiesExcelReferenceTypes() {
        var unit = new com.github.javaparser.JavaParser().parse(
                "package org.springblade.demo.excel; public class DemoExcel {}"
        ).getResult().orElseThrow();
        var declaration = unit.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElseThrow();
        assertEquals(ClassType.EXCEL, ClassType.fromDeclaration(declaration, "org.springblade.demo.excel"));
    }

    private static IndexedClassInfo info(String name, String module, ClassType type) {
        return new IndexedClassInfo(name, "org.springblade." + module, type, false, module,
                "IMPL", "blade-service/blade-" + module,
                "src/" + name + ".java", null, List.of(), Map.of(), List.of());
    }

    private static void setCached(ReferenceProjectIndex index, List<IndexedClassInfo> classes) throws Exception {
        Field field = ReferenceProjectIndex.class.getDeclaredField("cachedFlat");
        field.setAccessible(true);
        field.set(index, classes);
    }
}
