package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springblade.aiworkflow.agent.CanonicalPlanContractV2;

import java.util.List;

@Data
@Schema(description = "Plan receive request")
public class PlanReceiveRequest {

    @NotBlank(message = "projectId is required")
    @Size(max = 100, message = "projectId must not exceed 100 characters")
    @Schema(description = "Part A project ID")
    private String projectId;

    @NotBlank(message = "projectName is required")
    @Size(max = 200, message = "projectName must not exceed 200 characters")
    @Schema(description = "Project name")
    private String projectName;

    @NotNull(message = "masterPlan is required")
    @Valid
    @Schema(description = "Master plan")
    private MasterPlanVO masterPlan;

    @NotEmpty(message = "subPlans must not be empty")
    @Size(max = 50, message = "subPlans must not contain more than 50 entries")
    @Valid
    @Schema(description = "Sub-plan list")
    private List<@NotNull @Valid SubPlanVO> subPlans;

    @Valid
    @Schema(description = "Request metadata")
    private MetadataVO metadata;

    @Valid
    @Schema(description = "Canonical generation identity shared by every sub-plan")
    private GenerationIdentityVO generationIdentity;

    @Valid
    @Schema(description = "Canonical Plan Contract v2; required for the v2 workflow")
    private CanonicalPlanContractV2 canonicalContract;

    @Valid
    @Schema(description = "Persisted review evidence manifest")
    private ReviewManifestVO reviewManifest;

    @Size(max = 64)
    @Schema(description = "SHA-256 hash of the complete reviewed bundle")
    private String bundleHash;

    @Size(max = 64)
    @Schema(description = "HMAC-SHA256 credential issued by trusted Part A")
    private String bundleSignature;

    /** Empty values default to ISOLATED; all other values are rejected. */
    @Schema(description = "Write target: ISOLATED", defaultValue = "ISOLATED")
    private String writeTarget;

    @Data
    @Schema(description = "Master plan")
    public static class MasterPlanVO {
        @NotBlank(message = "masterPlan.id is required")
        @Size(max = 100)
        private String id;

        @Min(value = 1, message = "masterPlan.version must be positive")
        private Integer version;

        @NotBlank(message = "masterPlan.content is required")
        @Size(max = 1_000_000, message = "masterPlan.content is too large")
        private String content;
    }

    @Data
    @Schema(description = "Sub-plan")
    public static class SubPlanVO {
        @NotBlank(message = "subPlan.id is required")
        @Size(max = 100)
        private String id;

        @NotNull(message = "subPlan.index is required")
        @Min(value = 1, message = "subPlan.index must be positive")
        private Integer index;

        @NotBlank(message = "subPlan.title is required")
        @Size(max = 200)
        private String title;

        @NotBlank(message = "subPlan.content is required")
        @Size(max = 1_000_000, message = "subPlan.content is too large")
        private String content;

        @Size(max = 50, message = "A sub-plan must not have more than 50 prerequisites")
        private List<@NotBlank @Size(max = 100) String> prerequisites;

        @Size(max = 50)
        private List<@NotBlank @Size(max = 150) String> deliverableIds;

        @Size(max = 64)
        private String contractHash;

        @Size(max = 200)
        private List<@NotBlank @Size(max = 150) String> referencedElementIds;

        @Size(max = 100)
        private List<@NotBlank @Size(max = 150) String> inputTypes;

        @Size(max = 100)
        private List<@NotBlank @Size(max = 150) String> outputTypes;
    }

    @Data
    @Schema(description = "Request metadata")
    public static class MetadataVO {
        @Size(max = 100)
        private String sourceService;
        @Size(max = 100)
        private String generatedBy;
        @Size(max = 100)
        private String transmittedAt;
    }
    @Data
    @Schema(description = "Canonical generation identity")
    public static class GenerationIdentityVO {
        @Size(max = 50)
        private String moduleName;
        @Size(max = 100)
        private String entityName;
        @Size(max = 100)
        private String tableName;
        @Size(max = 200)
        private String basePackage;
        @Size(max = 100)
        private String apiModuleName;
        @Size(max = 100)
        private String serviceModuleName;
        @Size(max = 100)
        private String serviceName;
    }

    @Data
    @Schema(description = "Review manifest")
    public static class ReviewManifestVO {
        @Size(max = 100)
        private String masterReviewId;
        @Size(max = 64)
        private String masterContentHash;
        @Size(max = 64)
        private String contractHash;
        @Size(max = 100)
        private String rulesetVersion;
        @Size(max = 100)
        private String referenceSnapshotId;
        @Valid
        @Size(max = 50)
        private List<SubPlanReviewVO> subPlanReviews;
    }

    @Data
    @Schema(description = "Sub-plan review evidence")
    public static class SubPlanReviewVO {
        @Size(max = 100)
        private String subPlanId;
        @Size(max = 100)
        private String reviewId;
        @Size(max = 64)
        private String contentHash;
    }

}
