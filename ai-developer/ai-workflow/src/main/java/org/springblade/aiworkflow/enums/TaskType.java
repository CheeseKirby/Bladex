package org.springblade.aiworkflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public enum TaskType {

    STANDARD_CRUD_ENTITY("STANDARD_CRUD_ENTITY", "标准CRUD实体类"),
    STANDARD_CRUD_CONTROLLER("STANDARD_CRUD_CONTROLLER", "标准CRUD控制器"),
    STANDARD_CRUD_SERVICE("STANDARD_CRUD_SERVICE", "标准CRUD服务层"),
    COMPLEX_BUSINESS_SERVICE("COMPLEX_BUSINESS_SERVICE", "复杂业务逻辑服务"),
    CUSTOM_MAPPER("CUSTOM_MAPPER", "自定义查询Mapper"),
    MAPPER_XML("MAPPER_XML", "Mapper XML 映射文件"),
    FEIGN_CLIENT("FEIGN_CLIENT", "Feign远程调用客户端"),
    EXCEL_IMPORT_EXPORT("EXCEL_IMPORT_EXPORT", "Excel导入导出"),
    NACOS_CONFIG("NACOS_CONFIG", "Nacos配置"),
    DDL_STATEMENT("DDL_STATEMENT", "数据库DDL"),
    WRAPPER("WRAPPER", "Wrapper转换类"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String desc;

    private static final Map<String, TaskType> CODE_MAP =
            Collections.unmodifiableMap(
                    Arrays.stream(values()).collect(Collectors.toMap(TaskType::getCode, Function.identity())));

    public static TaskType fromCode(String code) {
        TaskType result = CODE_MAP.get(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown TaskType code: " + code);
        }
        return result;
    }
}
