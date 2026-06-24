# BladeX 4.1.0 Excel 导入导出指南

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。

## 概述

BladeX 使用 **Alibaba EasyExcel** 进行 Excel 导入导出。核心工具类 `ExcelUtil` 在 `blade-common` 模块中。导入导出涉及三个组件：**Excel 模型类（EVO）**、**Importer 导入策略**、**Controller 端点**。

---

## Excel 模型类（EVO）

文件位置：`blade-service/blade-xxx/src/main/java/org/springblade/xxx/excel/XxxExcel.java`

```java
package org.springblade.order.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@ColumnWidth(25)           // 默认列宽
@HeadRowHeight(20)         // 表头行高
@ContentRowHeight(18)      // 内容行高
public class OrderExcel implements Serializable {
    private static final long serialVersionUID = 1L;

    @ColumnWidth(15)
    @ExcelProperty("订单编号")
    private String orderNo;

    @ExcelProperty("客户名称")
    private String customerName;

    @ColumnWidth(20)
    @ExcelProperty("金额")
    private String amount;  // 注意：EVO 中金额用 String，避免精度问题

    @ExcelIgnore             // 不导出
    @ExcelProperty("状态码")  // @ExcelIgnore 优先，不会出现在 Excel 中
    private String statusCode;

    @ExcelProperty("状态")
    private String statusName;  // 只导翻译后的名称

    @ColumnWidth(20)
    @ExcelProperty("创建时间")
    private Date createTime;
}
```

来源：`UserExcel.java`（blade-system/excel/）

### EVO 常用注解

| 注解 | 说明 |
|------|------|
| `@ExcelProperty("列名")` | 标记 Excel 列，值为表头名称 |
| `@ExcelIgnore` | 排除该字段，不导出也不导入 |
| `@ColumnWidth(N)` | 列宽（只在导出时生效） |
| `@HeadRowHeight(N)` | 表头行高（类级别） |
| `@ContentRowHeight(N)` | 内容行高（类级别） |

### EVO 字段类型注意事项

| 场景 | 建议类型 | 说明 |
|------|---------|------|
| 普通文本 | `String` | 直接用 String |
| 金额/数字 | `String` | 避免科学计数法和精度问题 |
| 日期 | `Date` | EasyExcel 自动处理格式 |
| ID/编码 | `String` | 避免长数字被截断 |
| 隐藏字段 | `String` + `@ExcelIgnore` | 不导出但保留字段 |

> 来源：`UserExcel` 中 `roleId`、`deptId`、`postId` 都加了 `@ExcelIgnore`（内部 ID），同时有对应的 `roleName`、`deptName`、`postName`（显示名称）。

---

## ExcelImporter 导入策略

文件位置：`blade-service/blade-xxx/src/main/java/org/springblade/xxx/excel/XxxImporter.java`

```java
package org.springblade.order.excel;

import lombok.RequiredArgsConstructor;
import org.springblade.core.excel.support.ExcelImporter;
import org.springblade.order.service.IOrderService;

import java.util.List;

@RequiredArgsConstructor
public class OrderImporter implements ExcelImporter<OrderExcel> {

    private final IOrderService service;
    private final Boolean isCovered;    // 是否覆盖已有数据

    @Override
    public void save(List<OrderExcel> data) {
        service.importOrder(data, isCovered);
    }
}
```

来源：`UserImporter.java`（blade-system/excel/）

### ExcelImporter 接口

```java
// BladeX 框架提供（org.springblade.core.excel.support.ExcelImporter）
@FunctionalInterface
public interface ExcelImporter<T> {
    void save(List<T> data);
}
```

### Importer 要点

| 要素 | 说明 |
|------|------|
| `@RequiredArgsConstructor` | Lombok 构造器注入 Service |
| `implements ExcelImporter<ExcelClass>` | 泛型指定 Excel 模型类 |
| `isCovered` | 是否覆盖已有数据（通常由前端传入） |
| `save()` | 委托给 Service 层处理实际导入逻辑 |

---

## ExcelUtil 工具类

位于 `org.springblade.core.excel.util.ExcelUtil`（注意：**不是** `blade-common` 中旧版的 `ExcelUtil`）。

### 导入方法

```java
// 方式一：简单读取（读取第一个 sheet）
List<OrderExcel> list = ExcelUtil.read(file, OrderExcel.class);

// 方式二：指定 sheet 序号
List<OrderExcel> list = ExcelUtil.read(file, 0, OrderExcel.class);

// 方式三：指定 sheet 序号和表头行号
List<OrderExcel> list = ExcelUtil.read(file, 0, 1, OrderExcel.class);

// 方式四：使用 Importer 策略（推荐，支持批量处理）
ExcelUtil.save(file, importer, OrderExcel.class);
```

### 导出方法

```java
// 方式一：简单导出（自动生成文件名）
ExcelUtil.export(response, list, OrderExcel.class);

// 方式二：指定文件名和 Sheet 名
ExcelUtil.export(response, "订单列表", "订单数据", list, OrderExcel.class);
```

---

## Controller 端点

### 导出端点

```java
@GetMapping("/export-order")
@ApiOperationSupport(order = 10)
@Operation(summary = "导出订单")
public void exportOrder(@RequestParam Map<String, Object> params,
                         HttpServletResponse response) {
    // 1. 构建查询条件
    QueryWrapper<Order> queryWrapper = Condition.getQueryWrapper(params, Order.class);

    // 2. 查询数据并转换为 Excel 模型
    List<OrderExcel> list = orderService.exportOrder(queryWrapper);

    // 3. 导出
    ExcelUtil.export(response, "订单列表", "订单数据", list, OrderExcel.class);
}
```

来源：`UserController` 中的 `exportUser` 模式。

### 导入端点

```java
@PostMapping("/import-order")
@ApiOperationSupport(order = 11)
@Operation(summary = "导入订单")
public R importOrder(MultipartFile file,
                      @RequestParam(defaultValue = "0") Integer isCovered) {
    // 1. 创建 Importer
    OrderImporter importer = new OrderImporter(orderService, isCovered == 1);

    // 2. 执行导入
    ExcelUtil.save(file, importer, OrderExcel.class);

    return R.success("操作成功");
}
```

来源：`UserController` 中的 `importUser` 模式。

### 导出模板端点

```java
@GetMapping("/export-template")
@ApiOperationSupport(order = 12)
@Operation(summary = "导出模板")
public void exportTemplate(HttpServletResponse response) {
    // 导出空模板（只包含表头）
    ExcelUtil.export(response, "订单导入模板", "订单数据",
        new ArrayList<>(), OrderExcel.class);
}
```

---

## Service 层导入导出逻辑

### 导出逻辑

```java
// Service 实现
@Override
public List<OrderExcel> exportOrder(QueryWrapper<Order> queryWrapper) {
    // 1. 查询数据
    List<Order> list = this.list(queryWrapper);

    // 2. 转换为 Excel 模型（解析 ID 为显示名称）
    return list.stream().map(order -> {
        OrderExcel excel = new OrderExcel();
        excel.setOrderNo(order.getOrderNo());
        excel.setCustomerName(order.getCustomerName());
        excel.setAmount(order.getAmount().toString());
        // 解析状态码为显示名称
        excel.setStatusName(DictCache.getValue("order_status", order.getStatus()));
        return excel;
    }).collect(Collectors.toList());
}
```

来源：`UserServiceImpl.exportUser()` 模式。

### 导入逻辑

```java
// Service 实现
@Override
@Transactional(rollbackFor = Exception.class)
public void importOrder(List<OrderExcel> data, boolean isCovered) {
    for (OrderExcel excel : data) {
        // 1. 校验每一行
        if (Func.isEmpty(excel.getOrderNo())) {
            throw new ServiceException("订单编号不能为空");
        }

        // 2. 检查是否已存在
        Order existing = this.getOne(
            Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, excel.getOrderNo())
        );

        if (existing != null) {
            if (isCovered) {
                // 覆盖模式：更新
                existing.setCustomerName(excel.getCustomerName());
                existing.setAmount(new BigDecimal(excel.getAmount()));
                this.updateById(existing);
            }
            // 非覆盖模式：跳过
        } else {
            // 新增
            Order order = new Order();
            order.setOrderNo(excel.getOrderNo());
            order.setCustomerName(excel.getCustomerName());
            order.setAmount(new BigDecimal(excel.getAmount()));
            this.save(order);
        }
    }
}
```

---

## 完整示例：订单导入导出

### 文件结构

```
blade-service/blade-order/src/main/java/org/springblade/order/
├── excel/
│   ├── OrderExcel.java      ← Excel 模型类
│   └── OrderImporter.java   ← 导入策略
├── controller/
│   └── OrderController.java ← 导入导出端点
└── service/
    ├── IOrderService.java   ← 接口声明
    └── impl/
        └── OrderServiceImpl.java ← 导入导出实现
```

### 完整代码

**OrderExcel.java**（EVO）：

```java
package org.springblade.order.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@ColumnWidth(25)
@HeadRowHeight(20)
@ContentRowHeight(18)
public class OrderExcel implements Serializable {
    private static final long serialVersionUID = 1L;

    @ExcelIgnore
    private String id;

    @ColumnWidth(20)
    @ExcelProperty("订单编号")
    private String orderNo;

    @ExcelProperty("客户名称")
    private String customerName;

    @ExcelProperty("金额")
    private String amount;

    @ExcelIgnore
    private String statusCode;

    @ExcelProperty("状态")
    private String statusName;

    @ColumnWidth(20)
    @ExcelProperty("创建时间")
    private Date createTime;
}
```

**OrderImporter.java**：

```java
package org.springblade.order.excel;

import lombok.RequiredArgsConstructor;
import org.springblade.core.excel.support.ExcelImporter;
import org.springblade.order.service.IOrderService;

import java.util.List;

@RequiredArgsConstructor
public class OrderImporter implements ExcelImporter<OrderExcel> {
    private final IOrderService service;
    private final Boolean isCovered;

    @Override
    public void save(List<OrderExcel> data) {
        service.importOrder(data, isCovered);
    }
}
```

**OrderController 中的导入导出方法**：

```java
// 导出
@GetMapping("/export-order")
@ApiOperationSupport(order = 20)
@Operation(summary = "导出订单")
public void exportOrder(@RequestParam Map<String, Object> params,
                         HttpServletResponse response) {
    QueryWrapper<Order> queryWrapper = Condition.getQueryWrapper(params, Order.class);
    List<OrderExcel> list = orderService.exportOrder(queryWrapper);
    ExcelUtil.export(response, "订单列表", "订单数据", list, OrderExcel.class);
}

// 导入
@PostMapping("/import-order")
@ApiOperationSupport(order = 21)
@Operation(summary = "导入订单")
public R importOrder(MultipartFile file,
                      @RequestParam(defaultValue = "0") Integer isCovered) {
    OrderImporter importer = new OrderImporter(orderService, isCovered == 1);
    ExcelUtil.save(file, importer, OrderExcel.class);
    return R.success("操作成功");
}

// 模板下载
@GetMapping("/export-template")
@ApiOperationSupport(order = 22)
@Operation(summary = "导出模板")
public void exportTemplate(HttpServletResponse response) {
    ExcelUtil.export(response, "订单导入模板", "订单数据",
        new ArrayList<>(), OrderExcel.class);
}
```

---

## 注意事项

1. **EVO 中金额用 String**：避免 Excel 数字格式导致的精度丢失。
2. **EVO 中 ID 用 `@ExcelIgnore` 隐藏**：导入时不需要用户填 ID，导出时 ID 对用户无意义。
3. **导入使用 `ExcelUtil.save()` 而非 `ExcelUtil.read()`**：`save()` 使用批量处理（默认每 3000 条一批），大数据量时性能更好。
4. **导入加事务**：Service 的 `importXxx()` 方法必须加 `@Transactional(rollbackFor = Exception.class)`。
5. **行级错误处理**：导入时应逐行校验，积累所有错误信息后统一返回，而非遇到第一个错误就停止。
6. **EVO 文件位置**：EVO 和 Importer 放在服务模块的 `excel/` 包下，不在 API 模块。因为它们是服务实现细节。
7. **@ExcelIgnore vs @ExcelProperty**：如果两个注解同时存在，`@ExcelIgnore` 优先，字段不会出现在 Excel 中。

## 检查清单

- [ ] Excel 模型类加 `@Data`、`@ColumnWidth`、`@HeadRowHeight`、`@ContentRowHeight`
- [ ] Excel 列用 `@ExcelProperty("中文列名")`
- [ ] 不导出的字段加 `@ExcelIgnore`
- [ ] Importer 类实现 `ExcelImporter<XxxExcel>`，加 `@RequiredArgsConstructor`
- [ ] Importer 的 `save()` 方法委托给 Service
- [ ] 导出端点方法签名包含 `HttpServletResponse response`
- [ ] 导入端点使用 `ExcelUtil.save()` 而非 `ExcelUtil.read()`
- [ ] 导入逻辑加 `@Transactional`
- [ ] 提供模板下载端点
