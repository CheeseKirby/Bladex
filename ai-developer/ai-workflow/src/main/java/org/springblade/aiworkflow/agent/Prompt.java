package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM Prompt
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Prompt {

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词 */
    private String userPrompt;
}
