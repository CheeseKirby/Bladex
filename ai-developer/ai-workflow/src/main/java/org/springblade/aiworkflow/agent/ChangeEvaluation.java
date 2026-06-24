package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 改动评估结果
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangeEvaluation {

    /** 操作: CREATE/MODIFY/SKIP */
    private String action;

    /** 评估原因 */
    private String reason;

    /** 警告信息列表 */
    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public static ChangeEvaluation skip(String reason) {
        ChangeEvaluation eval = new ChangeEvaluation();
        eval.setAction("SKIP");
        eval.setReason(reason);
        return eval;
    }

    public static ChangeEvaluation create(String reason) {
        ChangeEvaluation eval = new ChangeEvaluation();
        eval.setAction("CREATE");
        eval.setReason(reason);
        return eval;
    }

    public static ChangeEvaluation modify(String reason) {
        ChangeEvaluation eval = new ChangeEvaluation();
        eval.setAction("MODIFY");
        eval.setReason(reason);
        return eval;
    }
}
