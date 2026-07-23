package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, contract-preserving repairs for errors that do not require architectural judgment.
 *
 * <p>The repair unit is the whole related artifact group rather than one source file. This is important for
 * DTO inheritance and controller/service closure: each individual edit can temporarily make another file invalid,
 * while the complete group is mechanically provable from the canonical contract and generated declarations.</p>
 */
final class DeterministicContractRepairer {

    private static final Set<String> API_MODEL_RULES = Set.of(
            "CANONICAL-INPUT-FIELD-MISSING", "CANONICAL-INPUT-VALIDATION-MISSING",
            "CANONICAL-UVO-ID-MISSING", "CANONICAL-UVO-INHERITANCE",
            "CANONICAL-VO-DERIVED-FIELD-MISSING", "CANONICAL-VO-FIELD-UNEXPECTED",
            "CANONICAL-VO-FIELD-SHADOW", "JAVA-IMPORT-MISSING", "TYPE-METHOD-MISSING");
    private static final Set<String> CONTROLLER_RULES = Set.of(
            "CONTROLLER-SERVICE-BUSINESS-GAP", "CONTROLLER-SKIP-SERVICE-VALIDATION");
    private static final Set<String> MAPPER_PAGE_RULES = Set.of("LIST-MAPPER-PAGE-INCONSISTENT");
    private static final Set<String> DDL_RULES = Set.of(
            "MAPPER-DDL-COLUMN-MISSING", "CANONICAL-DDL-COLUMN-MISSING");
    private static final Set<String> FRAMEWORK_RULES = Set.of("FRAMEWORK-SELECTCOUNT-TYPE-MISMATCH");
    private static final Pattern INTERFACE_METHOD = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?([A-Za-z0-9_<>?, .]+?)\\s+([a-z][A-Za-z0-9_]*)\\s*\\(([^;{}]*)\\)\\s*;");
    private static final Pattern PARAM = Pattern.compile(
            "(?:@[A-Za-z0-9_.]+(?:\\([^)]*\\))?\\s+)*(?:final\\s+)?([A-Za-z0-9_<>?, .]+)\\s+([a-z][A-Za-z0-9_]*)$");
    private static final Pattern PAGE_SELECT = Pattern.compile(
            "(?is)\\s*<select\\b[^>]*\\bid=\"(?:select[A-Za-z0-9_]*Page|selectPage)\"[^>]*>.*?</select>\\s*");

    private static final Pattern BLADEX2_LONG_SELECT_COUNT = Pattern.compile(
            "(?m)\\b(?:Long|long)(\\s+[a-z][A-Za-z0-9_]*\\s*=\\s*(?:this\\.)?"
                    + "(?:baseMapper|[a-z][A-Za-z0-9_]*Mapper)\\.selectCount\\s*\\()");

    RepairBatch repair(List<GeneratedFile> sourceFiles, GenerationContext context, Set<String> activeRules) {
        if (context == null || context.domainContract() == null || context.domainContract().isEmpty()) {
            return new RepairBatch(copy(sourceFiles), Map.of());
        }
        List<GeneratedFile> files = copy(sourceFiles);
        Map<String, String> changes = new LinkedHashMap<>();
        if (containsAny(activeRules, API_MODEL_RULES)) {
            repairApiModels(files, context, changes);
            alignUpdateMethodTypes(files, context, changes);
            removeRequiredFieldNulling(files, context, changes);
        }
        if (containsAny(activeRules, DDL_RULES)) repairDdl(files, context, changes);
        if (containsAny(activeRules, MAPPER_PAGE_RULES)) repairDeadMapperPage(files, context, changes);
        if (containsAny(activeRules, CONTROLLER_RULES)) repairController(files, context, changes);
        if (containsAny(activeRules, FRAMEWORK_RULES)) repairFrameworkCompatibility(files, changes);
        return new RepairBatch(files, changes);
    }

    private void repairApiModels(List<GeneratedFile> files, GenerationContext context, Map<String, String> changes) {
        String entity = context.identity().entityName();
        GeneratedJavaType ivo = findJavaType(files, entity + "IVO");
        GeneratedJavaType uvo = findJavaType(files, entity + "UVO");
        GeneratedJavaType vo = findJavaType(files, entity + "VO");
        GeneratedJavaType entityType = findJavaType(files, entity);
        if (ivo != null) {
            replace(files, ivo.file().getFilePath(), renderIvo(context, ivo.packageName()), changes,
                    "Regenerated canonical create input model at the authoritative generated path");
        }
        if (uvo != null) {
            String ivoPackage = ivo == null ? voPackage(context, entity + "IVO") : ivo.packageName();
            replace(files, uvo.file().getFilePath(), renderUvo(context, uvo.packageName(), ivoPackage), changes,
                    "Regenerated canonical update input model with IVO inheritance at the authoritative generated path");
        }
        if (vo != null) {
            String entityPackage = entityType == null
                    ? context.identity().basePackage() + "." + context.referenceProfile().entityPackageSuffix()
                    : entityType.packageName();
            replace(files, vo.file().getFilePath(), renderVo(context, vo.packageName(), entityPackage), changes,
                    "Regenerated canonical output model without inherited-field shadowing at the authoritative generated path");
        }
    }

    private String renderIvo(GenerationContext context, String pkg) {
        String entity = context.identity().entityName();
        String type = entity + "IVO";
        CanonicalDomainContract contract = context.domainContract();
        Set<String> imports = new LinkedHashSet<>();
        imports.add("io.swagger.annotations.ApiModel");
        imports.add("io.swagger.annotations.ApiModelProperty");
        imports.add("lombok.Data");
        imports.add(validationPackage(context) + ".NotNull");
        if (contract.persistentFields().stream().anyMatch(field -> "String".equals(simpleType(field.javaType())) && field.required())) {
            imports.add(validationPackage(context) + ".NotBlank");
        }
        imports.add("java.io.Serializable");
        addJavaTypeImports(contract.persistentFields(), imports);
        StringBuilder body = new StringBuilder();
        body.append("package ").append(pkg).append(";\n\n");
        appendImports(body, imports);
        body.append("\n@Data\n@ApiModel(value = \"").append(type).append("\", description = \"")
                .append(entity).append("新增对象\")\n")
                .append("public class ").append(type).append(" implements Serializable {\n\n")
                .append("\tprivate static final long serialVersionUID = 1L;\n");
        for (CanonicalDomainContract.DomainField field : contract.persistentFields()) {
            body.append("\n\t@ApiModelProperty(value = \"").append(escape(field.evidence())).append("\")\n");
            if (field.required()) {
                String annotation = "String".equals(simpleType(field.javaType())) ? "NotBlank" : "NotNull";
                body.append("\t@").append(annotation).append("(message = \"")
                        .append(escape(field.evidence())).append("不能为空\")\n");
            }
            body.append("\tprivate ").append(simpleType(field.javaType())).append(' ')
                    .append(field.name()).append(";\n");
        }
        return body.append("\n}\n").toString();
    }

    private String renderUvo(GenerationContext context, String pkg, String ivoPackage) {
        String entity = context.identity().entityName();
        String type = entity + "UVO";
        StringBuilder body = new StringBuilder();
        body.append("package ").append(pkg).append(";\n\n")
                .append("import io.swagger.annotations.ApiModel;\n")
                .append("import io.swagger.annotations.ApiModelProperty;\n")
                .append("import lombok.Data;\n")
                .append("import lombok.EqualsAndHashCode;\n")
                .append("import ").append(validationPackage(context)).append(".NotNull;\n");
        if (!pkg.equals(ivoPackage)) {
            body.append("import ").append(ivoPackage).append('.').append(entity).append("IVO;\n");
        }
        body.append("\n@Data\n@EqualsAndHashCode(callSuper = true)\n")
                .append("@ApiModel(value = \"").append(type).append("\", description = \"")
                .append(entity).append("修改对象\")\n")
                .append("public class ").append(type).append(" extends ").append(entity).append("IVO {\n\n")
                .append("\tprivate static final long serialVersionUID = 1L;\n\n")
                .append("\t@NotNull(message = \"主键不能为空\")\n")
                .append("\t@ApiModelProperty(value = \"主键\")\n")
                .append("\tprivate Long id;\n\n")
                .append("}\n");
        return body.toString();
    }

    private String renderVo(GenerationContext context, String pkg, String entityPackage) {
        String entity = context.identity().entityName();
        String type = entity + "VO";
        Set<String> imports = new LinkedHashSet<>();
        imports.add("io.swagger.annotations.ApiModel");
        imports.add("io.swagger.annotations.ApiModelProperty");
        imports.add("lombok.Data");
        imports.add("lombok.EqualsAndHashCode");
        imports.add(entityPackage + "." + entity);
        imports.add("java.io.Serializable");
        addJavaTypeImports(context.domainContract().derivedFields(), imports);
        StringBuilder body = new StringBuilder();
        body.append("package ").append(pkg).append(";\n\n");
        appendImports(body, imports);
        body.append("\n@Data\n@EqualsAndHashCode(callSuper = true)\n")
                .append("@ApiModel(value = \"").append(type).append("\", description = \"")
                .append(entity).append("输出对象\")\n")
                .append("public class ").append(type).append(" extends ").append(entity)
                .append(" implements Serializable {\n\n")
                .append("\tprivate static final long serialVersionUID = 1L;\n");
        for (CanonicalDomainContract.DomainField field : context.domainContract().derivedFields()) {
            body.append("\n\t@ApiModelProperty(value = \"").append(escape(field.evidence())).append("\")\n")
                    .append("\tprivate ").append(simpleType(field.javaType())).append(' ')
                    .append(field.name()).append(";\n");
        }
        return body.append("\n}\n").toString();
    }

    private void alignUpdateMethodTypes(List<GeneratedFile> files, GenerationContext context,
                                        Map<String, String> changes) {
        String entity = context.identity().entityName();
        String ivo = entity + "IVO";
        String uvo = entity + "UVO";
        GeneratedJavaType uvoType = findJavaType(files, uvo);
        String uvoFqcn = uvoType == null ? voPackage(context, uvo) + "." + uvo : uvoType.fqcn();
        for (String serviceType : List.of("I" + entity + "Service", entity + "ServiceImpl")) {
            GeneratedJavaType sourceType = findJavaType(files, serviceType);
            if (sourceType == null || sourceType.file().getContent() == null) continue;
            GeneratedFile source = sourceType.file();
            String repaired = source.getContent().replaceAll(
                    "(\\b(?:modify|update)[A-Za-z0-9_]*\\s*\\(\\s*)" + Pattern.quote(ivo) + "(\\s+[a-z][A-Za-z0-9_]*)",
                    "$1" + uvo + "$2");
            if (repaired.equals(source.getContent())) continue;
            repaired = ensureImport(repaired, sourceType.packageName(), uvoFqcn);
            replace(files, source.getFilePath(), repaired, changes,
                    "Aligned update service method parameter with canonical UVO");
        }
    }

    private void removeRequiredFieldNulling(List<GeneratedFile> files, GenerationContext context,
                                             Map<String, String> changes) {
        Set<String> required = new LinkedHashSet<>();
        for (CanonicalDomainContract.DomainField field : context.domainContract().persistentFields()) {
            if (field.required()) required.add(field.name());
        }
        for (GeneratedFile file : new ArrayList<>(files)) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith("ServiceImpl.java") || file.getContent() == null) continue;
            String repaired = file.getContent();
            for (String field : required) {
                String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                repaired = repaired.replaceAll("(?m)^\\s*[a-z][A-Za-z0-9_]*\\." + Pattern.quote(setter)
                        + "\\(null\\);\\s*\\R?", "");
            }
            if (!repaired.equals(file.getContent())) {
                replace(files, path, repaired, changes, "Removed null assignment to required canonical field");
            }
        }
    }

    private void repairFrameworkCompatibility(List<GeneratedFile> files, Map<String, String> changes) {
        for (GeneratedFile file : new ArrayList<>(files)) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith(".java") || file.getContent() == null) continue;
            String repaired = BLADEX2_LONG_SELECT_COUNT.matcher(file.getContent()).replaceAll("Integer$1");
            if (!repaired.equals(file.getContent())) {
                replace(files, path, repaired, changes,
                        "Aligned MyBatis-Plus selectCount assignment with BladeX 2.x Integer return type");
            }
        }
    }

    private void repairDdl(List<GeneratedFile> files, GenerationContext context, Map<String, String> changes) {
        String path = BladeXModuleLayout.ddlPath(context);
        GeneratedFile ddl = find(files, path);
        if (ddl == null || ddl.getContent() == null) return;
        Pattern targetTableOpen = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?"
                + Pattern.quote(context.identity().tableName()) + "`?\\s*\\(");
        Matcher matcher = targetTableOpen.matcher(ddl.getContent());
        if (!matcher.find()) return;
        Matcher tableClose = Pattern.compile("(?is)\\)\\s*ENGINE").matcher(ddl.getContent());
        if (!tableClose.find(matcher.end())) return;
        String tableBody = ddl.getContent().substring(matcher.end(), tableClose.start());
        List<String> missingColumns = new ArrayList<>();
        Map<String, String> baseColumns = new LinkedHashMap<>();
        baseColumns.put("tenant_id", "`tenant_id` VARCHAR(12) DEFAULT '000000' COMMENT '??ID'");
        baseColumns.put("create_user", "`create_user` BIGINT(20) DEFAULT NULL COMMENT '???'");
        baseColumns.put("create_dept", "`create_dept` BIGINT(20) DEFAULT NULL COMMENT '????'");
        baseColumns.put("create_time", "`create_time` DATETIME DEFAULT NULL COMMENT '????'");
        baseColumns.put("update_user", "`update_user` BIGINT(20) DEFAULT NULL COMMENT '???'");
        baseColumns.put("update_time", "`update_time` DATETIME DEFAULT NULL COMMENT '????'");
        baseColumns.put("status", "`status` INT(11) DEFAULT 1 COMMENT '??'");
        baseColumns.put("is_deleted", "`is_deleted` INT(11) DEFAULT 0 COMMENT '?????'");
        for (Map.Entry<String, String> column : baseColumns.entrySet()) {
            if (!containsColumn(tableBody, column.getKey())) missingColumns.add(column.getValue());
        }
        for (CanonicalDomainContract.DomainField field : context.domainContract().persistentFields()) {
            if (!containsColumn(tableBody, field.columnName())) {
                missingColumns.add(renderSqlColumn(field));
            }
        }
        if (missingColumns.isEmpty()) return;
        String insertion = "\n  " + String.join(",\n  ", missingColumns) + ",";
        String repaired = ddl.getContent().substring(0, matcher.end()) + insertion
                + ddl.getContent().substring(matcher.end());
        replace(files, path, repaired, changes, "Added missing BladeX base/canonical columns");
    }

    private String renderSqlColumn(CanonicalDomainContract.DomainField field) {
        String sqlType = switch (simpleType(field.javaType())) {
            case "Long" -> "BIGINT(20)";
            case "Integer", "Short", "Byte" -> "INT(11)";
            case "Boolean" -> "TINYINT(1)";
            case "Date", "LocalDateTime" -> "DATETIME";
            case "LocalDate" -> "DATE";
            case "BigDecimal" -> "DECIMAL(18,2)";
            default -> "VARCHAR(255)";
        };
        String nullability = field.required() ? " NOT NULL" : " DEFAULT NULL";
        return "`" + field.columnName() + "` " + sqlType + nullability + " COMMENT '"
                + escapeSqlComment(field.evidence()) + "'";
    }

    private String escapeSqlComment(String value) {
        String text = value == null || value.isBlank() ? "??" : value;
        return text.replace("'", "''");
    }

    private void repairDeadMapperPage(List<GeneratedFile> files, GenerationContext context,
                                      Map<String, String> changes) {
        String entity = context.identity().entityName();
        String mapperPath = BladeXModuleLayout.mapperJavaPath(context, entity);
        GeneratedFile mapper = find(files, mapperPath);
        if (mapper != null && mapper.getContent() != null) {
            try {
                CompilationUnit unit = new JavaParser().parse(mapper.getContent()).getResult().orElse(null);
                if (unit != null) {
                    List<MethodDeclaration> removals = unit.findAll(MethodDeclaration.class).stream()
                            .filter(method -> method.getNameAsString().matches("select[A-Za-z0-9_]*Page|selectPage")
                                    && method.getParameters().stream()
                                    .anyMatch(param -> param.getTypeAsString().contains("IPage")))
                            .toList();
                    removals.forEach(MethodDeclaration::remove);
                    if (!removals.isEmpty()) {
                        removeUnusedImport(unit, "com.baomidou.mybatisplus.core.metadata.IPage");
                        replace(files, mapperPath, unit.toString(), changes,
                                "Removed unconsumed custom mapper page method; controller keeps standard BaseService paging");
                    }
                }
            } catch (RuntimeException ignored) {
                // Keep the original file; the LLM repair path remains available.
            }
        }
        String xmlPath = BladeXModuleLayout.mapperXmlPath(context, entity);
        GeneratedFile xml = find(files, xmlPath);
        if (xml != null && xml.getContent() != null) {
            String repaired = PAGE_SELECT.matcher(xml.getContent()).replaceAll("\n");
            if (!repaired.equals(xml.getContent())) {
                replace(files, xmlPath, repaired, changes,
                        "Removed mapper XML page statement together with unconsumed Java mapper method");
            }
        }
    }

    private void repairController(List<GeneratedFile> files, GenerationContext context, Map<String, String> changes) {
        String entity = context.identity().entityName();
        GeneratedJavaType service = findJavaType(files, "I" + entity + "Service");
        GeneratedJavaType controller = findJavaType(files, entity + "Controller");
        if (service == null || service.file().getContent() == null || controller == null) return;
        List<ServiceMethod> methods = parseServiceMethods(service.file().getContent());
        if (methods.isEmpty()) return;
        String rendered = renderController(context, files, controller, methods);
        replace(files, controller.file().getFilePath(), rendered, changes,
                "Regenerated controller endpoints from the generated service interface method contract");
    }

    private String renderController(GenerationContext context, List<GeneratedFile> files,
                                    GeneratedJavaType controller, List<ServiceMethod> methods) {
        String entity = context.identity().entityName();
        String base = context.identity().basePackage();
        String controllerPackage = controller.packageName();
        String entityPackage = packageOf(files, entity,
                base + "." + context.referenceProfile().entityPackageSuffix());
        String ivoPackage = packageOf(files, entity + "IVO", voPackage(context, entity + "IVO"));
        String uvoPackage = packageOf(files, entity + "UVO", voPackage(context, entity + "UVO"));
        String voPackage = packageOf(files, entity + "VO", voPackage(context, entity + "VO"));
        String servicePackage = packageOf(files, "I" + entity + "Service",
                base + "." + context.referenceProfile().servicePackageSuffix());
        String wrapperPackage = packageOf(files, entity + "Wrapper",
                base + "." + context.referenceProfile().wrapperPackageSuffix());
        ServiceMethod submit = findMethod(methods, "submit");
        ServiceMethod modify = findMethod(methods, "modify", "update");
        Set<String> consumed = new LinkedHashSet<>();
        if (submit != null) consumed.add(submit.name());
        if (modify != null) consumed.add(modify.name());
        String serviceField = Character.toLowerCase(entity.charAt(0)) + entity.substring(1) + "Service";
        StringBuilder body = new StringBuilder();
        body.append("package ").append(controllerPackage).append(";\n\n")
                .append("import com.baomidou.mybatisplus.core.metadata.IPage;\n")
                .append("import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;\n")
                .append("import io.swagger.annotations.Api;\n")
                .append("import io.swagger.annotations.ApiOperation;\n")
                .append("import io.swagger.annotations.ApiParam;\n")
                .append("import lombok.AllArgsConstructor;\n")
                .append("import org.springblade.core.boot.ctrl.BladeController;\n")
                .append("import org.springblade.core.mp.support.Condition;\n")
                .append("import org.springblade.core.mp.support.Query;\n")
                .append("import org.springblade.core.tool.api.R;\n")
                .append("import org.springblade.core.tool.utils.Func;\n")
                .append("import ").append(entityPackage).append('.').append(entity).append(";\n")
                .append("import ").append(servicePackage).append(".I").append(entity).append("Service;\n")
                .append("import ").append(ivoPackage).append('.').append(entity).append("IVO;\n")
                .append("import ").append(uvoPackage).append('.').append(entity).append("UVO;\n")
                .append("import ").append(voPackage).append('.').append(entity).append("VO;\n")
                .append("import ").append(wrapperPackage).append('.').append(entity).append("Wrapper;\n");
        appendGeneratedMethodImports(body, files, controllerPackage, methods,
                Set.of(entity, entity + "IVO", entity + "UVO", entity + "VO", entity + "Wrapper"));
        body.append("import org.springframework.web.bind.annotation.*;\n\n")
                .append("import ").append(validationPackage(context).replace(".constraints", "")).append(".Valid;\n")
                .append("import java.util.Map;\n\n")
                .append("@RestController\n@AllArgsConstructor\n@RequestMapping(\"/")
                .append(Character.toLowerCase(entity.charAt(0))).append(entity.substring(1)).append("\")\n")
                .append("@Api(value = \"").append(entity).append("\", tags = \"").append(entity).append("接口\")\n")
                .append("public class ").append(entity).append("Controller extends BladeController {\n\n")
                .append("\tprivate final I").append(entity).append("Service ").append(serviceField).append(";\n\n")
                .append("\t@GetMapping(\"/detail\")\n\t@ApiOperationSupport(order = 1)\n")
                .append("\t@ApiOperation(value = \"详情\", notes = \"传入").append(entity).append("\")\n")
                .append("\tpublic R<").append(entity).append("VO> detail(").append(entity).append(" value) {\n")
                .append("\t\t").append(entity).append(" detail = ").append(serviceField)
                .append(".getOne(Condition.getQueryWrapper(value));\n")
                .append("\t\treturn R.data(").append(entity).append("Wrapper.build().entityVO(detail));\n\t}\n\n")
                .append("\t@GetMapping(\"/list\")\n\t@ApiOperationSupport(order = 2)\n")
                .append("\t@ApiOperation(value = \"分页列表\", notes = \"传入查询参数\")\n")
                .append("\tpublic R<IPage<").append(entity).append("VO>> list(")
                .append("@ApiParam(value = \"查询参数\", hidden = true) @RequestParam Map<String, Object> params, Query query) {\n")
                .append("\t\tIPage<").append(entity).append("> pages = ").append(serviceField)
                .append(".page(Condition.getPage(query), Condition.getQueryWrapper(params, ").append(entity).append(".class));\n")
                .append("\t\treturn R.data(").append(entity).append("Wrapper.build().pageVO(pages));\n\t}\n\n")
                .append("\t@PostMapping(\"/save\")\n\t@ApiOperationSupport(order = 3)\n")
                .append("\t@ApiOperation(value = \"新增\", notes = \"传入新增对象\")\n")
                .append("\tpublic R save(@Valid @RequestBody ").append(entity).append("IVO value) {\n")
                .append("\t\treturn R.status(");
        if (submit != null) body.append(serviceField).append('.').append(submit.name()).append("(value)");
        else body.append(serviceField).append(".save(").append(entity).append("Wrapper.build().entity(value))");
        body.append(");\n\t}\n\n")
                .append("\t@PostMapping(\"/update\")\n\t@ApiOperationSupport(order = 4)\n")
                .append("\t@ApiOperation(value = \"修改\", notes = \"传入修改对象\")\n")
                .append("\tpublic R update(@Valid @RequestBody ").append(entity).append("UVO value) {\n")
                .append("\t\treturn R.status(");
        if (modify != null) body.append(serviceField).append('.').append(modify.name()).append("(value)");
        else body.append(serviceField).append(".updateById(").append(entity).append("Wrapper.build().entity(value))");
        body.append(");\n\t}\n\n")
                .append("\t@PostMapping(\"/remove\")\n\t@ApiOperationSupport(order = 5)\n")
                .append("\t@ApiOperation(value = \"逻辑删除\", notes = \"传入ids\")\n")
                .append("\tpublic R remove(@ApiParam(value = \"主键集合\", required = true) @RequestParam String ids) {\n")
                .append("\t\treturn R.status(").append(serviceField).append(".deleteLogic(Func.toLongList(ids)));\n\t}\n");
        int order = 6;
        for (ServiceMethod method : methods) {
            if (consumed.contains(method.name()) || isBaseServiceMethod(method.name())) continue;
            body.append(renderCustomEndpoint(method, serviceField, order++));
        }
        return body.append("\n}\n").toString();
    }

    private String renderCustomEndpoint(ServiceMethod method, String serviceField, int order) {
        StringBuilder body = new StringBuilder();
        body.append("\n\t@PostMapping(\"/").append(method.name()).append("\")\n")
                .append("\t@ApiOperationSupport(order = ").append(order).append(")\n")
                .append("\t@ApiOperation(value = \"").append(method.name()).append("\")\n")
                .append("\tpublic R ").append(method.name()).append('(');
        List<String> args = new ArrayList<>();
        for (int i = 0; i < method.parameters().size(); i++) {
            Parameter parameter = method.parameters().get(i);
            if (i > 0) body.append(", ");
            if (isSimpleRequestType(parameter.type())) body.append("@RequestParam ");
            else body.append("@Valid @RequestBody ");
            body.append(parameter.type()).append(' ').append(parameter.name());
            args.add(parameter.name());
        }
        body.append(") {\n");
        String invocation = serviceField + "." + method.name() + "(" + String.join(", ", args) + ")";
        if ("void".equals(method.returnType())) {
            body.append("\t\t").append(invocation).append(";\n\t\treturn R.success(\"")
                    .append(method.name()).append("成功\");\n");
        } else if ("boolean".equals(method.returnType()) || "Boolean".equals(method.returnType())) {
            body.append("\t\treturn R.status(").append(invocation).append(");\n");
        } else {
            body.append("\t\treturn R.data(").append(invocation).append(");\n");
        }
        return body.append("\t}\n").toString();
    }

    private List<ServiceMethod> parseServiceMethods(String content) {
        List<ServiceMethod> methods = new ArrayList<>();
        Matcher matcher = INTERFACE_METHOD.matcher(content);
        while (matcher.find()) {
            String returnType = matcher.group(1).trim().replaceAll("\\s+", " ");
            String name = matcher.group(2);
            if (returnType.contains(" interface ") || returnType.endsWith("interface")) continue;
            List<Parameter> parameters = new ArrayList<>();
            String rawParameters = matcher.group(3).trim();
            if (!rawParameters.isBlank()) {
                for (String raw : splitParameters(rawParameters)) {
                    Matcher parameter = PARAM.matcher(raw.trim());
                    if (!parameter.find()) continue;
                    parameters.add(new Parameter(parameter.group(1).trim(), parameter.group(2)));
                }
            }
            methods.add(new ServiceMethod(simpleType(returnType), name, parameters));
        }
        return methods;
    }

    private List<String> splitParameters(String input) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(input.substring(start, i));
                start = i + 1;
            }
        }
        result.add(input.substring(start));
        return result;
    }

    private ServiceMethod findMethod(List<ServiceMethod> methods, String... names) {
        for (String name : names) {
            for (ServiceMethod method : methods) if (name.equals(method.name())) return method;
        }
        return null;
    }

    private boolean isBaseServiceMethod(String name) {
        return Set.of("save", "updateById", "deleteLogic", "getOne", "page", "getById", "list", "count")
                .contains(name);
    }

    private boolean isSimpleRequestType(String type) {
        String simple = simpleType(type);
        return Set.of("String", "Long", "Integer", "Boolean", "Short", "Byte", "Double", "Float",
                "BigDecimal", "Date", "LocalDate", "LocalDateTime").contains(simple);
    }

    private void removeUnusedImport(CompilationUnit unit, String fqcn) {
        boolean stillUsed = unit.toString().contains(simpleType(fqcn));
        if (!stillUsed) unit.getImports().removeIf(item -> fqcn.equals(item.getNameAsString()));
    }

    private boolean containsColumn(String sql, String column) {
        return Pattern.compile("(?i)(?:`" + Pattern.quote(column) + "`|\\b" + Pattern.quote(column) + "\\b)\\s+")
                .matcher(sql).find();
    }

    private void addJavaTypeImports(List<CanonicalDomainContract.DomainField> fields, Set<String> imports) {
        for (CanonicalDomainContract.DomainField field : fields) {
            switch (simpleType(field.javaType())) {
                case "Date" -> imports.add("java.util.Date");
                case "LocalDate" -> imports.add("java.time.LocalDate");
                case "LocalDateTime" -> imports.add("java.time.LocalDateTime");
                case "BigDecimal" -> imports.add("java.math.BigDecimal");
                default -> { }
            }
        }
    }

    private void appendImports(StringBuilder body, Set<String> imports) {
        for (String value : imports) body.append("import ").append(value).append(";\n");
    }

    private void appendGeneratedMethodImports(StringBuilder body, List<GeneratedFile> files,
                                              String ownerPackage, List<ServiceMethod> methods,
                                              Set<String> alreadyImported) {
        Set<String> imports = new LinkedHashSet<>();
        Pattern identifier = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
        for (ServiceMethod method : methods) {
            List<String> referencedTypes = new ArrayList<>();
            referencedTypes.add(method.returnType());
            for (Parameter parameter : method.parameters()) referencedTypes.add(parameter.type());
            for (String referencedType : referencedTypes) {
                Matcher matcher = identifier.matcher(referencedType == null ? "" : referencedType);
                while (matcher.find()) {
                    String simpleName = matcher.group();
                    if (alreadyImported.contains(simpleName)) continue;
                    GeneratedJavaType type = findJavaType(files, simpleName);
                    if (type != null && !ownerPackage.equals(type.packageName())) imports.add(type.fqcn());
                }
            }
        }
        for (String value : imports) body.append("import ").append(value).append(";\n");
    }

    private String packageOf(List<GeneratedFile> files, String typeName, String fallback) {
        GeneratedJavaType type = findJavaType(files, typeName);
        return type == null || type.packageName() == null || type.packageName().isBlank()
                ? fallback : type.packageName();
    }

    private GeneratedJavaType findJavaType(List<GeneratedFile> files, String typeName) {
        if (files == null || typeName == null || typeName.isBlank()) return null;
        Map<String, GeneratedFile> matchesByPath = new LinkedHashMap<>();
        for (GeneratedFile file : files) {
            if (file == null || file.getFilePath() == null) continue;
            String path = normalize(file.getFilePath());
            if (path.endsWith("/" + typeName + ".java")) matchesByPath.putIfAbsent(path, file);
        }
        if (matchesByPath.size() != 1) return null;
        GeneratedFile file = matchesByPath.values().iterator().next();
        String pkg = packageFromPhysicalPath(file.getFilePath());
        if (pkg == null || pkg.isBlank()) pkg = packageFromSource(file.getContent());
        return pkg == null || pkg.isBlank() ? null : new GeneratedJavaType(file, pkg);
    }

    private String packageFromPhysicalPath(String path) {
        String normalized = normalize(path);
        if (normalized == null) return null;
        String marker = "/src/main/java/";
        int markerIndex = normalized.indexOf(marker);
        int lastSlash = normalized.lastIndexOf('/');
        if (markerIndex < 0 || lastSlash <= markerIndex + marker.length()) return null;
        return normalized.substring(markerIndex + marker.length(), lastSlash).replace('/', '.');
    }

    private String packageFromSource(String content) {
        if (content == null) return null;
        Matcher matcher = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String ensureImport(String content, String ownerPackage, String fqcn) {
        if (content == null || fqcn == null || fqcn.isBlank()) return content;
        int dot = fqcn.lastIndexOf('.');
        String importedPackage = dot < 0 ? "" : fqcn.substring(0, dot);
        String simpleName = dot < 0 ? fqcn : fqcn.substring(dot + 1);
        String repaired = content.replaceAll("(?m)^\\s*import\\s+[^;]+\\." + Pattern.quote(simpleName)
                + "\\s*;\\s*\\R?", "");
        if (ownerPackage != null && ownerPackage.equals(importedPackage)) return repaired;
        Matcher packageDeclaration = Pattern.compile("(?m)^package\\s+[^;]+;").matcher(repaired);
        if (!packageDeclaration.find()) return repaired;
        return repaired.substring(0, packageDeclaration.end()) + "\n\nimport " + fqcn + ";"
                + repaired.substring(packageDeclaration.end());
    }

    private String validationPackage(GenerationContext context) {
        return context.referenceProfile().usesJavax()
                ? "javax.validation.constraints" : "jakarta.validation.constraints";
    }

    private String voPackage(GenerationContext context, String type) {
        return context.identity().basePackage() + "." + context.referenceProfile().voPackageSuffix(type);
    }

    private String simpleType(String value) {
        if (value == null) return "Object";
        String normalized = value.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) return normalized;
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private String escape(String value) {
        String text = value == null || value.isBlank() ? "字段" : value;
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean containsAny(Set<String> active, Set<String> candidates) {
        if (active == null || active.isEmpty()) return false;
        for (String rule : candidates) if (active.contains(rule)) return true;
        return false;
    }

    private GeneratedFile find(List<GeneratedFile> files, String path) {
        String normalized = normalize(path);
        return files.stream().filter(file -> normalized.equals(normalize(file.getFilePath()))).findFirst().orElse(null);
    }

    private void replace(List<GeneratedFile> files, String path, String content,
                         Map<String, String> changes, String reason) {
        String normalized = normalize(path);
        for (int i = 0; i < files.size(); i++) {
            GeneratedFile current = files.get(i);
            if (!normalized.equals(normalize(current.getFilePath())) || content.equals(current.getContent())) continue;
            files.set(i, GeneratedFile.modify(current.getType(), current.getFilePath(), content));
            changes.put(normalized, reason);
            return;
        }
    }

    private List<GeneratedFile> copy(List<GeneratedFile> files) {
        return files == null ? new ArrayList<>() : new ArrayList<>(files);
    }

    private String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    record RepairBatch(List<GeneratedFile> files, Map<String, String> changedPaths) { }
    private record GeneratedJavaType(GeneratedFile file, String packageName) {
        String fqcn() { return packageName + "." + fileNameWithoutExtension(file.getFilePath()); }

        private static String fileNameWithoutExtension(String path) {
            String normalized = path == null ? "" : path.replace('\\', '/');
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        }
    }
    private record ServiceMethod(String returnType, String name, List<Parameter> parameters) { }
    private record Parameter(String type, String name) { }
}
