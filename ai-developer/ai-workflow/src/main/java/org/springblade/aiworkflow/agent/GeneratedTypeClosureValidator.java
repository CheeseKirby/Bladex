package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dependency-independent type-closure validation for generated types. It intentionally resolves only
 * declarations inside the generated inventory, avoiding false claims about private framework APIs.
 */
final class GeneratedTypeClosureValidator {

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    List<GeneratedProjectValidator.Issue> validate(List<GeneratedFile> files) {
        Map<String, TypeModel> models = new LinkedHashMap<>();
        Map<String, CompilationUnit> unitsByPath = new LinkedHashMap<>();
        for (GeneratedFile file : files == null ? List.<GeneratedFile>of() : files) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith(".java") || file.getContent() == null) continue;
            ParseResult<CompilationUnit> parsed = parser.parse(file.getContent());
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) continue;
            CompilationUnit unit = parsed.getResult().orElseThrow();
            unitsByPath.put(path, unit);
            for (TypeDeclaration<?> declaration : unit.getTypes()) {
                TypeModel model = model(path, declaration);
                models.putIfAbsent(model.name(), model);
            }
        }

        List<GeneratedProjectValidator.Issue> issues = new ArrayList<>();
        for (Map.Entry<String, CompilationUnit> entry : unitsByPath.entrySet()) {
            for (TypeDeclaration<?> declaration : entry.getValue().getTypes()) {
                TypeModel owner = models.get(declaration.getNameAsString());
                if (owner == null) continue;
                validateDeclaration(owner, declaration, models, issues);
            }
        }
        return deduplicate(issues);
    }

    private TypeModel model(String path, TypeDeclaration<?> declaration) {
        Map<String, String> fields = new LinkedHashMap<>();
        Set<String> finalFields = new LinkedHashSet<>();
        declaration.getFields().forEach(field -> field.getVariables().forEach(variable -> {
            fields.put(variable.getNameAsString(), normalizeType(variable.getType().asString()));
            if (field.isFinal()) finalFields.add(variable.getNameAsString());
        }));
        List<MethodModel> methods = new ArrayList<>();
        declaration.getMethods().forEach(method -> methods.add(new MethodModel(
                method.getNameAsString(), normalizeType(method.getType().asString()),
                method.getParameters().stream().map(parameter -> normalizeType(parameter.getType().asString())).toList())));
        Set<String> annotations = declaration.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier()).collect(java.util.stream.Collectors.toSet());
        boolean lombokGetters = annotations.stream().anyMatch(Set.of("Data", "Getter", "Value")::contains);
        boolean lombokSetters = annotations.stream().anyMatch(Set.of("Data", "Setter")::contains);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String suffix = Character.toUpperCase(field.getKey().charAt(0)) + field.getKey().substring(1);
            if (lombokGetters) {
                methods.add(new MethodModel("get" + suffix, field.getValue(), List.of()));
                if ("boolean".equals(field.getValue()) || "Boolean".equals(field.getValue())) {
                    methods.add(new MethodModel("is" + suffix, field.getValue(), List.of()));
                }
            }
            if (lombokSetters && !finalFields.contains(field.getKey())) {
                methods.add(new MethodModel("set" + suffix, "void", List.of(field.getValue())));
            }
        }
        String mapperType = null;
        Set<String> externalParents = new LinkedHashSet<>();
        if (declaration instanceof ClassOrInterfaceDeclaration classType) {
            for (var extended : classType.getExtendedTypes()) {
                externalParents.add(extended.getNameAsString());
                if ("BaseServiceImpl".equals(extended.getNameAsString()) && !extended.getTypeArguments().isEmpty()
                        && extended.getTypeArguments().orElseThrow().size() >= 1) {
                    mapperType = simpleType(extended.getTypeArguments().orElseThrow().get(0).asString());
                }
                if (Set.of("BaseEntity", "TenantEntity").contains(extended.getNameAsString())) {
                    Map<String, String> baseFields = Map.of(
                            "id", "Long", "status", "Integer", "isDeleted", "Integer",
                            "createUser", "Long", "createDept", "Long", "createTime", "LocalDateTime",
                            "updateUser", "Long", "updateTime", "LocalDateTime", "tenantId", "String");
                    for (Map.Entry<String, String> baseField : baseFields.entrySet()) {
                        String suffix = Character.toUpperCase(baseField.getKey().charAt(0)) + baseField.getKey().substring(1);
                        methods.add(new MethodModel("get" + suffix, baseField.getValue(), List.of()));
                        methods.add(new MethodModel("set" + suffix, "void", List.of(baseField.getValue())));
                    }
                }
            }
        }
        return new TypeModel(declaration.getNameAsString(), path, fields, methods, mapperType, externalParents);
    }

    private void validateDeclaration(TypeModel owner, TypeDeclaration<?> declaration,
                                     Map<String, TypeModel> models,
                                     List<GeneratedProjectValidator.Issue> issues) {
        for (MethodDeclaration method : declaration.getMethods()) {
            Map<String, String> variables = new LinkedHashMap<>(owner.fields());
            for (Parameter parameter : method.getParameters()) {
                variables.put(parameter.getNameAsString(), normalizeType(parameter.getType().asString()));
            }
            method.findAll(VariableDeclarator.class).forEach(variable ->
                    variables.put(variable.getNameAsString(), normalizeType(variable.getType().asString())));
            method.findAll(ForEachStmt.class).forEach(loop -> loop.getVariable().getVariables().forEach(variable ->
                    variables.put(variable.getNameAsString(), normalizeType(variable.getType().asString()))));
            if (owner.mapperType() != null) variables.put("baseMapper", owner.mapperType());
            variables.put("this", owner.name());

            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                ResolvedCall resolved = resolveCall(call, variables, models);
                if (resolved == null) continue;
                if (resolved.candidates().isEmpty()) {
                    if (isKnownExternalInheritedMethod(resolved.target(), resolved.scopeName(),
                            call.getNameAsString(), call.getArguments().size())) continue;
                    issues.add(error("TYPE-METHOD-MISSING", owner.path(),
                            resolved.target().name() + " does not declare generated method "
                                    + call.getNameAsString() + "(" + call.getArguments().size() + ")"));
                    continue;
                }
                MethodModel targetMethod = bestCandidate(resolved.candidates(), call, variables, models);
                validateArguments(owner, call, targetMethod, variables, models, issues);
                validateReturnClosure(owner, method, call, targetMethod, models, issues);
            }

            for (MethodReferenceExpr reference : method.findAll(MethodReferenceExpr.class)) {
                if (!(reference.getScope() instanceof TypeExpr typeExpr)) continue;
                TypeModel target = models.get(simpleType(typeExpr.getType().asString()));
                if (target == null) continue;
                String identifier = reference.getIdentifier();
                boolean exists = allMethods(target, models).stream()
                        .anyMatch(candidate -> candidate.name().equals(identifier));
                if (!exists) {
                    issues.add(error("TYPE-METHOD-REFERENCE-MISSING", owner.path(),
                            target.name() + " does not declare generated method reference " + identifier));
                }
            }
        }
    }

    private ResolvedCall resolveCall(MethodCallExpr call, Map<String, String> variables,
                                     Map<String, TypeModel> models) {
        if (call.getScope().isEmpty()) return null;
        Expression scope = call.getScope().orElseThrow();
        String targetType = null;
        String scopeName = null;
        if (scope instanceof NameExpr nameExpr) {
            scopeName = nameExpr.getNameAsString();
            targetType = variables.get(scopeName);
        } else if (scope instanceof ThisExpr) {
            scopeName = "this";
            targetType = variables.get("this");
        } else if (scope instanceof ObjectCreationExpr creation) {
            targetType = creation.getType().asString();
        }
        if (targetType == null) return null;
        TypeModel target = models.get(simpleType(targetType));
        if (target == null) return null;
        List<MethodModel> candidates = allMethods(target, models).stream()
                .filter(method -> method.name().equals(call.getNameAsString()))
                .filter(method -> method.parameterTypes().size() == call.getArguments().size())
                .toList();
        return new ResolvedCall(target, candidates, scopeName);
    }

    private boolean isKnownExternalInheritedMethod(TypeModel target, String scopeName, String method, int arity) {
        if ("baseMapper".equals(scopeName)) {
            return Set.of("insert", "delete", "deleteById", "deleteByIds", "deleteBatchIds",
                    "update", "updateById", "selectById", "selectBatchIds", "selectOne", "selectCount",
                    "selectList", "selectMaps", "selectObjs", "selectPage", "selectMapsPage").contains(method);
        }
        Set<String> serviceMethods = Set.of("save", "saveBatch", "saveOrUpdate", "saveOrUpdateBatch",
                "updateById", "update", "remove", "removeById", "removeByIds", "removeBatchByIds",
                "deleteLogic", "getById", "getOne", "list", "listByIds", "page", "count",
                "getBaseMapper", "getEntityClass");
        boolean externalService = target.externalParents().stream()
                .anyMatch(parent -> Set.of("BaseService", "IService").contains(parent));
        return serviceMethods.contains(method) && ("this".equals(scopeName) || externalService);
    }

    private MethodModel bestCandidate(List<MethodModel> candidates, MethodCallExpr call,
                                      Map<String, String> variables, Map<String, TypeModel> models) {
        if (candidates.size() <= 1) return candidates.get(0);
        return candidates.stream().max((left, right) -> Integer.compare(
                compatibilityScore(left, call, variables, models),
                compatibilityScore(right, call, variables, models))).orElse(candidates.get(0));
    }

    private int compatibilityScore(MethodModel candidate, MethodCallExpr call,
                                   Map<String, String> variables, Map<String, TypeModel> models) {
        int score = 0;
        for (int i = 0; i < call.getArguments().size(); i++) {
            String actual = inferExpressionType(call.getArgument(i), variables, models);
            if (actual != null && compatible(candidate.parameterTypes().get(i), actual, models)) score++;
        }
        return score;
    }

    private void validateArguments(TypeModel owner, MethodCallExpr call, MethodModel target,
                                   Map<String, String> variables, Map<String, TypeModel> models,
                                   List<GeneratedProjectValidator.Issue> issues) {
        for (int i = 0; i < call.getArguments().size(); i++) {
            String actual = inferExpressionType(call.getArgument(i), variables, models);
            String expected = target.parameterTypes().get(i);
            if (actual != null && !compatible(expected, actual, models)) {
                issues.add(error("TYPE-ARGUMENT-MISMATCH", owner.path(),
                        call.getNameAsString() + " argument " + (i + 1) + " expects " + expected
                                + " but generated caller provides " + actual));
            }
        }
    }

    private void validateReturnClosure(TypeModel owner, MethodDeclaration enclosing,
                                       MethodCallExpr call, MethodModel target,
                                       Map<String, TypeModel> models,
                                       List<GeneratedProjectValidator.Issue> issues) {
        if (target.returnType() == null) return;
        Optional<ReturnStmt> returned = call.findAncestor(ReturnStmt.class);
        if (returned.isEmpty() || returned.orElseThrow().getExpression().orElse(null) != call) return;
        String expected = normalizeType(enclosing.getType().asString());
        if (!compatible(expected, target.returnType(), models)) {
            issues.add(error("TYPE-RETURN-MISMATCH", owner.path(),
                    enclosing.getNameAsString() + " returns " + expected + " but delegated generated method "
                            + call.getNameAsString() + " returns " + target.returnType()));
        }
    }

    private String inferExpressionType(Expression expression, Map<String, String> variables,
                                       Map<String, TypeModel> models) {
        if (expression instanceof NameExpr nameExpr) return variables.get(nameExpr.getNameAsString());
        if (expression instanceof ObjectCreationExpr creation) return normalizeType(creation.getType().asString());
        if (expression instanceof MethodCallExpr call) {
            ResolvedCall resolved = resolveCall(call, variables, models);
            if (resolved != null && resolved.candidates().size() == 1) return resolved.candidates().get(0).returnType();
        }
        return null;
    }

    private List<MethodModel> allMethods(TypeModel target, Map<String, TypeModel> models) {
        List<MethodModel> methods = new ArrayList<>();
        collectMethods(target, models, methods, new LinkedHashSet<>());
        return methods;
    }

    private void collectMethods(TypeModel target, Map<String, TypeModel> models,
                                List<MethodModel> methods, Set<String> visiting) {
        if (target == null || !visiting.add(target.name())) return;
        methods.addAll(target.methods());
        for (String parentName : target.externalParents()) {
            TypeModel parent = models.get(simpleType(parentName));
            if (parent != null) collectMethods(parent, models, methods, visiting);
        }
        visiting.remove(target.name());
    }

    private boolean compatible(String expected, String actual, Map<String, TypeModel> models) {
        if (compatible(expected, actual)) return true;
        String expectedSimple = simpleType(expected);
        String actualSimple = simpleType(actual);
        TypeModel actualModel = models.get(actualSimple);
        return inheritsFrom(actualModel, expectedSimple, models, new LinkedHashSet<>());
    }

    private boolean inheritsFrom(TypeModel actual, String expected, Map<String, TypeModel> models,
                                 Set<String> visiting) {
        if (actual == null || !visiting.add(actual.name())) return false;
        for (String parentName : actual.externalParents()) {
            String parent = simpleType(parentName);
            if (expected.equals(parent)) return true;
            if (inheritsFrom(models.get(parent), expected, models, visiting)) return true;
        }
        return false;
    }

    private boolean compatible(String expected, String actual) {
        if (expected == null || actual == null) return true;
        String left = normalizeType(expected);
        String right = normalizeType(actual);
        if (left.equals(right)) return true;
        return box(left).equals(box(right));
    }

    private String box(String type) {
        return switch (type) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            case "double" -> "Double";
            default -> type;
        };
    }

    private String normalizeType(String type) {
        if (type == null) return null;
        return type.replace("? extends ", "").replace("? super ", "")
                .replaceAll("\\s+", "").replace("java.util.", "");
    }

    private String simpleType(String type) {
        String normalized = normalizeType(type);
        int generic = normalized.indexOf('<');
        String raw = generic >= 0 ? normalized.substring(0, generic) : normalized;
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private List<GeneratedProjectValidator.Issue> deduplicate(List<GeneratedProjectValidator.Issue> issues) {
        Map<String, GeneratedProjectValidator.Issue> unique = new LinkedHashMap<>();
        for (GeneratedProjectValidator.Issue issue : issues) {
            unique.putIfAbsent(issue.rule() + "|" + issue.filePath() + "|" + issue.message(), issue);
        }
        return new ArrayList<>(unique.values());
    }

    private GeneratedProjectValidator.Issue error(String rule, String path, String message) {
        return new GeneratedProjectValidator.Issue("ERROR", rule, path, message);
    }

    private String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private record TypeModel(String name, String path, Map<String, String> fields,
                             List<MethodModel> methods, String mapperType, Set<String> externalParents) { }
    private record MethodModel(String name, String returnType, List<String> parameterTypes) { }
    private record ResolvedCall(TypeModel target, List<MethodModel> candidates, String scopeName) { }
}
