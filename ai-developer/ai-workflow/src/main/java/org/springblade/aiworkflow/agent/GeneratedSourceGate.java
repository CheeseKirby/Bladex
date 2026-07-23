package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dependency-independent source gate for generated output.
 *
 * <p>This gate deliberately does not resolve project classes or third-party dependencies. It verifies the part that
 * is always decidable in an isolated output directory: source syntax, XML safety/well-formedness, Markdown residue,
 * and Java physical-path/package/type alignment.</p>
 */
public final class GeneratedSourceGate {

    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "(?im)^\\s*```(?:java|xml|sql|yaml|yml|properties)?\\s*$");
    private static final Pattern ENTITY_DECLARATION = Pattern.compile("(?is)<!ENTITY\\b");
    private static final Pattern INCOMPLETE_PLACEHOLDER = Pattern.compile("(?i)\\b(?:TODO|FIXME)\\b");
    private static final Pattern INTERNAL_DOCTYPE_SUBSET = Pattern.compile("(?is)<!DOCTYPE[^>]*\\[");
    private static final Pattern MYBATIS_MAPPER_DOCTYPE = Pattern.compile(
            "(?is)<!DOCTYPE\\s+mapper\\s+PUBLIC\\s+\"-//mybatis\\.org//DTD Mapper 3\\.0//EN\"\\s+"
                    + "\"https?://mybatis\\.org/dtd/mybatis-3-mapper\\.dtd\"\\s*>");

    private final JavaParser javaParser;
    private final GeneratedTypeClosureValidator typeClosureValidator = new GeneratedTypeClosureValidator();

    public GeneratedSourceGate() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        this.javaParser = new JavaParser(configuration);
    }

    public List<GeneratedProjectValidator.Issue> validate(List<GeneratedFile> files) {
        List<GeneratedProjectValidator.Issue> issues = new ArrayList<>();
        if (files == null) return issues;
        for (GeneratedFile file : files) {
            if (file == null || file.getFilePath() == null) continue;
            String path = normalize(file.getFilePath());
            String content = file.getContent();
            if (!isSourceFile(path)) continue;
            if (content == null || content.isBlank()) {
                issues.add(error("SOURCE-EMPTY", path, "Generated source file is empty"));
                continue;
            }
            if (hasOuterMarkdownFence(content)) {
                issues.add(error("SOURCE-MARKDOWN-FENCE", path,
                        "Generated source still contains an outer Markdown code fence"));
            }
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(".java")) {
                validateJava(path, content, issues);
            } else if (lowerPath.endsWith(".xml")) {
                validateXml(path, content, issues);
            }
        }
        issues.addAll(typeClosureValidator.validate(files));
        return issues;
    }

    public static boolean isSourceGateRule(String rule) {
        return rule != null && (rule.startsWith("SOURCE-")
                || rule.startsWith("JAVA-SYNTAX")
                || rule.startsWith("JAVA-PACKAGE")
                || rule.startsWith("JAVA-TOPLEVEL")
                || rule.startsWith("JAVA-IMPORT")
                || rule.startsWith("TYPE-")
                || rule.startsWith("XML-SYNTAX")
                || rule.startsWith("XML-UNSAFE"));
    }

    private void validateJava(String path, String content, List<GeneratedProjectValidator.Issue> issues) {
        ParseResult<CompilationUnit> result = javaParser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            issues.add(error("JAVA-SYNTAX-INVALID", path, summarizeProblems(result.getProblems())));
            return;
        }
        CompilationUnit unit = result.getResult().orElseThrow();
        validateIncompletePlaceholders(path, unit, issues);
        validatePackagePath(path, unit, issues);
        validateTopLevelType(path, unit, issues);
        validateKnownRequiredImports(path, content, unit, issues);
    }

    private void validateIncompletePlaceholders(String path, CompilationUnit unit,
                                                List<GeneratedProjectValidator.Issue> issues) {
        unit.getAllContainedComments().stream()
                .map(comment -> comment.getContent() == null ? "" : comment.getContent().strip())
                .filter(comment -> INCOMPLETE_PLACEHOLDER.matcher(comment).find())
                .findFirst()
                .ifPresent(comment -> issues.add(error("SOURCE-INCOMPLETE-PLACEHOLDER", path,
                        "Generated Java source contains an unfinished TODO/FIXME comment: "
                                + firstLine(comment, "unfinished implementation placeholder"))));
    }

    private void validateKnownRequiredImports(String path, String content, CompilationUnit unit,
                                              List<GeneratedProjectValidator.Issue> issues) {
        requireImport(path, content, unit, "@JsonSerialize", "JsonSerialize",
                "com.fasterxml.jackson.databind.annotation.JsonSerialize", issues);
        requireImport(path, content, unit, "ToStringSerializer.class", "ToStringSerializer",
                "com.fasterxml.jackson.databind.ser.std.ToStringSerializer", issues);
    }

    private void requireImport(String path, String content, CompilationUnit unit,
                               String usage, String simpleName, String fqcn,
                               List<GeneratedProjectValidator.Issue> issues) {
        if (!content.contains(usage) || content.contains(fqcn)) return;
        boolean imported = unit.getImports().stream()
                .anyMatch(item -> !item.isAsterisk() && item.getNameAsString().equals(fqcn));
        if (!imported) {
            issues.add(error("JAVA-IMPORT-MISSING", path,
                    simpleName + " is used without required import " + fqcn));
        }
    }

    private void validatePackagePath(String path, CompilationUnit unit,
                                     List<GeneratedProjectValidator.Issue> issues) {
        String marker = "src/main/java/";
        int markerIndex = path.indexOf(marker);
        int lastSlash = path.lastIndexOf('/');
        if (markerIndex < 0 || lastSlash <= markerIndex + marker.length()) return;
        String expectedPackage = path.substring(markerIndex + marker.length(), lastSlash).replace('/', '.');
        String declaredPackage = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()).orElse("");
        if (!expectedPackage.equals(declaredPackage)) {
            issues.add(error("JAVA-PACKAGE-PATH-MISMATCH", path,
                    "Declared package '" + declaredPackage + "' does not match physical package '"
                            + expectedPackage + "'"));
        }
    }

    private void validateTopLevelType(String path, CompilationUnit unit,
                                      List<GeneratedProjectValidator.Issue> issues) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!fileName.endsWith(".java") || "package-info.java".equals(fileName)
                || "module-info.java".equals(fileName)) return;
        String expectedType = fileName.substring(0, fileName.length() - ".java".length());
        boolean matched = unit.getTypes().stream().anyMatch(type -> expectedType.equals(type.getNameAsString()));
        if (!matched) {
            issues.add(error("JAVA-TOPLEVEL-TYPE-MISMATCH", path,
                    "No top-level type named " + expectedType + " was found in the generated file"));
        }
    }

    private void validateXml(String path, String content, List<GeneratedProjectValidator.Issue> issues) {
        if (ENTITY_DECLARATION.matcher(content).find() || INTERNAL_DOCTYPE_SUBSET.matcher(content).find()) {
            issues.add(error("XML-UNSAFE-DOCTYPE", path,
                    "XML entity declarations and internal DOCTYPE subsets are forbidden"));
            return;
        }
        int doctypeIndex = content.toUpperCase(Locale.ROOT).indexOf("<!DOCTYPE");
        if (doctypeIndex >= 0 && !MYBATIS_MAPPER_DOCTYPE.matcher(content).find()) {
            issues.add(error("XML-UNSAFE-DOCTYPE", path,
                    "Only the standard MyBatis mapper DOCTYPE is allowed; external entity expansion is disabled"));
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((_publicId, _systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            builder.parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            issues.add(error("XML-SYNTAX-INVALID", path, firstLine(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    private String summarizeProblems(List<Problem> problems) {
        if (problems == null || problems.isEmpty()) return "JavaParser rejected the generated source";
        return problems.stream().limit(3)
                .map(problem -> firstLine(problem.getMessage(), "Java syntax error"))
                .reduce((left, right) -> left + " | " + right)
                .orElse("JavaParser rejected the generated source");
    }

    private String firstLine(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        int newline = value.indexOf('\n');
        String first = newline >= 0 ? value.substring(0, newline) : value;
        return first.length() <= 500 ? first : first.substring(0, 500);
    }

    private boolean hasOuterMarkdownFence(String content) {
        String trimmed = content == null ? "" : content.strip();
        return trimmed.startsWith("```") || trimmed.endsWith("```");
    }

    private boolean isSourceFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".xml");
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private GeneratedProjectValidator.Issue error(String rule, String path, String message) {
        return new GeneratedProjectValidator.Issue("ERROR", rule, path, message);
    }
}
