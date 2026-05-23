package cn.hollis.llm.mentor.agent.agent.a2a;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理决策结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileHandoffDecision {

    private FileHandoffStrategy strategy;

    private String targetAgent;

    private String skillId;

    private String reasoning;

    private int priority;

    public static FileHandoffDecision continueTask(String reasoning) {
        return new FileHandoffDecision(
                FileHandoffStrategy.CONTINUE,
                null,
                null,
                reasoning,
                0
        );
    }

    public static FileHandoffDecision interrupt(String targetAgent, String skillId, String reasoning) {
        return new FileHandoffDecision(
                FileHandoffStrategy.INTERRUPT,
                targetAgent,
                skillId,
                reasoning,
                1
        );
    }

    public static FileHandoffDecision parallel(String targetAgent, String skillId, String reasoning) {
        return new FileHandoffDecision(
                FileHandoffStrategy.PARALLEL,
                targetAgent,
                skillId,
                reasoning,
                2
        );
    }
}
