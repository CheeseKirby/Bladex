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

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(controller, ivo), true);

        CrossFileValidator.ContractIssue issue = issues.stream()
                .filter(i -> "CROSS-IMPORT-PATH".equals(i.rule))
                .findFirst().orElseThrow();
        assertTrue(issue.isError(), "Plan-wide generated import mismatch must enter the repair loop");
        assertEquals(controller.getFilePath(), issue.sourceFilePath);
        assertEquals(ivo.getFilePath(), issue.contractFilePath);
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

    @Test
    void shouldDetectControllerServiceMethodMismatch() {
        // 复现 screw 模块真实问题: Controller 调 warningList()(0参),
        // 但 IService 定义 warningList(String warehouseCode)(1参) → 编译失败
        GeneratedFile service = file(
                "src/main/java/org/springblade/screw/service/IScrewStockService.java",
                """
                package org.springblade.screw.service;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.screw.pojo.entity.ScrewStock;
                public interface IScrewStockService extends BaseService<ScrewStock> {
                    java.util.List warningList(String warehouseCode);
                }
                """
        );
        GeneratedFile controller = file(
                "src/main/java/org/springblade/screw/controller/ScrewStockController.java",
                """
                package org.springblade.screw.controller;
                import org.springblade.screw.service.IScrewStockService;
                public class ScrewStockController {
                    private final IScrewStockService screwStockService;
                    public ScrewStockController(IScrewStockService s) { this.screwStockService = s; }
                    public Object warningList() {
                        return screwStockService.warningList();
                    }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(service, controller));

        assertTrue(issues.stream().anyMatch(i -> "CROSS-CONTROLLER-SERVICE-MISMATCH".equals(i.rule)),
                "应检出 Controller 调 warningList(0参) 与 Service warningList(1参) 不匹配; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        assertTrue(issues.stream().anyMatch(i -> "CROSS-CONTROLLER-SERVICE-MISMATCH".equals(i.rule) && i.isError()),
                "Controller→Service 不匹配应为 ERROR 级");
        // 跨文件修复定位字段: sourceFilePath=Controller(需重生成), contractFilePath=Service 接口(契约源头)
        CrossFileValidator.ContractIssue mismatch = issues.stream()
                .filter(i -> "CROSS-CONTROLLER-SERVICE-MISMATCH".equals(i.rule)).findFirst().orElseThrow();
        assertEquals("src/main/java/org/springblade/screw/controller/ScrewStockController.java",
                mismatch.sourceFilePath, "sourceFilePath 应指向需修复的 Controller");
        assertEquals("src/main/java/org/springblade/screw/service/IScrewStockService.java",
                mismatch.contractFilePath, "contractFilePath 应指向契约源头 Service 接口");
    }

    @Test
    void shouldNotReportBaseServiceInheritedMethods() {
        // Controller 调 save/updateById/getOne/deleteLogic — 这些是 BaseService 父类方法,
        // 不在生成的 IService 定义里但调用合法, 不应误报。
        GeneratedFile service = file(
                "src/main/java/org/springblade/order/service/IOrderService.java",
                """
                package org.springblade.order.service;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.order.pojo.entity.Order;
                public interface IOrderService extends BaseService<Order> {
                    boolean saveOrder(String code);
                }
                """
        );
        GeneratedFile controller = file(
                "src/main/java/org/springblade/order/controller/OrderController.java",
                """
                package org.springblade.order.controller;
                import org.springblade.order.service.IOrderService;
                public class OrderController {
                    private final IOrderService orderService;
                    public OrderController(IOrderService s) { this.orderService = s; }
                    public void save() { orderService.save(null); }
                    public void update() { orderService.updateById(null); }
                    public void get() { orderService.getOne(null); }
                    public void remove() { orderService.deleteLogic(null); }
                    public void custom() { orderService.saveOrder("x"); }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(service, controller));

        // 不应有 CROSS-CONTROLLER-SERVICE-MISMATCH 误报
        long mismatches = issues.stream()
                .filter(i -> "CROSS-CONTROLLER-SERVICE-MISMATCH".equals(i.rule)).count();
        assertEquals(0, mismatches,
                "BaseService 父类方法(save/updateById/getOne/deleteLogic)与已定义的 saveOrder 不应误报; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectEntityDdlMismatchForMultiWordTable() {
        // 复现 Course 真实问题: 表名带下划线分词(blade_training_course), entity 包是 training (单词)
        // 旧实现用"表名去 blade_ 前缀"→ training_course, 与包路径 training 不匹配, 校验静默失效。
        // 新实现用 @TableName 注解直接匹配表名, 应正确检出 courseType String vs INT 类型不符。
        GeneratedFile ddl = file(
                "doc/sql/training/migration.sql",
                "CREATE TABLE `blade_training_course` (\n"
                        + "  `id` BIGINT NOT NULL AUTO_INCREMENT,\n"
                        + "  `course_type` INT(2) DEFAULT NULL COMMENT '课程类型',\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ");"
        );
        GeneratedFile entity = file(
                "src/main/java/org/springblade/training/pojo/entity/Course.java",
                """
                package org.springblade.training.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import org.springblade.core.mp.base.BaseEntity;
                @TableName("blade_training_course")
                public class Course extends BaseEntity {
                    private String courseType;
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(ddl, entity));

        assertTrue(issues.stream().anyMatch(i -> "ENTITY-DDL-TYPE-MISMATCH".equals(i.rule) && i.isError()),
                "多词表名应通过 @TableName 注解匹配, 检出 courseType String vs INT 类型不符; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectServiceImplOverridesNotInInterface() {
        // 复现 Course 真实问题: ICourseService 接口空, 但 CourseServiceImpl 有 8 个 @Override 自定义方法
        // → 编译失败(@Override 找不到父方法)。
        GeneratedFile iface = file(
                "src/main/java/org/springblade/training/service/ICourseService.java",
                """
                package org.springblade.training.service;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.training.pojo.entity.Course;
                public interface ICourseService extends BaseService<Course> {
                }
                """
        );
        GeneratedFile impl = file(
                "src/main/java/org/springblade/training/service/impl/CourseServiceImpl.java",
                """
                package org.springblade.training.service.impl;
                import org.springblade.core.mp.base.BaseServiceImpl;
                import org.springblade.training.mapper.CourseMapper;
                import org.springblade.training.pojo.entity.Course;
                import org.springblade.training.service.ICourseService;
                public class CourseServiceImpl extends BaseServiceImpl<CourseMapper, Course> implements ICourseService {
                    public boolean saveCourse(String code) { return true; }
                    public boolean publish(Long id) { return true; }
                    public boolean save(Course c) { return true; }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(iface, impl));

        // 应检出 saveCourse / publish 两个自定义方法, 但 save 是父类方法不报
        long mismatches = issues.stream()
                .filter(i -> "CROSS-SERVICE-IMPL-IFACE-MISMATCH".equals(i.rule) && i.isError()).count();
        assertEquals(2, mismatches,
                "应检出 saveCourse + publish 两个接口未声明的方法, 不应误报父类 save; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        // 定位字段: sourceFilePath=ServiceImpl, contractFilePath=IService
        CrossFileValidator.ContractIssue first = issues.stream()
                .filter(i -> "CROSS-SERVICE-IMPL-IFACE-MISMATCH".equals(i.rule)).findFirst().orElseThrow();
        assertEquals("src/main/java/org/springblade/training/service/impl/CourseServiceImpl.java",
                first.sourceFilePath);
        assertEquals("src/main/java/org/springblade/training/service/ICourseService.java",
                first.contractFilePath);
    }

    @Test
    void shouldDetectImportClosureMissing() {
        // 复现 Course 真实问题: CourseServiceImpl import RecordMapper 但本次未生成 → 编译失败
        GeneratedFile impl = file(
                "src/main/java/org/springblade/training/service/impl/CourseServiceImpl.java",
                """
                package org.springblade.training.service.impl;
                import org.springblade.core.mp.base.BaseServiceImpl;
                import org.springblade.training.mapper.CourseMapper;
                import org.springblade.training.mapper.RecordMapper;
                import org.springblade.training.pojo.entity.Course;
                import org.springblade.training.service.ICourseService;
                public class CourseServiceImpl extends BaseServiceImpl<CourseMapper, Course> implements ICourseService {
                }
                """
        );
        GeneratedFile mapper = file(
                "src/main/java/org/springblade/training/mapper/CourseMapper.java",
                "package org.springblade.training.mapper; public interface CourseMapper {}"
        );
        GeneratedFile iface = file(
                "src/main/java/org/springblade/training/service/ICourseService.java",
                "package org.springblade.training.service; public interface ICourseService {}"
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(impl, mapper, iface));

        // RecordMapper 不在生成集合 → 应报 CROSS-IMPORT-CLOSURE-MISSING
        assertTrue(issues.stream().anyMatch(i -> "CROSS-IMPORT-CLOSURE-MISSING".equals(i.rule)
                        && i.isError() && i.message.contains("RecordMapper")),
                "应检出 RecordMapper 未在生成集合; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        // CourseMapper 在生成集合内, 不应误报
        long courseMapperMisses = issues.stream()
                .filter(i -> "CROSS-IMPORT-CLOSURE-MISSING".equals(i.rule) && i.message.contains("CourseMapper")
                        && !i.message.contains("RecordMapper"))
                .count();
        assertEquals(0, courseMapperMisses, "CourseMapper 已在集合中, 不应误报");
    }

    @Test
    void shouldDetectServiceImplParamTypeMismatch() {
        // 复现 Screw 真实问题: 接口 selectScrewPage(Query, ScrewQVO), 实现 selectScrewPage(IPage<Screw>, ScrewQVO)
        // 方法名+参数个数都对上(2), 但参数1类型不同 → @Override 编译失败。
        // 旧实现只查粗签名(N), 漏报。
        GeneratedFile iface = file(
                "src/main/java/org/springblade/screw/service/IScrewService.java",
                """
                package org.springblade.screw.service;
                import com.baomidou.mybatisplus.core.metadata.IPage;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.core.mp.support.Query;
                import org.springblade.screw.pojo.entity.Screw;
                import org.springblade.screw.pojo.vo.ScrewQVO;
                import org.springblade.screw.pojo.vo.ScrewVO;
                public interface IScrewService extends BaseService<Screw> {
                    IPage<ScrewVO> selectScrewPage(Query query, ScrewQVO qvo);
                }
                """
        );
        GeneratedFile impl = file(
                "src/main/java/org/springblade/screw/service/impl/ScrewServiceImpl.java",
                """
                package org.springblade.screw.service.impl;
                import com.baomidou.mybatisplus.core.metadata.IPage;
                import org.springblade.core.mp.base.BaseServiceImpl;
                import org.springblade.screw.mapper.ScrewMapper;
                import org.springblade.screw.pojo.entity.Screw;
                import org.springblade.screw.pojo.vo.ScrewQVO;
                import org.springblade.screw.pojo.vo.ScrewVO;
                import org.springblade.screw.service.IScrewService;
                public class ScrewServiceImpl extends BaseServiceImpl<ScrewMapper, Screw> implements IScrewService {
                    @Override
                    public IPage<ScrewVO> selectScrewPage(IPage<Screw> page, ScrewQVO qvo) { return null; }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(iface, impl));

        // 参数类型不一致 → 应报 CROSS-SERVICE-IMPL-IFACE-MISMATCH ERROR, message 提及"参数类型不一致"
        assertTrue(issues.stream().anyMatch(i -> "CROSS-SERVICE-IMPL-IFACE-MISMATCH".equals(i.rule)
                        && i.isError() && i.message.contains("参数类型不一致")),
                "应检出参数类型不一致(Query vs IPage<Screw>); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectInterfaceMethodsNotImplemented() {
        // 复现 TissueProduct 真实问题: ITissueProductService 声明 7 个自定义方法,
        // 但 TissueProductServiceImpl 是空类(implements 但不实现) → 编译失败。
        // 这是"接口堆方法、实现类空"的反向漂移, 旧实现只检"实现类有、接口没", 漏报此方向。
        GeneratedFile iface = file(
                "src/main/java/org/springblade/tissue/service/ITissueProductService.java",
                """
                package org.springblade.tissue.service;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.tissue.pojo.entity.TissueProduct;
                public interface ITissueProductService extends BaseService<TissueProduct> {
                    boolean saveProduct(Object ivo);
                    boolean updateProduct(Object uvo);
                    boolean removeProduct(java.util.List ids);
                }
                """
        );
        GeneratedFile impl = file(
                "src/main/java/org/springblade/tissue/service/impl/TissueProductServiceImpl.java",
                """
                package org.springblade.tissue.service.impl;
                import org.springblade.core.mp.base.BaseServiceImpl;
                import org.springblade.tissue.mapper.TissueProductMapper;
                import org.springblade.tissue.pojo.entity.TissueProduct;
                import org.springblade.tissue.service.ITissueProductService;
                public class TissueProductServiceImpl extends BaseServiceImpl<TissueProductMapper, TissueProduct> implements ITissueProductService {
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(iface, impl));

        // 接口声明 3 个方法, 实现类都没实现 → 应报 3 个 CROSS-SERVICE-IMPL-IFACE-MISMATCH ERROR
        long mismatches = issues.stream()
                .filter(i -> "CROSS-SERVICE-IMPL-IFACE-MISMATCH".equals(i.rule) && i.isError()
                        && i.message.contains("未实现该方法")).count();
        assertEquals(3, mismatches,
                "应检出接口声明的 3 个方法未被实现类实现; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        // 定位字段: sourceFilePath=ServiceImpl(需重生成补实现), contractFilePath=IService(契约源头)
        CrossFileValidator.ContractIssue first = issues.stream()
                .filter(i -> "CROSS-SERVICE-IMPL-IFACE-MISMATCH".equals(i.rule) && i.message.contains("未实现该方法"))
                .findFirst().orElseThrow();
        assertEquals("src/main/java/org/springblade/tissue/service/impl/TissueProductServiceImpl.java",
                first.sourceFilePath);
        assertEquals("src/main/java/org/springblade/tissue/service/ITissueProductService.java",
                first.contractFilePath);
    }

    @Test
    void shouldLocateEntityAndDdlPathsForAutoFix() {
        // plan 级 entity↔DDL 自动修复的前提: 检出的 ContractIssue 必须带 sourceFilePath(Entity) + contractFilePath(DDL),
        // 修复循环才能定位"要重生的 Entity"和"作为契约 context 的 DDL"。
        GeneratedFile ddl = file(
                "doc/sql/order/migration.sql",
                "CREATE TABLE `blade_order` (\n"
                        + "  `id` BIGINT NOT NULL AUTO_INCREMENT,\n"
                        + "  `total_amount` DECIMAL(18,2) DEFAULT NULL,\n"
                        + "  `tenant_id` VARCHAR(12) DEFAULT '000000',\n"
                        + "  PRIMARY KEY (`id`)\n"
                        + ");"
        );
        GeneratedFile entity = file(
                "blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/entity/Order.java",
                """
                package org.springblade.order.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import org.springblade.core.mp.base.BaseEntity;
                @TableName("blade_order")
                public class Order extends BaseEntity {
                    private String totalAmount;
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(ddl, entity));

        // 应检出: total_amount 类型不符(DECIMAL vs String→BigDecimal) + tenant_id 多租户丢失
        List<CrossFileValidator.ContractIssue> ddlErrors = issues.stream()
                .filter(i -> i.isError() && (i.rule.startsWith("ENTITY-DDL-TYPE") || i.rule.startsWith("ENTITY-DDL-TENANT")))
                .toList();
        assertFalse(ddlErrors.isEmpty(), "应检出 entity↔DDL 不一致");
        // 每条 ERROR 都必须带定位: source=Entity, contract=DDL
        for (CrossFileValidator.ContractIssue err : ddlErrors) {
            assertEquals("blade-service-api/blade-order-api/src/main/java/org/springblade/order/pojo/entity/Order.java",
                    err.sourceFilePath, "sourceFilePath 应指向需修复的 Entity: " + err.rule);
            assertEquals("doc/sql/order/migration.sql",
                    err.contractFilePath, "contractFilePath 应指向契约源头 DDL: " + err.rule);
        }
    }

    @Test
    void shouldDetectVoEntityFieldNameMismatch() {
        // 复现 specialperiod 真实问题: Entity 用 periodName/periodType/isEnabled,
        // VO 改名 name/type/enabled + 凭空 weekDays/priority -> BeanUtil.copy 丢字段, CRUD 断裂
        GeneratedFile entity = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/entity/SpecialPeriod.java",
                """
                package org.springblade.specialperiod.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                @TableName("blade_special_period")
                public class SpecialPeriod {
                    private String periodName;
                    private Integer periodType;
                    private Integer isEnabled;
                }
                """
        );
        GeneratedFile vo = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/vo/SpecialPeriodVO.java",
                """
                package org.springblade.specialperiod.pojo.vo;
                public class SpecialPeriodVO {
                    private Long id;
                    private String name;
                    private Integer type;
                    private Integer enabled;
                    private String weekDays;
                    private Integer priority;
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, vo));

        assertTrue(issues.stream().anyMatch(i -> "VO-ENTITY-FIELD-MISMATCH".equals(i.rule) && i.isError()),
                "应检出 VO 字段与 Entity 不同名/凭空(B1/B3); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        // 定位: source=VO(需重生成), contract=Entity(契约源头)
        CrossFileValidator.ContractIssue mismatch = issues.stream()
                .filter(i -> "VO-ENTITY-FIELD-MISMATCH".equals(i.rule)).findFirst().orElseThrow();
        assertEquals("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/vo/SpecialPeriodVO.java",
                mismatch.sourceFilePath, "sourceFilePath 应指向需修复的 VO");
        assertEquals("blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/entity/SpecialPeriod.java",
                mismatch.contractFilePath, "contractFilePath 应指向契约源头 Entity");
    }

    @Test
    void shouldDetectVoEntityFieldTypeMismatch() {
        // 复现 specialperiod: Entity startDate 是 Date, IVO startDate 是 LocalDate -> 跨类型拷不动
        GeneratedFile entity = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/entity/SpecialPeriod.java",
                """
                package org.springblade.specialperiod.pojo.entity;
                import java.util.Date;
                import com.baomidou.mybatisplus.annotation.TableName;
                @TableName("blade_special_period")
                public class SpecialPeriod {
                    private String periodName;
                    private Date startDate;
                    private Date endDate;
                }
                """
        );
        GeneratedFile ivo = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/vo/SpecialPeriodIVO.java",
                """
                package org.springblade.specialperiod.pojo.vo;
                import java.time.LocalDate;
                public class SpecialPeriodIVO {
                    private String periodName;
                    private LocalDate startDate;
                    private LocalDate endDate;
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, ivo));

        assertTrue(issues.stream().anyMatch(i -> "VO-ENTITY-FIELD-TYPE-MISMATCH".equals(i.rule) && i.isError()
                        && i.message.contains("startDate")),
                "应检出 startDate 类型不一致(Date vs LocalDate, B2); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectResultMapReturnTypeMismatch() {
        // 复现 specialperiod: selectActivePeriods 返回 List<SpecialPeriodVO>, 但 resultMap type 指向 Entity
        GeneratedFile mapper = file(
                "src/main/java/org/springblade/specialperiod/mapper/SpecialPeriodMapper.java",
                """
                package org.springblade.specialperiod.mapper;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.metadata.IPage;
                import org.springblade.specialperiod.pojo.entity.SpecialPeriod;
                import org.springblade.specialperiod.pojo.vo.SpecialPeriodVO;
                import java.util.List;
                public interface SpecialPeriodMapper extends BaseMapper<SpecialPeriod> {
                    List<SpecialPeriodVO> selectActivePeriods();
                }
                """
        );
        GeneratedFile xml = file(
                "src/main/java/org/springblade/specialperiod/mapper/SpecialPeriodMapper.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="org.springblade.specialperiod.mapper.SpecialPeriodMapper">
                    <resultMap id="rm" type="org.springblade.specialperiod.pojo.entity.SpecialPeriod">
                        <id column="id" property="id"/>
                    </resultMap>
                    <select id="selectActivePeriods" resultMap="rm">
                        SELECT * FROM blade_special_period
                    </select>
                </mapper>
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(mapper, xml));

        assertTrue(issues.stream().anyMatch(i -> "MAPPER-RESULTMAP-TYPE-MISMATCH".equals(i.rule) && i.isError()),
                "应检出 resultMap type=Entity 与方法返回 List<VO> 不一致(B8); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectFeignImplMissing() {
        // 复现 specialperiod: ISpecialPeriodClient 定义了但无 SpecialPeriodClient 实现类 -> Feign 调用 404
        GeneratedFile feign = file(
                "src/main/java/org/springblade/specialperiod/feign/ISpecialPeriodClient.java",
                """
                package org.springblade.specialperiod.feign;
                import org.springframework.cloud.openfeign.FeignClient;
                @FeignClient(value = "blade-specialperiod-service")
                public interface ISpecialPeriodClient {
                    String API_PREFIX = "/feign/client/specialperiod";
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(feign));

        assertTrue(issues.stream().anyMatch(i -> "FEIGN-IMPL-MISSING".equals(i.rule)),
                "应检出 Feign 接口无实现类(B5); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectListMapperPageInconsistent() {
        // 复现 specialperiod: Mapper 有 selectSpecialPeriodPage(IPage, QVO) 但 Controller /list 没调 -> QVO 区间字段死代码
        GeneratedFile mapper = file(
                "src/main/java/org/springblade/specialperiod/mapper/SpecialPeriodMapper.java",
                """
                package org.springblade.specialperiod.mapper;
                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import com.baomidou.mybatisplus.core.metadata.IPage;
                import org.springblade.specialperiod.pojo.entity.SpecialPeriod;
                import org.springblade.specialperiod.pojo.vo.SpecialPeriodVO;
                import org.springblade.specialperiod.pojo.vo.SpecialPeriodQVO;
                import java.util.List;
                public interface SpecialPeriodMapper extends BaseMapper<SpecialPeriod> {
                    List<SpecialPeriodVO> selectSpecialPeriodPage(IPage<SpecialPeriodVO> page, SpecialPeriodQVO qvo);
                }
                """
        );
        GeneratedFile controller = file(
                "src/main/java/org/springblade/specialperiod/controller/SpecialPeriodController.java",
                """
                package org.springblade.specialperiod.controller;
                public class SpecialPeriodController {
                    @GetMapping("/list")
                    public Object list() {
                        return null;
                    }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(mapper, controller));

        assertTrue(issues.stream().anyMatch(i -> "LIST-MAPPER-PAGE-INCONSISTENT".equals(i.rule)),
                "应检出 /list 未调用 Mapper 自定义分页方法(B7); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectControllerSkipServiceValidation() {
        // 复现 specialperiod: IService 有 submit(含校验) 但 Controller /save 调基类 save -> 校验成死代码
        GeneratedFile service = file(
                "src/main/java/org/springblade/specialperiod/service/ISpecialPeriodService.java",
                """
                package org.springblade.specialperiod.service;
                import org.springblade.core.mp.base.BaseService;
                import org.springblade.specialperiod.pojo.entity.SpecialPeriod;
                public interface ISpecialPeriodService extends BaseService<SpecialPeriod> {
                    boolean submit(SpecialPeriod specialPeriod);
                }
                """
        );
        GeneratedFile controller = file(
                "src/main/java/org/springblade/specialperiod/controller/SpecialPeriodController.java",
                """
                package org.springblade.specialperiod.controller;
                import org.springblade.specialperiod.service.ISpecialPeriodService;
                public class SpecialPeriodController {
                    private final ISpecialPeriodService specialPeriodService;
                    public SpecialPeriodController(ISpecialPeriodService s) { this.specialPeriodService = s; }
                    @PostMapping("/save")
                    public Object save() { return specialPeriodService.save(null); }
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(service, controller));

        assertTrue(issues.stream().anyMatch(i -> "CONTROLLER-SKIP-SERVICE-VALIDATION".equals(i.rule)),
                "应检出 Controller /save 调基类 save 绕过 submit 校验(B4); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldPassWhenVoAlignsWithEntity() {
        // 反例(permit/WorkPermit 风格): VO 字段与 Entity 同名同类型 + 展示衍生字段(permitTypeName)
        // -> 不应报 VO-ENTITY-FIELD-*
        GeneratedFile entity = file(
                "blade-service-api/blade-permit-api/src/main/java/org/springblade/permit/pojo/entity/WorkPermit.java",
                """
                package org.springblade.permit.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                @TableName("blade_work_permit")
                public class WorkPermit {
                    private String permitNo;
                    private Integer permitType;
                    private Integer status;
                }
                """
        );
        GeneratedFile vo = file(
                "blade-service-api/blade-permit-api/src/main/java/org/springblade/permit/pojo/vo/WorkPermitVO.java",
                """
                package org.springblade.permit.pojo.vo;
                public class WorkPermitVO {
                    private Long id;
                    private String permitNo;
                    private Integer permitType;
                    private Integer status;
                    private String permitTypeName;
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, vo));

        long voIssues = issues.stream().filter(i -> i.rule != null && i.rule.startsWith("VO-ENTITY-FIELD")).count();
        assertEquals(0, voIssues,
                "VO 与 Entity 对齐时不应报 VO-ENTITY-FIELD 问题; 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
    }

    @Test
    void shouldDetectVoImportNotInGenerationSet() {
        // 复现 specialperiod 真实问题: Controller import SpecialPeriodIVO(pojo.vo 包) 但 IVO 未生成
        GeneratedFile entity = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/entity/SpecialPeriod.java",
                """
                package org.springblade.specialperiod.pojo.entity;
                public class SpecialPeriod extends BaseEntity {
                    private String periodName;
                }
                """
        );
        GeneratedFile vo = file(
                "blade-service-api/blade-specialperiod-api/src/main/java/org/springblade/specialperiod/pojo/vo/SpecialPeriodVO.java",
                "package org.springblade.specialperiod.pojo.vo; public class SpecialPeriodVO {}"
        );
        GeneratedFile controller = file(
                "blade-service/blade-specialperiod/src/main/java/org/springblade/specialperiod/controller/SpecialPeriodController.java",
                """
                package org.springblade.specialperiod.controller;
                import org.springblade.specialperiod.pojo.vo.SpecialPeriodIVO;
                import org.springblade.specialperiod.pojo.vo.SpecialPeriodVO;
                public class SpecialPeriodController {
                    public void save(SpecialPeriodIVO ivo) {}
                }
                """
        );

        List<CrossFileValidator.ContractIssue> issues = validator.validate(List.of(entity, vo, controller), true);

        // checkVoImportClosure 应检出 Controller import 未生成的 SpecialPeriodIVO
        assertTrue(issues.stream().anyMatch(i -> "CROSS-IMPORT-CLOSURE-MISSING".equals(i.rule)
                        && i.message.contains("SpecialPeriodIVO")),
                "应检出 Controller import 未生成的 SpecialPeriodIVO(VO 类缺失); 实际: "
                        + issues.stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse(""));
        // SpecialPeriodVO 在集合内, 不应误报
        long voMisses = issues.stream()
                .filter(i -> "CROSS-IMPORT-CLOSURE-MISSING".equals(i.rule) && i.message.contains("SpecialPeriodVO")
                        && !i.message.contains("IVO"))
                .count();
        assertEquals(0, voMisses, "SpecialPeriodVO 已在集合, 不应误报");
    }

    private GeneratedFile file(String path, String content) {
        return new GeneratedFile(TaskType.OTHER, path, content, "CREATED");
    }
}
