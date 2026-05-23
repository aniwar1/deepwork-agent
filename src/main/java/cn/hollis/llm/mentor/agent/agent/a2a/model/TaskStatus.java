package cn.hollis.llm.mentor.agent.agent.a2a.model;

/**
 * A2A 任务状态枚举
 * 遵循 A2A Protocol v1.0.0 规范
 */
public enum TaskStatus {

    /**
     * 任务已提交，等待处理
     */
    SUBMITTED,

    /**
     * 任务正在处理中
     */
    WORKING,

    /**
     * 需要用户额外输入
     */
    INPUT_REQUIRED,

    /**
     * 需要认证
     */
    AUTH_REQUIRED,

    /**
     * 任务已完成
     */
    COMPLETED,

    /**
     * 任务失败
     */
    FAILED,

    /**
     * 任务被取消
     */
    CANCELED,

    /**
     * 任务被拒绝
     */
    REJECTED
}
