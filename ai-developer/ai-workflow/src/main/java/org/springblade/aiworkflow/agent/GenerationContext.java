package org.springblade.aiworkflow.agent;

/** Immutable execution context shared by all tasks of one received plan. */
public record GenerationContext(GenerationIdentity identity, ReferenceFrameworkProfile referenceProfile) {
    public GenerationContext {
        if (identity == null) throw new IllegalArgumentException("Generation identity is required");
        if (referenceProfile == null) referenceProfile = ReferenceFrameworkProfile.defaults();
    }
}
