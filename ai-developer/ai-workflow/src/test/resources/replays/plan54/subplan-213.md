# Excel 导入导出 - EasyExcel 工具类

## 实体名: SpecialPeriod
## 模块名: specialperiod
## 表名: blade_special_period
## 包路径: org.springblade.specialperiod
## 模块结构: blade-service/blade-specialperiod

## 目标层: Service 模块 (blade-specialperiod) - Excel 工具层

## 1. Excel 工具类: org.springblade.specialperiod.excel.SpecialPeriodExcelUtil
- 使用 EasyExcel (com.alibaba.excel.EasyExcel)
- @Slf4j
- 静态方法:

### export(List<SpecialPeriod> data, HttpServletResponse response)
- 设置 response: contentType=application/vnd.ms-excel, charset=utf-8
- 文件名: URLEncoder.encode("特殊时段配置_" + DateUtil.format(now, "yyyyMMddHHmmss"))
- WriteCellStyle + WriteFont 表头样式(居中, 加粗, 12pt)
- EasyExcel.write(response.getOutputStream(), SpecialPeriodEVO.class).sheet("特殊时段配置").doWrite(convert(data))
- convert: SpecialPeriod -> SpecialPeriodEVO 手动字段映射
- 异常: catch IOException -> ServiceException("导出失败: " + message)

### importExcel(MultipartFile file, ISpecialPeriodService service)
- EasyExcel.read(file.getInputStream(), SpecialPeriodEVO.class, new AnalysisEventListener<SpecialPeriodEVO>())
- invoke: cache.add(data)
- doAfterAllAnalysed: 遍历 cache, BeanUtil.copy(EVO -> IVO), service.save(ivo) 逐条保存
- 异常: catch IOException -> ServiceException("导入失败: " + message)

## 2. Service 委托方法补充
### ISpecialPeriodService 新增方法声明
```java
boolean importSpecialPeriod(MultipartFile file);
void exportSpecialPeriod(SpecialPeriodQVO qvo, HttpServletResponse response);
```

### SpecialPeriodServiceImpl 实现
```java
@Override
public boolean importSpecialPeriod(MultipartFile file) {
    SpecialPeriodExcelUtil.importExcel(file, this);
    return true;
}

@Override
public void exportSpecialPeriod(SpecialPeriodQVO qvo, HttpServletResponse response) {
    Query query = new Query();
    query.setSize(-1L);
    IPage<SpecialPeriodVO> page = selectPage(Condition.getPage(query), qvo);
    List<SpecialPeriod> data = new ArrayList<>();
    for (SpecialPeriodVO vo : page.getRecords()) {
        data.add(BeanUtil.copy(vo, SpecialPeriod.class));
    }
    SpecialPeriodExcelUtil.export(data, response);
}
```

## 技术约束
- Java 8
- EasyExcel 注解: com.alibaba.excel.annotation.ExcelProperty / ColumnWidth
- SpecialPeriodEVO 在 sub_2 中定义, ISpecialPeriodService 在 sub_3 中定义
- 导入时复用 Service.save() 的冲突校验逻辑, 保证数据一致性
- 导出查询不分页 (size=-1)