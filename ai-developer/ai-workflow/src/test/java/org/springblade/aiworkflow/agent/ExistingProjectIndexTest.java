package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.vo.ProjectScanVO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExistingProjectIndex 单元测试 — 阶段1 扫描核心逻辑。
 *
 * <p>用 @TempDir 构造一个迷你 BladeX 项目结构,手写各类型 .java + 坏文件 + target/ 排除,
 * 验证:分类规则、@TableName 提取、非static字段、public方法签名、坏文件跳过、缓存、过滤查询。
 */
class ExistingProjectIndexTest {

    /** 构造一个指向 tempDir 的 properties(只关心 targetProjectRoot 字段) */
    private AiWorkflowProperties propsFor(Path tempDir) {
        AiWorkflowProperties p = new AiWorkflowProperties();
        p.setTargetProjectRoot(tempDir.toString());
        return p;
    }

    @Test
    void shouldScanAndClassifyAllTypes(@TempDir Path tempDir) throws IOException {
        // 构造迷你 BladeX 结构
        writeJava(tempDir, "blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/entity/Order.java",
                """
                package org.springblade.order.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import org.springblade.core.mp.base.BaseEntity;
                @TableName("blade_order")
                public class Order extends BaseEntity {
                    private static final long serialVersionUID = 1L;
                    private String orderNo;
                    private Long customerId;
                }
                """);
        writeJava(tempDir, "blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/vo/OrderVO.java",
                "package org.springblade.order.pojo.vo; public class OrderVO {}");
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/service/IOrderService.java",
                "package org.springblade.order.service; import org.springblade.core.mp.base.BaseService;\n"
                + "import org.springblade.order.pojo.entity.Order;\n"
                + "public interface IOrderService extends BaseService<Order> { boolean pay(Long id); }");
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/service/impl/OrderServiceImpl.java",
                "package org.springblade.order.service.impl; public class OrderServiceImpl {}");
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/controller/OrderController.java",
                "package org.springblade.order.controller; public class OrderController {}");
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/mapper/OrderMapper.java",
                "package org.springblade.order.mapper; public interface OrderMapper {}");

        ExistingProjectIndex index = new ExistingProjectIndex(propsFor(tempDir));
        ProjectScanVO vo = index.scan(true);

        // 元信息
        assertEquals(6, vo.getMeta().getTotalFiles(), "应扫到 6 个 .java");
        assertEquals(6, vo.getMeta().getIndexedClasses(), "6 个全部索引成功");
        assertEquals(0, vo.getMeta().getSkippedFiles(), "无坏文件,跳过应为 0");
        assertFalse(vo.getMeta().isFromCache(), "force=true 不应来自缓存");

        // 分类正确
        assertEquals(ClassType.ENTITY, find(index, "Order").get().type(), "Order 应分类为 ENTITY");
        assertEquals(ClassType.VO, find(index, "OrderVO").get().type(), "OrderVO 应分类为 VO");
        assertEquals(ClassType.SERVICE, find(index, "IOrderService").get().type(), "IOrderService 应分类为 SERVICE");
        assertEquals(ClassType.SERVICE_IMPL, find(index, "OrderServiceImpl").get().type());
        assertEquals(ClassType.CONTROLLER, find(index, "OrderController").get().type());
        assertEquals(ClassType.MAPPER, find(index, "OrderMapper").get().type());

        // Order 实体细节
        IndexedClassInfo order = find(index, "Order").get();
        assertEquals("blade_order", order.tableName(), "应提取 @TableName 表名");
        assertEquals("order", order.module(), "模块名应为 order");
        assertEquals("API", order.side(), "Order 在 api 模块,side 应为 API");
        // 非static字段: orderNo/customerId(serialVersionUID 排除)
        assertEquals(2, order.fields().size(), "应只含 2 个非static字段(排除 serialVersionUID)");
        assertTrue(order.fields().containsKey("orderNo"));
        assertTrue(order.fields().containsKey("customerId"));
        // public 方法: BaseEntity 的方法不在 cid.getMethods()(继承的不解析),Order 自身无 public 方法
        assertTrue(order.publicMethodSignatures().isEmpty(), "Order 自身无 public 方法");

        // IOrderService 的自定义方法签名
        IndexedClassInfo svc = find(index, "IOrderService").get();
        assertTrue(svc.publicMethodSignatures().contains("pay(Long)"), "应含 pay(Long) 方法签名");
        assertTrue(svc.interfaze(), "IOrderService 应识别为接口");

        // 模块分组
        assertTrue(vo.getModules().stream().anyMatch(g -> "order".equals(g.getModule()) && g.getClassCount() == 6),
                "应有 order 模块组,含 6 个类");
    }

    @Test
    void shouldSkipBrokenFileAndTargetDir(@TempDir Path tempDir) throws IOException {
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/Good.java",
                "package org.springblade.order; public class Good {}");
        // 坏文件(语法错误,JavaParser 解析失败)
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/Bad.java",
                "this is not java at all {{{");
        // target/ 下的应被排除
        writeJava(tempDir, "blade-service/blade-order/target/classes/org/springblade/order/Compiled.class.java",
                "package org.springblade.order; public class Compiled {}");

        ExistingProjectIndex index = new ExistingProjectIndex(propsFor(tempDir));
        ProjectScanVO vo = index.scan(true);

        // target/ 下文件被排除,不进 totalFiles。target 外:Good + Bad = 2
        assertEquals(2, vo.getMeta().getTotalFiles(), "target/ 下文件应被排除,totalFiles 只算 target 外");
        assertEquals(1, vo.getMeta().getIndexedClasses(), "Good 索引成功,Bad 解析失败");
        assertEquals(1, vo.getMeta().getSkippedFiles(), "Bad 坏文件应跳过");
        assertTrue(find(index, "Good").isPresent(), "Good 应被索引");
        assertFalse(find(index, "Bad").isPresent(), "Bad 应被跳过,不在索引");
        assertFalse(find(index, "Compiled").isPresent(), "target/ 下文件应被排除,不在索引");
    }

    @Test
    void shouldReturnCacheWhenForceFalse(@TempDir Path tempDir) throws IOException {
        writeJava(tempDir, "src/main/java/Foo.java", "public class Foo {}");
        ExistingProjectIndex index = new ExistingProjectIndex(propsFor(tempDir));

        ProjectScanVO first = index.scan(true);
        assertFalse(first.getMeta().isFromCache(), "首次 force=true 不来自缓存");

        ProjectScanVO second = index.scan(false);
        assertTrue(second.getMeta().isFromCache(), "force=false 应来自缓存");
        assertEquals(first.getMeta().getTotalFiles(), second.getMeta().getTotalFiles(), "缓存内容应一致");

        assertTrue(index.getCachedScan() != null, "缓存后 getCachedScan 应非空");
    }

    @Test
    void shouldFilterByModuleAndType(@TempDir Path tempDir) throws IOException {
        writeJava(tempDir, "blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/entity/Order.java",
                "package org.springblade.order.pojo.entity; public class Order {}");
        writeJava(tempDir, "blade-service-api/blade-user-api/src/main/java/org/springblade/user/pojo/entity/User.java",
                "package org.springblade.user.pojo.entity; public class User {}");
        writeJava(tempDir, "blade-service/blade-order/src/main/java/org/springblade/order/controller/OrderController.java",
                "package org.springblade.order.controller; public class OrderController {}");

        ExistingProjectIndex index = new ExistingProjectIndex(propsFor(tempDir));
        index.scan(true);

        List<IndexedClassInfo> orderClasses = index.filter("order", null, null);
        assertEquals(2, orderClasses.size(), "order 模块应有 2 个类(Order+OrderController)");

        List<IndexedClassInfo> entities = index.filter(null, ClassType.ENTITY, null);
        assertEquals(2, entities.size(), "应有 2 个 ENTITY(Order+User)");

        List<IndexedClassInfo> byName = index.filter(null, null, "Order");
        assertEquals(2, byName.size(), "名含 Order 的应有 2 个(Order+OrderController)");
    }

    @Test
    void shouldRejectNonExistentRoot() {
        AiWorkflowProperties p = new AiWorkflowProperties();
        p.setTargetProjectRoot("/definitely/does/not/exist/xyz123");
        ExistingProjectIndex index = new ExistingProjectIndex(p);
        // 构造时不抛(scan 时校验)
        assertThrows(IllegalArgumentException.class, () -> index.scan(true),
                "根不存在时 scan 应抛 IllegalArgumentException → 400");
    }

    // ─── 辅助 ───

    private void writeJava(Path root, String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Optional<IndexedClassInfo> find(ExistingProjectIndex index, String simpleName) {
        return index.findBySimpleName(simpleName);
    }
}
