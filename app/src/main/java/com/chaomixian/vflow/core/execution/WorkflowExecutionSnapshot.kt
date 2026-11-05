package com.chaomixian.vflow.core.execution

/**
 * 工作流执行状态（用于外部查询，如 MCP 服务）
 */
enum class WorkflowExecutionStatus {
    RUNNING,
    COMPLETED,
    CANCELLED
}

/**
 * 一次工作流执行的结果快照。
 *
 * WorkflowExecutor.execute() 是「发射后不管」的异步入口，原本不对外暴露执行结果。
 * MCP 服务需要拿到「停止并返回」的返回值与命名变量，故在执行的开始/结束各留一份快照。
 */
data class WorkflowExecutionSnapshot(
    val executionInstanceId: String,
    val workflowId: String,
    val workflowName: String,
    val status: WorkflowExecutionStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** 「停止并返回」的值；工作流未显式返回时为 null */
    val returnValue: String? = null,
    /** 执行结束时的命名变量快照（已做变量解析） */
    val namedVariables: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    val duration: Long?
        get() = finishedAt?.let { it - startedAt }
}
