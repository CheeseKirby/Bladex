package org.springblade.aiworkflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Supported generation output target. Direct writes into a reference project are intentionally disabled. */
@Getter
@AllArgsConstructor
public enum WriteTarget {

    ISOLATED("ISOLATED", "isolated output (ai-generated-modules)");

    private final String code;
    private final String desc;

    /** Empty or unknown values resolve to the safe isolated target after request validation. */
    public static WriteTarget parse(String code) {
        return ISOLATED;
    }
}
