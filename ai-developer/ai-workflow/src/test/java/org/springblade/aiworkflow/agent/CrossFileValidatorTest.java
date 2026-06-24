package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨文件契约校验器单元测试 — 复现编译验证暴露的 4 类 bug。
 *
 * <p>这些用例对应实际生成代码中发现的真实问题:
 * <ol>
 *   <li>VO 包路径不一致 (import vo.ivo.OrderIVO 但实际在 vo 包)</li>
 *   <li>Wrapper 缺 entity(IVO)/entity(UVO) 方法</li>
 *   <li>Feign 引用未生成的 Fallback 类</li>
 *   <li>跨文件方法/字段调用契约校验通过场景</li>
 * </ol>
 */
class CrossFileValidatorTest {

    private final CrossFileValidator validator = new CrossFileValidator();

    @Test
    void shouldDetectImportPackageMismatch() {
        // OrderController 把 OrderIVO 当成 vo.ivo 包下的类, 但实际 OrderIVO 声明在 vo 包
        GeneratedFile controller = file(
                "src/main/java/org/springblade/order/controller/OrderController.java",
                """
                package org.springblade.order.controller;
                import org.springblade.order.vo.ivo.OrderIVO;
                public class OrderController {
                    public void save(OrderIVO ivo) {}
                }
                """
        );
        GeneratedFile ivo = file(
                "src/main/java/org/springblade/order/vo/OrderIVO.java",
                "package org.springblade.order.vo; public class OrderIVO {}"
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(controller, ivo));

        assertTrue(issues.stream().anyMatch(i -> "CROSS-IMPORT-PATH".equals(i.rule)),
                "应检出 import 包路径不一致");
    }

    @Test
    void shouldDetectWrapperMissingEntityMethod() {
        // Wrapper 只有 entityVO 方法, 缺 entity(IVO) 和 entity(UVO)
        GeneratedFile wrapper = file(
                "src/main/java/org/springblade/order/wrapper/OrderWrapper.java",
                """
                package org.springblade.order.wrapper;
                public class OrderWrapper {
                    public Object entityVO(Object entity) { return null; }
                }
                """
        );
        GeneratedFile ivo = file(
                "src/main/java/org/springblade/order/vo/OrderIVO.java",
                "package org.springblade.order.vo; public class OrderIVO {}"
        );
        GeneratedFile uvo = file(
                "src/main/java/org/springblade/order/vo/OrderUVO.java",
                "package org.springblade.order.vo; public class OrderUVO {}"
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(wrapper, ivo, uvo));

        assertTrue(issues.stream().anyMatch(i -> "CROSS-WRAPPER-ENTITY-IVO".equals(i.rule)),
                "应检出 Wrapper 缺 entity(IVO) 方法");
        assertTrue(issues.stream().anyMatch(i -> "CROSS-WRAPPER-ENTITY-UVO".equals(i.rule)),
                "应检出 Wrapper 缺 entity(UVO) 方法");
        assertTrue(issues.stream().anyMatch(CrossFileValidator.ContractIssue::isError),
                "Wrapper 缺方法应为 ERROR 级");
    }

    @Test
    void shouldDetectFeignFallbackMissing() {
        // Feign 接口引用了未生成的 IOrderClientFallback 类
        GeneratedFile feign = file(
                "src/main/java/org/springblade/order/feign/IOrderClient.java",
                """
                package org.springblade.order.feign;
                import org.springframework.cloud.openfeign.FeignClient;
                @FeignClient(value = "blade-order-service", fallback = IOrderClientFallback.class)
                public interface IOrderClient {}
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(feign));

        assertTrue(issues.stream().anyMatch(i -> "CROSS-FEIGN-FALLBACK-MISSING".equals(i.rule)),
                "应检出 Feign 引用了不存在的 fallback 类");
        assertTrue(issues.stream().anyMatch(CrossFileValidator.ContractIssue::isError),
                "Feign 缺 fallback 应为 ERROR 级");
    }

    @Test
    void shouldPassWhenAllContractsHold() {
        // 完整、自洽的一组生成文件 — 包路径一致、Wrapper 方法齐全、Feign 不引用 fallback
        GeneratedFile entity = file(
                "src/main/java/org/springblade/order/entity/Order.java",
                "package org.springblade.order.entity; public class Order {}"
        );
        GeneratedFile ivo = file(
                "src/main/java/org/springblade/order/vo/OrderIVO.java",
                "package org.springblade.order.vo; public class OrderIVO {}"
        );
        GeneratedFile uvo = file(
                "src/main/java/org/springblade/order/vo/OrderUVO.java",
                "package org.springblade.order.vo; public class OrderUVO {}"
        );
        GeneratedFile wrapper = file(
                "src/main/java/org/springblade/order/wrapper/OrderWrapper.java",
                """
                package org.springblade.order.wrapper;
                import org.springblade.order.entity.Order;
                import org.springblade.order.vo.OrderIVO;
                import org.springblade.order.vo.OrderUVO;
                public class OrderWrapper {
                    public Object entityVO(Order entity) { return null; }
                    public Order entity(OrderIVO ivo) { return null; }
                    public Order entity(OrderUVO uvo) { return null; }
                }
                """
        );
        GeneratedFile controller = file(
                "src/main/java/org/springblade/order/controller/OrderController.java",
                """
                package org.springblade.order.controller;
                import org.springblade.order.vo.OrderIVO;
                import org.springblade.order.wrapper.OrderWrapper;
                public class OrderController {
                    public void save(OrderIVO ivo) {
                        new OrderWrapper().entity(ivo);
                    }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(
                List.of(entity, ivo, uvo, wrapper, controller));

        // 不应有任何 ERROR 级问题
        long errors = issues.stream().filter(CrossFileValidator.ContractIssue::isError).count();
        assertEquals(0, errors,
                "完整自洽的文件集合不应有 ERROR 级契约问题; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldHandleEmptyOrMalformedFilesGracefully() {
        // 边界: null content / 非 Java 文件 / 解析失败 — 都不应抛异常
        GeneratedFile sql = new GeneratedFile(TaskType.DDL_STATEMENT,
                "migration.sql", "CREATE TABLE blade_order (id BIGINT);", "CREATED");
        GeneratedFile bad = new GeneratedFile(TaskType.OTHER,
                "Bad.java", "this is not valid java", "CREATED");

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(sql, bad));

        // 不抛异常即通过, 具体 issue 数量不强求
        assertNotNull(issues);
    }

    @Test
    void shouldDetectEntityDdlTypeMismatchAndTenant() {
        // Employee: sex/employeeStatus 是 Integer 但 DDL 是 VARCHAR(32)；DDL 有 tenant_id 但 entity extends BaseEntity
        GeneratedFile entity = file(
                "blade-service-api/blade-employee-api/src/main/java/org/springblade/employee/pojo/entity/Employee.java",
                """
                package org.springblade.employee.pojo.entity;
                public class Employee extends BaseEntity {
                    private Long deptId;
                    private String employeeNo;
                    private String employeeName;
                    private Integer sex;
                    private Integer employeeStatus;
                }
                """
        );
        GeneratedFile ddl = new GeneratedFile(TaskType.DDL_STATEMENT,
                "doc/sql/employee/migration.sql",
                """
                CREATE TABLE IF NOT EXISTS `blade_employee` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `tenant_id` VARCHAR(12) NOT NULL DEFAULT '000000',
                  `dept_id` BIGINT DEFAULT NULL,
                  `employee_no` VARCHAR(64) NOT NULL,
                  `employee_name` VARCHAR(64) NOT NULL,
                  `sex` VARCHAR(32) DEFAULT NULL,
                  `employee_status` VARCHAR(32) DEFAULT NULL,
                  PRIMARY KEY (`id`)
                );
                """,
                "CREATED");

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, ddl));

        assertTrue(issues.stream().anyMatch(i -> "ENTITY-DDL-TYPE-MISMATCH".equals(i.rule)),
                "应检出 sex 类型不匹配 (Integer vs VARCHAR)");
        assertTrue(issues.stream().anyMatch(i -> "ENTITY-DDL-TENANT".equals(i.rule)),
                "应检出 tenant_id 列存在但 entity 未 extends TenantEntity");
        assertTrue(issues.stream().anyMatch(CrossFileValidator.ContractIssue::isError),
                "entity↔DDL 不一致应为 ERROR 级");
    }

    @Test
    void shouldDetectEntityDdlColumnMissing() {
        // Product: DDL 有 remark 业务列但 entity 未映射
        GeneratedFile entity = file(
                "blade-service-api/blade-product-api/src/main/java/org/springblade/product/pojo/entity/Product.java",
                """
                package org.springblade.product.pojo.entity;
                public class Product extends BaseEntity {
                    private String productCode;
                    private String productName;
                    private BigDecimal price;
                    private Integer stock;
                }
                """
        );
        GeneratedFile ddl = new GeneratedFile(TaskType.DDL_STATEMENT,
                "doc/sql/product/migration.sql",
                """
                CREATE TABLE IF NOT EXISTS `blade_product` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `product_code` VARCHAR(64) NOT NULL,
                  `product_name` VARCHAR(255) NOT NULL,
                  `price` DECIMAL(18,2) NOT NULL,
                  `stock` INT NOT NULL,
                  `remark` VARCHAR(500) DEFAULT NULL,
                  PRIMARY KEY (`id`)
                );
                """,
                "CREATED");

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, ddl));

        assertTrue(issues.stream().anyMatch(i -> "ENTITY-DDL-COLUMN-MISSING".equals(i.rule)),
                "应检出 DDL remark 业务列在 entity 缺失");
    }

    @Test
    void shouldPassWhenEntityAlignsWithDdl() {
        // entity 字段与 DDL 列一一对应、类型匹配、无多租户列 → 不应有 ENTITY-DDL 问题
        GeneratedFile entity = file(
                "blade-service-api/blade-product-api/src/main/java/org/springblade/product/pojo/entity/Product.java",
                """
                package org.springblade.product.pojo.entity;
                public class Product extends BaseEntity {
                    private String productCode;
                    private String productName;
                    private BigDecimal price;
                    private Integer stock;
                }
                """
        );
        GeneratedFile ddl = new GeneratedFile(TaskType.DDL_STATEMENT,
                "doc/sql/product/migration.sql",
                """
                CREATE TABLE IF NOT EXISTS `blade_product` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `product_code` VARCHAR(64) NOT NULL,
                  `product_name` VARCHAR(255) NOT NULL,
                  `price` DECIMAL(18,2) NOT NULL,
                  `stock` INT NOT NULL,
                  `status` INT DEFAULT 1,
                  `is_deleted` INT DEFAULT 0,
                  PRIMARY KEY (`id`)
                );
                """,
                "CREATED");

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, ddl));

        long ddlIssues = issues.stream().filter(i -> i.rule != null && i.rule.startsWith("ENTITY-DDL")).count();
        assertEquals(0, ddlIssues,
                "entity 与 DDL 对齐时不应有 ENTITY-DDL 问题; 实际: "
                        + issues.stream().filter(i -> i.rule != null && i.rule.startsWith("ENTITY-DDL"))
                                .map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldNotReportSerialVersionUidAsMissingColumn() {
        // serialVersionUID 是 static final 常量,不是业务字段。
        // 修复前 camelToSnake 会把它产成 "serial_version_u_i_d" 误报 ENTITY-DDL-COLUMN-MISSING。
        GeneratedFile entity = file(
                "blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/entity/Order.java",
                """
                package org.springblade.order.pojo.entity;
                public class Order extends BaseEntity {
                    private static final long serialVersionUID = 1L;
                    private String orderNo;
                }
                """
        );
        GeneratedFile ddl = new GeneratedFile(TaskType.DDL_STATEMENT,
                "doc/sql/order/migration.sql",
                """
                CREATE TABLE IF NOT EXISTS `blade_order` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `order_no` VARCHAR(64) NOT NULL,
                  PRIMARY KEY (`id`)
                );
                """,
                "CREATED");

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, ddl));

        assertTrue(issues.stream().noneMatch(i -> i.message != null && i.message.contains("serial_version_u_i_d")),
                "serialVersionUID 不应被当成业务字段误报; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    private GeneratedFile file(String path, String content) {
        return new GeneratedFile(TaskType.OTHER, path, content, "CREATED");
    }
}
