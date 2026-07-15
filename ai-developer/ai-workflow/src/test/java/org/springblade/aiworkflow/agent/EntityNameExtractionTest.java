package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BladeXCodeAgent.extractEntityName 的回归测试。
 *
 * <p>之前实测发现:子方案 content 写成 "实体名 Order, 模块名 order" 时,
 * 旧正则 {@code (?:类名|实体类名|实体|Entity名)[:：\s]+...} 不匹配
 * (实体 后面紧跟 "名" 而不是冒号/空格),导致实体名漂移成默认值 "Entity"。
 *
 * <p>本测试覆盖常见的实体名声明写法,确保都能被正确提取。
 */
class EntityNameExtractionTest {

    private String invokeExtract(String content) throws Exception {
        // 用反射调用 private 方法 extractEntityName(String)
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                0, false, req -> {}, null, null, null, null, null);
        Method m = BladeXCodeAgent.class.getDeclaredMethod("extractEntityName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(agent, content);
    }

    @Test
    void shouldMatchEntityNameWithSpace() throws Exception {
        // 真实场景: "实体名 Order, 模块名 order"
        assertEquals("Order", invokeExtract("实体名 Order, 模块名 order"));
    }

    @Test
    void shouldMatchEntityNameWithColon() throws Exception {
        assertEquals("Product", invokeExtract("实体名: Product"));
        assertEquals("Product", invokeExtract("实体名:Product"));
        assertEquals("Product", invokeExtract("实体名:`Product`"));
    }

    @Test
    void shouldMatchClassName() throws Exception {
        assertEquals("Customer", invokeExtract("类名: Customer"));
        assertEquals("Customer", invokeExtract("类名 Customer"));
    }

    @Test
    void shouldMatchFromClassDeclaration() throws Exception {
        assertEquals("Invoice", invokeExtract("public class Invoice extends BaseEntity"));
    }

    @Test
    void shouldMatchFromPath() throws Exception {
        assertEquals("Refund", invokeExtract("路径: src/main/java/org/springblade/order/entity/Refund.java"));
    }

    @Test
    void shouldFallbackWhenNoMatch() throws Exception {
        // 完全没有实体名声明时,应返回默认的 "Entity" 字面量
        // (这是已知降级行为,前端应在 subPlan content 里明确写实体名)
        assertEquals("Entity", invokeExtract("做一个简单模块"));
    }

    @Test
    void shouldNotConfuseWithBaseEntityLiteral() throws Exception {
        // 避免把 "extends BaseEntity" 误识别为实体名 "BaseEntity" 或 "Entity"
        // (此场景下应优先匹配前面的"实体名: Order")
        assertEquals("Order", invokeExtract("实体名: Order, 继承 BaseEntity"));
    }
}
