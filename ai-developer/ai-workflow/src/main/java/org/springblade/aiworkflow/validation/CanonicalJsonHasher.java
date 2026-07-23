package org.springblade.aiworkflow.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springblade.aiworkflow.agent.CanonicalPlanContractV2;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Cross-language SHA-256 rules used by Part A and Part B. */
public final class CanonicalJsonHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final Pattern CONTRACT_BLOCK = Pattern.compile("```plan-contract\\s*[\\s\\S]*?```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTRACT_HEADING = Pattern.compile("\\n*## Machine-readable plan contract\\s*", Pattern.CASE_INSENSITIVE);

    private CanonicalJsonHasher() { }

    public static String contentHash(String markdown) {
        String value = markdown == null ? "" : markdown;
        value = CONTRACT_HEADING.matcher(value).replaceAll("\n");
        value = CONTRACT_BLOCK.matcher(value).replaceAll("").stripTrailing();
        return sha256(value);
    }

    /** Stable database idempotency key for one reviewed intake bundle. */
    public static String idempotencyKey(PlanReceiveRequest request) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("projectId", request == null ? null : request.getProjectId());
        String bundleHash = request == null ? null : request.getBundleHash();
        if (bundleHash != null && !bundleHash.isBlank()) {
            material.put("mode", "CANONICAL_V2");
            material.put("bundleHash", bundleHash);
        } else {
            material.put("mode", "LEGACY_INFERRED");
            String content = request == null || request.getMasterPlan() == null
                    ? null : request.getMasterPlan().getContent();
            material.put("contentHash", contentHash(content));
        }
        return sha256(stableJson(MAPPER.valueToTree(material)));
    }

    public static String contractHash(CanonicalPlanContractV2 contract) {
        JsonNode tree = MAPPER.valueToTree(contract);
        if (tree instanceof ObjectNode object) {
            object.remove("sourceHash");
            object.remove("referenceSnapshotId");
        }
        return sha256(stableJson(tree));
    }

    public static String bundleHash(PlanReceiveRequest request, String contractHash) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("projectId", request.getProjectId());
        material.put("writeTarget", org.springblade.aiworkflow.enums.WriteTarget.parse(request.getWriteTarget()).getCode());
        PlanReceiveRequest.GenerationIdentityVO identity = request.getGenerationIdentity();
        Map<String, Object> generationIdentity = new LinkedHashMap<>();
        generationIdentity.put("moduleName", identity == null ? null : identity.getModuleName());
        generationIdentity.put("entityName", identity == null ? null : identity.getEntityName());
        generationIdentity.put("tableName", identity == null ? null : identity.getTableName());
        generationIdentity.put("basePackage", identity == null ? null : identity.getBasePackage());
        generationIdentity.put("apiModuleName", identity == null ? null : identity.getApiModuleName());
        generationIdentity.put("serviceModuleName", identity == null ? null : identity.getServiceModuleName());
        generationIdentity.put("serviceName", identity == null ? null : identity.getServiceName());
        material.put("generationIdentity", generationIdentity);
        Map<String, Object> masterPlan = new LinkedHashMap<>();
        masterPlan.put("id", request.getMasterPlan().getId());
        masterPlan.put("version", request.getMasterPlan().getVersion());
        masterPlan.put("contentHash", contentHash(request.getMasterPlan().getContent()));
        material.put("masterPlan", masterPlan);
        material.put("contractHash", contractHash);
        List<PlanReceiveRequest.SubPlanVO> orderedSubPlans = new ArrayList<>(request.getSubPlans());
        orderedSubPlans.sort(Comparator
                .comparing((PlanReceiveRequest.SubPlanVO item) -> item.getIndex() == null ? Integer.MAX_VALUE : item.getIndex())
                .thenComparing(item -> nullToEmpty(item.getId())));
        List<Map<String, Object>> subPlans = new ArrayList<>();
        for (PlanReceiveRequest.SubPlanVO subPlan : orderedSubPlans) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", subPlan.getId());
            item.put("index", subPlan.getIndex());
            item.put("title", subPlan.getTitle());
            item.put("contentHash", contentHash(subPlan.getContent()));
            item.put("prerequisites", sortedCopy(subPlan.getPrerequisites()));
            item.put("deliverableIds", sortedCopy(subPlan.getDeliverableIds()));
            item.put("contractHash", subPlan.getContractHash());
            item.put("referencedElementIds", sortedCopy(subPlan.getReferencedElementIds()));
            item.put("inputTypes", sortedCopy(subPlan.getInputTypes()));
            item.put("outputTypes", sortedCopy(subPlan.getOutputTypes()));
            subPlans.add(item);
        }
        material.put("subPlans", subPlans);
        return sha256(stableJson(MAPPER.valueToTree(material)));
    }

    /** HMAC-SHA256 credential over the bundle hash and persisted review evidence. */
    public static String bundleSignature(PlanReceiveRequest request, String secret) {
        if (request == null || request.getReviewManifest() == null) {
            throw new IllegalArgumentException("Review manifest is required for bundle signing");
        }
        String normalizedSecret = secret == null ? "" : secret.trim();
        if (normalizedSecret.isEmpty()) {
            throw new IllegalStateException("Plan bundle signing secret is not configured");
        }
        PlanReceiveRequest.ReviewManifestVO manifest = request.getReviewManifest();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("bundleHash", nullToEmpty(request.getBundleHash()));
        material.put("masterReviewId", nullToEmpty(manifest.getMasterReviewId()));
        material.put("masterContentHash", nullToEmpty(manifest.getMasterContentHash()));
        material.put("contractHash", nullToEmpty(manifest.getContractHash()));
        material.put("rulesetVersion", nullToEmpty(manifest.getRulesetVersion()));
        material.put("referenceSnapshotId", nullToEmpty(manifest.getReferenceSnapshotId()));
        List<Map<String, Object>> reviews = new ArrayList<>();
        if (manifest.getSubPlanReviews() != null) {
            manifest.getSubPlanReviews().stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing((PlanReceiveRequest.SubPlanReviewVO item) -> nullToEmpty(item.getSubPlanId()))
                            .thenComparing(item -> nullToEmpty(item.getReviewId()))
                            .thenComparing(item -> nullToEmpty(item.getContentHash())))
                    .forEach(review -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("subPlanId", nullToEmpty(review.getSubPlanId()));
                        item.put("reviewId", nullToEmpty(review.getReviewId()));
                        item.put("contentHash", nullToEmpty(review.getContentHash()));
                        reviews.add(item);
                    });
        }
        material.put("subPlanReviews", reviews);
        return hmacSha256(stableJson(MAPPER.valueToTree(material)), normalizedSecret);
    }

    public static boolean verifyBundleSignature(PlanReceiveRequest request, String secret) {
        String actual = request == null ? null : request.getBundleSignature();
        if (actual == null || !actual.matches("(?i)[0-9a-f]{64}")) return false;
        String expected = bundleSignature(request, secret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static String stableJson(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(stableJson(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            List<String> entries = new ArrayList<>();
            for (String name : names) entries.add(jsonString(name) + ":" + stableJson(node.get(name)));
            return "{" + String.join(",", entries) + "}";
        }
        return node.toString();
    }

    private static String jsonString(String value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Unable to canonicalize JSON", error); }
    }

    private static List<String> sortedCopy(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(values);
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
