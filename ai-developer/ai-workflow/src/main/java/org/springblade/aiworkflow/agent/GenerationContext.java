package org.springblade.aiworkflow.agent;

/** Immutable execution context shared by all tasks of one received plan. */
public record GenerationContext(
        GenerationIdentity identity,
        ReferenceFrameworkProfile referenceProfile,
        CanonicalDomainContract domainContract,
        CanonicalPlanContractV2 planContract) {

    public GenerationContext(GenerationIdentity identity, ReferenceFrameworkProfile referenceProfile) {
        this(identity, referenceProfile, CanonicalDomainContract.empty(identity), null);
    }

    public GenerationContext(GenerationIdentity identity, ReferenceFrameworkProfile referenceProfile,
                             CanonicalDomainContract domainContract) {
        this(identity, referenceProfile, domainContract, null);
    }

    public GenerationContext {
        if (identity == null) throw new IllegalArgumentException("Generation identity is required");
        if (referenceProfile == null) referenceProfile = ReferenceFrameworkProfile.defaults();
        if (domainContract == null) domainContract = CanonicalDomainContract.empty(identity);
        if (domainContract.identity() != null && !identity.equals(domainContract.identity())) {
            throw new IllegalArgumentException("Domain contract identity does not match generation identity");
        }
        if (planContract != null && !identity.equals(planContract.generationIdentity())) {
            throw new IllegalArgumentException("Canonical plan contract identity does not match generation identity");
        }
    }

    public boolean contractV2() {
        return planContract != null && planContract.isV2();
    }
}
