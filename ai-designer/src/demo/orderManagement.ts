/**
 * 内置「订单管理」完整示例
 *
 * 一键填入完整的:
 * - 项目名 + 8 个拖入模块(Entity/API/Excel/Feign/Job)
 * - 已生成的主方案(Markdown)
 * - 已拆分的 5 个子方案 (DDL → Entity/VO → Mapper → Service → Controller)
 *
 * 用户点击「📦 载入订单示例」后可直接 review/拆分/传输,无需等待 LLM。
 * 也适合在 ANTHROPIC 接口未配置时演示完整的端到端流程。
 */

import type { DraggedModule, MasterPlan, SubPlan } from '../types/plan';

export interface DemoSeed {
  projectName: string;
  rawRequirements: string;
  modules: Omit<DraggedModule, 'id'>[];
  masterPlan: Omit<MasterPlan, 'id' | 'projectId'>;
  subPlans: Omit<SubPlan, 'id' | 'masterPlanId'>[];
}

const MASTER_PLAN_MD = `# 订单管理模块开发方案

> 目标项目: blade_hgsjy (BladeX 4.1.0)
> 模块: blade-order / blade-order-api

## 1. 需求分析

支持订单全生命周期管理:
- **基础 CRUD**: 订单创建/查询/修改/逻辑删除
- **业务状态机**: PENDING(待支付) → PAID(已支付) → SHIPPED(已发货) → COMPLETED(已完成) / CANCELLED(已取消)
- **Excel 导出**: 按筛选条件批量导出订单数据
- **远程调用**: 通过 Feign 查询用户中心获取客户档案

## 2. 模块结构

\`\`\`
blade-service-api/
└── blade-order-api/                # 共享 Feign 接口 + DTO
    └── src/main/java/org/springblade/order/
        ├── feign/IOrderClient.java
        └── dto/OrderDTO.java
blade-service/
└── blade-order/                    # 实际服务
    └── src/main/java/org/springblade/order/
        ├── OrderApplication.java
        ├── entity/Order.java
        ├── vo/{OrderQVO,OrderIVO,OrderUVO,OrderVO,OrderEVO}.java
        ├── wrapper/OrderWrapper.java
        ├── mapper/OrderMapper.java
        ├── service/IOrderService.java
        ├── service/impl/OrderServiceImpl.java
        └── controller/OrderController.java
\`\`\`

## 3. 数据库 DDL

\`\`\`sql
CREATE TABLE blade_order (
    id BIGINT PRIMARY KEY COMMENT '主键(雪花算法)',
    order_no VARCHAR(50) NOT NULL UNIQUE COMMENT '订单编号',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    customer_name VARCHAR(100) COMMENT '客户名称(冗余)',
    total_amount DECIMAL(18,2) NOT NULL COMMENT '订单总额',
    paid_amount DECIMAL(18,2) DEFAULT 0 COMMENT '已支付金额',
    order_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '订单状态',
    pay_time DATETIME COMMENT '支付时间',
    ship_time DATETIME COMMENT '发货时间',
    complete_time DATETIME COMMENT '完成时间',
    remark VARCHAR(500) COMMENT '备注',
    -- BaseEntity 标准字段
    create_user BIGINT COMMENT '创建人',
    create_time DATETIME COMMENT '创建时间',
    update_user BIGINT COMMENT '修改人',
    update_time DATETIME COMMENT '修改时间',
    status INT DEFAULT 1 COMMENT '业务状态',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_order_no (order_no),
    INDEX idx_customer_id (customer_id),
    INDEX idx_order_status (order_status),
    INDEX idx_create_time (create_time)
) COMMENT '订单表';
\`\`\`

## 4. Entity 定义

\`\`\`java
@Data
@TableName("blade_order")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单实体")
public class Order extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "客户ID")
    private Long customerId;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "订单总额")
    private BigDecimal totalAmount;

    @Schema(description = "已支付金额")
    private BigDecimal paidAmount;

    @Schema(description = "订单状态")
    private String orderStatus;

    @Schema(description = "支付/发货/完成时间")
    private LocalDateTime payTime;
    private LocalDateTime shipTime;
    private LocalDateTime completeTime;

    @Schema(description = "备注")
    private String remark;
}
\`\`\`

## 5. VO 类型

| 类型 | 类名 | 用途 |
|------|------|------|
| QVO | OrderQVO | 列表查询: orderNo/customerName/orderStatus + 日期区间 |
| IVO | OrderIVO | 新增: 必填校验 customerId/totalAmount |
| UVO | OrderUVO | 修改: 必填 id, 可更新备注/customer_name |
| VO  | OrderVO  | 输出: id 序列化为 String, orderStatus 转中文标签 |
| EVO | OrderEVO | Excel 导出: @ExcelProperty 标记列 |

## 6. Mapper

\`\`\`java
public interface OrderMapper extends BaseMapper<Order> {

    /** 自定义 SQL: 按客户 ID 统计已完成订单总额 */
    @Select("SELECT IFNULL(SUM(total_amount), 0) FROM blade_order " +
            "WHERE customer_id = #{customerId} AND order_status = 'COMPLETED' AND is_deleted = 0")
    BigDecimal sumCompletedAmount(@Param("customerId") Long customerId);
}
\`\`\`

## 7. Service 层

\`\`\`java
public interface IOrderService extends BaseService<Order> {
    boolean pay(Long id, BigDecimal amount);
    boolean ship(Long id);
    boolean complete(Long id);
    boolean cancel(Long id);
    List<OrderEVO> exportAll(Wrapper<Order> queryWrapper);
}

@Service
@AllArgsConstructor
public class OrderServiceImpl extends BaseServiceImpl<OrderMapper, Order> implements IOrderService {

    private final IUserClient userClient;  // Feign 调用用户中心

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pay(Long id, BigDecimal amount) {
        Order order = getById(id);
        if (order == null) throw new ServiceException("订单不存在");
        if (!"PENDING".equals(order.getOrderStatus())) {
            throw new ServiceException("订单状态不允许支付");
        }
        order.setOrderStatus("PAID");
        order.setPaidAmount(amount);
        order.setPayTime(LocalDateTime.now());
        return updateById(order);
    }
    // ship/complete/cancel/exportAll 类似实现...
}
\`\`\`

## 8. Controller 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | /order/detail            | 详情 |
| GET  | /order/list              | 分页列表 |
| POST | /order/save              | 新增 |
| POST | /order/update            | 修改 |
| POST | /order/remove            | 逻辑删除 (deleteLogic) |
| POST | /order/pay?id=&amount=   | 业务: 支付 |
| POST | /order/ship?id=          | 业务: 发货 |
| POST | /order/complete?id=      | 业务: 完成 |
| POST | /order/cancel?id=        | 业务: 取消 |
| GET  | /order/export            | Excel 导出 |

类级别约束:
\`\`\`java
@RestController
@AllArgsConstructor
@RequestMapping("/order")
@Tag(name = "订单管理", description = "订单管理接口")
public class OrderController extends BladeController {
    private final IOrderService orderService;
    // ...
}
\`\`\`

## 9. 关键业务约束

- **状态机**: 状态扭转必须经过 Service 校验,禁止前端直接更新 \`orderStatus\`
- **金额**: BigDecimal,禁止 float/double
- **幂等**: 支付/发货/完成接口需校验当前状态,不允许重复操作
- **审计**: 所有状态扭转写入 \`order_status\` + 对应时间字段

## 10. 实现顺序

\`\`\`
子方案 1: 数据库 DDL
   ↓
子方案 2: Entity + 5 个 VO 类
   ↓
子方案 3: Mapper + IOrderService 接口 + OrderServiceImpl
   ↓
子方案 4: OrderWrapper + OrderController (5 标准 + 4 业务)
   ↓
子方案 5: OrderEVO + Excel 导出 + Feign 客户端 IOrderClient
\`\`\`
`;

export const ORDER_MANAGEMENT_DEMO: DemoSeed = {
  projectName: '订单管理示例',
  rawRequirements:
    '我需要一个完整的订单管理模块,支持订单 CRUD + 状态机(待支付/已支付/已发货/已完成/已取消) + Excel 导出 + 通过 Feign 调用用户中心获取客户档案。订单需要包含订单编号、客户、金额、状态、备注等字段。',
  modules: [
    {
      type: 'ENTITY',
      name: '订单实体',
      icon: '📦',
      color: '#1890ff',
      config: {
        tableName: 'blade_order',
        moduleName: 'order',
        extendBaseEntity: true,
        needVO: true,
        needExcel: true,
        fields: [
          { name: 'orderNo', type: 'VARCHAR(50)', comment: '订单编号', nullable: false, length: 50 },
          { name: 'customerId', type: 'BIGINT', comment: '客户ID', nullable: false },
          { name: 'customerName', type: 'VARCHAR(100)', comment: '客户名称', nullable: true, length: 100 },
          { name: 'totalAmount', type: 'DECIMAL(18,2)', comment: '订单总额', nullable: false },
          { name: 'paidAmount', type: 'DECIMAL(18,2)', comment: '已支付金额', nullable: true },
          { name: 'orderStatus', type: 'VARCHAR(20)', comment: '订单状态', nullable: false, length: 20 },
          { name: 'remark', type: 'VARCHAR(500)', comment: '备注', nullable: true, length: 500 },
        ],
      },
    },
    {
      type: 'API',
      name: '订单 REST API',
      icon: '🔌',
      color: '#52c41a',
      config: {
        pathPrefix: 'order',
        needAuth: true,
        needLog: true,
        endpoints: [
          { method: 'GET', path: '/detail', summary: '详情', params: 'id', returnType: 'R<OrderVO>' },
          { method: 'GET', path: '/list', summary: '分页列表', params: 'Map+OrderQVO', returnType: 'R<IPage<OrderVO>>' },
          { method: 'POST', path: '/save', summary: '新增', params: '@Valid OrderIVO', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/update', summary: '修改', params: '@Valid OrderUVO', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/remove', summary: '逻辑删除', params: 'ids', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/pay', summary: '支付订单', params: 'id, amount', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/ship', summary: '发货', params: 'id', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/complete', summary: '完成', params: 'id', returnType: 'R<Boolean>' },
          { method: 'POST', path: '/cancel', summary: '取消', params: 'id', returnType: 'R<Boolean>' },
        ],
      },
    },
    {
      type: 'EXCEL',
      name: '订单导出',
      icon: '📊',
      color: '#2f54eb',
      config: {
        entityName: 'Order',
        needImport: false,
        needExport: true,
        needTemplate: false,
      },
    },
    {
      type: 'FEIGN',
      name: '用户中心调用',
      icon: '🔗',
      color: '#13c2c2',
      config: {
        targetService: 'blade-user',
        apiPrefix: '/client/user',
        methods: [
          { name: 'getUserById', path: '/get', method: 'GET', params: 'Long id', returnType: 'R<UserDTO>' },
        ],
      },
    },
  ],
  masterPlan: {
    version: 1,
    planContent: MASTER_PLAN_MD,
    reviewedContent: MASTER_PLAN_MD,
    status: 'SUBPLANS_REVIEWED',
    llmModel: 'demo-seed',
    llmTokensUsed: 0,
    reviewChangeLog: [
      {
        what: '统一订单状态字段类型',
        why: 'BladeX 规范要求枚举类型字段优先使用字符串,前端国际化更友好',
        before: '`order_status INT`',
        after: '`order_status VARCHAR(20) DEFAULT \'PENDING\'`',
      },
      {
        what: '为业务接口补充事务声明',
        why: 'pay/ship/complete/cancel 都涉及多字段更新,必须保证原子性',
        before: '无 @Transactional',
        after: '@Transactional(rollbackFor = Exception.class)',
      },
    ],
  },
  subPlans: [
    {
      index: 1,
      title: '数据库 DDL',
      planContent: `## 子方案 1: 数据库 DDL

**目标**: 在 \`ai_workflow\` 库执行订单表建表语句。

**文件**: \`blade-service/blade-order/src/main/resources/db/V20260622__create_blade_order.sql\`

\`\`\`sql
${MASTER_PLAN_MD.match(/```sql\n([\s\S]*?)\n```/)?.[1] ?? ''}
\`\`\`

**校验**:
- BaseEntity 标准字段齐全 (create_user/create_time/update_user/update_time/status/is_deleted)
- order_no UNIQUE 约束
- 4 个常用查询索引 (order_no/customer_id/order_status/create_time)`,
      reviewedContent: '',
      prerequisites: [],
      status: 'CONFIRMED',
    },
    {
      index: 2,
      title: 'Entity 与 VO 类',
      planContent: `## 子方案 2: Entity 与 VO 类

**目标**: 创建 \`Order\` Entity 以及 \`OrderQVO/OrderIVO/OrderUVO/OrderVO/OrderEVO\` 五个 VO。

**文件**:
- \`blade-order/src/main/java/org/springblade/order/entity/Order.java\`
- \`blade-order/src/main/java/org/springblade/order/vo/OrderQVO.java\`
- \`blade-order/src/main/java/org/springblade/order/vo/OrderIVO.java\`
- \`blade-order/src/main/java/org/springblade/order/vo/OrderUVO.java\`
- \`blade-order/src/main/java/org/springblade/order/vo/OrderVO.java\`
- \`blade-order/src/main/java/org/springblade/order/vo/OrderEVO.java\`

**关键约束**:
- Entity extends BaseEntity, 添加 @TableName("blade_order"), @EqualsAndHashCode(callSuper=true)
- 金额字段使用 BigDecimal (禁止 double/float)
- VO 中 id 用 @JsonSerialize(ToStringSerializer.class)
- IVO/UVO 必填字段加 @NotNull/@NotBlank
- UVO 必须含 id 字段并 @NotNull`,
      reviewedContent: '',
      prerequisites: ['__SUBPLAN_0__'],
      status: 'CONFIRMED',
    },
    {
      index: 3,
      title: 'Mapper 与 Service 层',
      planContent: `## 子方案 3: Mapper 与 Service 层

**目标**: 实现数据访问 + 业务逻辑(包含状态机)。

**文件**:
- \`blade-order/src/main/java/org/springblade/order/mapper/OrderMapper.java\`
- \`blade-order/src/main/java/org/springblade/order/mapper/OrderMapper.xml\`
- \`blade-order/src/main/java/org/springblade/order/service/IOrderService.java\`
- \`blade-order/src/main/java/org/springblade/order/service/impl/OrderServiceImpl.java\`

**关键约束**:
- IOrderService extends BaseService<Order>
- OrderServiceImpl extends BaseServiceImpl<OrderMapper, Order> implements IOrderService
- pay/ship/complete/cancel 加 @Transactional(rollbackFor = Exception.class)
- 状态校验失败抛 ServiceException("...")
- 自定义查询用 Wrappers.<Order>lambdaQuery()`,
      reviewedContent: '',
      prerequisites: ['__SUBPLAN_1__'],
      status: 'PENDING',
    },
    {
      index: 4,
      title: 'Controller 与 Wrapper',
      planContent: `## 子方案 4: Controller 与 Wrapper

**目标**: REST API 端点 (5 个标准 CRUD + 4 个业务接口) + Wrapper 转换。

**文件**:
- \`blade-order/src/main/java/org/springblade/order/wrapper/OrderWrapper.java\`
- \`blade-order/src/main/java/org/springblade/order/controller/OrderController.java\`

**关键约束**:
- Controller extends BladeController, 类级 @AllArgsConstructor, **禁用 @Autowired**
- 所有方法返回 R<T>
- /remove 使用 \`service.deleteLogic(Func.toLongList(ids))\`
- /list 参数: \`@ApiIgnore Map<String,Object> params, OrderQVO query, Query query\`
- /save 参数: \`@Valid @RequestBody OrderIVO\`
- 业务接口使用 @PostMapping + @RequestParam`,
      reviewedContent: '',
      prerequisites: ['__SUBPLAN_2__'],
      status: 'PENDING',
    },
    {
      index: 5,
      title: 'Excel 导出 + Feign 客户端',
      planContent: `## 子方案 5: Excel 导出 + Feign 客户端

**目标**: 订单数据 Excel 导出 + 通过 Feign 调用用户中心。

**文件**:
- \`blade-order/src/main/java/org/springblade/order/excel/OrderExcel.java\`
- \`blade-order-api/src/main/java/org/springblade/order/feign/IUserClient.java\`
- \`blade-order/src/main/java/org/springblade/order/feign/UserClientFallback.java\`

**关键约束**:
- OrderEVO 字段加 @ExcelProperty 注解
- Controller /export 用 ExcelUtil.export(response, name, OrderEVO.class, data)
- IUserClient 加 @FeignClient(value = "blade-user", fallback = UserClientFallback.class)
- @RequestParam 必须指定 value 属性
- Fallback 类加 @Component`,
      reviewedContent: '',
      prerequisites: ['__SUBPLAN_3__'],
      status: 'PENDING',
    },
  ],
};
