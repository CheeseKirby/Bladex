package org.springblade.aiworkflow.agent;

/**
 * Intent-scoped reference project lookup. The request is deliberately small so callers cannot
 * fetch the complete source index through the review endpoint.
 */
public record ReferenceSearchRequest(String intent, Integer topK, Integer relationDepth) {

    public int normalizedTopK() {
        if (topK == null) return 20;
        return Math.max(1, Math.min(topK, 40));
    }

    public int normalizedRelationDepth() {
        if (relationDepth == null) return 2;
        return Math.max(0, Math.min(relationDepth, 3));
    }
}
