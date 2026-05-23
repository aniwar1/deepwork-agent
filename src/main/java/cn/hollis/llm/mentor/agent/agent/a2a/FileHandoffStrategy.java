package cn.hollis.llm.mentor.agent.agent.a2a;

/**
 * 文件处理策略枚举
 */
public enum FileHandoffStrategy {

    /**
     * 忽略文件，继续当前任务
     */
    CONTINUE,

    /**
     * 中断当前任务，交给专门 Agent 处理
     */
    INTERRUPT,

    /**
     * 并行处理文件，不阻塞主流程
     */
    PARALLEL
}
