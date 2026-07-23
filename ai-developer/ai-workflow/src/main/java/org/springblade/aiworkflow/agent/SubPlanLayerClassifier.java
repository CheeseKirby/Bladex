package org.springblade.aiworkflow.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically classifies a reviewed sub-plan into generation layers. */
final class SubPlanLayerClassifier {

    private static final Pattern TARGET_LAYER = Pattern.compile(
            "(?im)^\\s*#{1,6}\\s*(?:\\u76ee\\u6807\\u5c42|target\\s+layer)\\s*[:\\uff1a]\\s*(.+?)\\s*$");

    private static final Pattern GENERIC_MODULE_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:api|service)\\s*(?:\\u6a21\\u5757|module)\\s*"
                    + "(?:\\([^)]*\\)|\\uff08[^\\uff09]*\\uff09)?\\s*(?:[-\\u2014:]\\s*)?");

    private SubPlanLayerClassifier() {
    }

    static Classification classify(String title, String content) {
        String declaredLayer = extractDeclaredLayer(content);
        String declaredSignal = declaredLayer == null ? "" : normalizeDeclaredLayer(declaredLayer);
        String signal = declaredSignal.isBlank() ? safe(title) : declaredSignal + "\n" + safe(title);
        String lower = signal.toLowerCase(Locale.ROOT);

        boolean feign = lower.contains("feign") || signal.contains("\u8fdc\u7a0b");
        boolean service = !feign && (lower.contains("service") || signal.contains("\u670d\u52a1"));
        boolean mapper = !feign && (lower.contains("mapper") || service);
        boolean controller = !feign && (lower.contains("controller") || signal.contains("\u63a7\u5236\u5668")
                || (declaredLayer == null && containsStandaloneApi(lower)));
        boolean wrapper = !feign && (lower.contains("wrapper") || signal.contains("\u5305\u88c5") || controller);

        return new Classification(
                lower.contains("ddl") || lower.contains("sql") || signal.contains("\u6570\u636e\u5e93")
                        || signal.contains("\u5efa\u8868"),
                lower.contains("entity") || signal.contains("\u5b9e\u4f53"),
                lower.contains("vo") || signal.contains("\u89c6\u56fe"),
                mapper,
                service,
                wrapper,
                controller,
                lower.contains("excel") || signal.contains("\u5bfc\u5165\u5bfc\u51fa"),
                feign,
                declaredLayer);
    }

    private static String normalizeDeclaredLayer(String declaredLayer) {
        String primary = primaryLayerExpression(declaredLayer);
        String withoutGenericModule = GENERIC_MODULE_PREFIX.matcher(primary).replaceFirst("").trim();
        return withoutGenericModule;
    }

    private static String primaryLayerExpression(String declaredLayer) {
        int asciiParenthesis = declaredLayer.indexOf('(');
        int fullWidthParenthesis = declaredLayer.indexOf('\uff08');
        int qualifierStart;
        if (asciiParenthesis < 0) {
            qualifierStart = fullWidthParenthesis;
        } else if (fullWidthParenthesis < 0) {
            qualifierStart = asciiParenthesis;
        } else {
            qualifierStart = Math.min(asciiParenthesis, fullWidthParenthesis);
        }
        if (qualifierStart < 0) return declaredLayer;
        String qualifier = declaredLayer.substring(qualifierStart).toLowerCase(Locale.ROOT);
        return qualifier.contains("module") || qualifier.contains("\u6a21\u5757")
                ? declaredLayer.substring(0, qualifierStart).trim()
                : declaredLayer;
    }

    private static String extractDeclaredLayer(String content) {
        Matcher matcher = TARGET_LAYER.matcher(safe(content));
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static boolean containsStandaloneApi(String lower) {
        return Pattern.compile("(?:^|[^a-z0-9])api(?:$|[^a-z0-9])").matcher(lower).find();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    record Classification(boolean ddl, boolean entity, boolean vo, boolean mapper, boolean service,
                          boolean wrapper, boolean controller, boolean excel, boolean feign,
                          String declaredLayer) {
    }
}
