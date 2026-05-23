package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.BaseAgent;
import cn.hollis.llm.mentor.agent.agent.deepresearch.PlanExecuteAgent;
import cn.hollis.llm.mentor.agent.entity.AiSession;
import cn.hollis.llm.mentor.agent.entity.vo.SaveQuestionRequest;
import cn.hollis.llm.mentor.agent.service.AgentTaskManager;
import cn.hollis.llm.mentor.agent.service.AiSessionService;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A Orchestrator Agent - 编排器
 * 作为主入口 Agent，负责：
 * 1. 接收用户请求（含文件）
 * 2. 检测文件附件
 * 3. 调用 FileHandoffDecider 决定处理策略
 * 4. 通过 A2A 调用子 Agent 或继续深度思考
 * 5. 合并子 Agent 结果后恢复深度思考
 */
@Slf4j
@Component
public class OrchestratorAgent extends BaseAgent {

    private final PlanExecuteAgent planExecuteAgent;
    private final FileHandoffDecider fileHandoffDecider;
    private final A2aAgentClient a2aAgentClient;
    private final FileParserAgent fileParserAgent;
    private final ChatModel chatModel;

    public OrchestratorAgent(
            ChatModel chatModel,
            PlanExecuteAgent planExecuteAgent,
            FileHandoffDecider fileHandoffDecider,
            A2aAgentClient a2aAgentClient,
            FileParserAgent fileParserAgent,
            AiSessionService sessionService,
            AgentTaskManager taskManager
    ) {
        super("OrchestratorAgent", chatModel, "orchestrator");
        this.chatModel = chatModel;
        this.planExecuteAgent = planExecuteAgent;
        this.fileHandoffDecider = fileHandoffDecider;
        this.a2aAgentClient = a2aAgentClient;
        this.fileParserAgent = fileParserAgent;
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.usedTools = ConcurrentHashMap.newKeySet();

        log.info("OrchestratorAgent 初始化完成");
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        return executeWithFile(conversationId, question, null, null, null);
    }

    /**
     * 带文件附件的执行入口
     */
    public Flux<String> executeWithFile(
            String conversationId,
            String question,
            String fileId,
            String fileName,
            byte[] fileBytes
    ) {
        log.info("OrchestratorAgent 收到请求: conversationId={}, question={}, hasFile={}",
                conversationId, question, fileName != null);

        if (conversationId != null && taskManager != null && taskManager.hasRunningTask(conversationId)) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentTaskManager.TaskInfo taskInfo = null;
        if (conversationId != null && taskManager != null) {
            taskInfo = taskManager.registerTask(conversationId, sink, "orchestrator");
            if (taskInfo == null) {
                return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
            }
        }

        initTimers();
        currentConversationId = conversationId;
        currentQuestion = question;

        Long sessionId = saveQuestion(conversationId, question);

        Schedulers.boundedElastic().schedule(() -> {
            try {
                if (fileName != null && fileBytes != null) {
                    handleWithFile(conversationId, question, fileId, fileName, fileBytes, sink, sessionId);
                } else {
                    handleWithoutFile(conversationId, question, sink, sessionId);
                }
            } catch (Exception e) {
                log.error("OrchestratorAgent 执行异常: {}", e.getMessage(), e);
                sink.tryEmitNext(createErrorResponse("执行异常: " + e.getMessage()));
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux()
                .doOnCancel(() -> {
                    if (currentConversationId != null && taskManager != null) {
                        taskManager.stopTask(currentConversationId);
                    }
                })
                .doFinally(signal -> {
                    if (currentConversationId != null && taskManager != null) {
                        taskManager.stopTask(currentConversationId);
                    }
                });
    }

    private void handleWithoutFile(String conversationId, String question,
                                   Sinks.Many<String> sink, Long sessionId) {
        emit(sink, "正在启动深度思考...", "thinking");

        Flux<String> deepResult = planExecuteAgent.stream(conversationId, question);

        deepResult.subscribe(
                chunk -> sink.tryEmitNext(chunk),
                error -> {
                    log.error("深度思考流异常: {}", error.getMessage());
                    sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                    sink.tryEmitComplete();
                },
                () -> {
                    log.info("深度思考完成");
                    if (sessionId != null) {
                        log.info("会话 {} 完成，已保存 sessionId={}", conversationId, sessionId);
                    }
                }
        );
    }

    private void handleWithFile(String conversationId, String question,
                                String fileId, String fileName, byte[] fileBytes,
                                Sinks.Many<String> sink, Long sessionId) {
        emit(sink, "检测到文件上传，开始决策...", "thinking");

        FileHandoffDecision decision;
        try {
            decision = fileHandoffDecider.decide(
                    fileName,
                    getFileType(fileName),
                    fileBytes != null ? fileBytes.length : 0,
                    question
            );
            log.info("文件处理决策: strategy={}, reasoning={}",
                    decision.getStrategy(), decision.getReasoning());
        } catch (Exception e) {
            log.error("决策失败，默认继续: {}", e.getMessage());
            decision = FileHandoffDecision.continueTask("决策异常，默认继续");
        }

        switch (decision.getStrategy()) {
            case CONTINUE:
                handleContinue(conversationId, question, fileName, sink);
                break;
            case INTERRUPT:
                handleInterrupt(conversationId, question, fileId, fileName, fileBytes, decision, sink);
                break;
            case PARALLEL:
                handleParallel(conversationId, question, fileId, fileName, fileBytes, decision, sink);
                break;
            default:
                handleContinue(conversationId, question, fileName, sink);
        }
    }

    private void handleContinue(String conversationId, String question,
                                String fileName, Sinks.Many<String> sink) {
        emit(sink, "文件 [ " + fileName + " ] 与当前任务无关，继续深度思考...", "thinking");

        Flux<String> deepResult = planExecuteAgent.stream(conversationId, question);

        deepResult.subscribe(
                chunk -> sink.tryEmitNext(chunk),
                error -> {
                    sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                    sink.tryEmitComplete();
                },
                () -> sink.tryEmitComplete()
        );
    }

    private void handleInterrupt(String conversationId, String question,
                                String fileId, String fileName, byte[] fileBytes,
                                FileHandoffDecision decision, Sinks.Many<String> sink) {
        emit(sink, "文件 [" + fileName + "] 需要专门处理，正在中断当前思考...", "thinking");

        String parsedContent;
        try {
            emit(sink, "正在解析文件内容...", "thinking");

            parsedContent = fileParserAgent.parseFile(fileId, fileName, fileBytes, null);

            emit(sink, "文件解析完成，综合分析中...", "thinking");

            String enhancedQuestion = buildEnhancedQuestion(question, fileName, parsedContent);

            Flux<String> deepResult = planExecuteAgent.stream(conversationId, enhancedQuestion);

            deepResult.subscribe(
                    chunk -> sink.tryEmitNext(chunk),
                    error -> {
                        sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                        sink.tryEmitComplete();
                    },
                    () -> sink.tryEmitComplete()
            );
        } catch (Exception e) {
            log.error("文件处理失败: {}", e.getMessage(), e);
            emit(sink, "文件处理失败，继续原任务: " + e.getMessage(), "thinking");

            Flux<String> deepResult = planExecuteAgent.stream(conversationId, question);
            deepResult.subscribe(
                    chunk -> sink.tryEmitNext(chunk),
                    error -> {
                        sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                        sink.tryEmitComplete();
                    },
                    () -> sink.tryEmitComplete()
            );
        }
    }

    private void handleParallel(String conversationId, String question,
                                String fileId, String fileName, byte[] fileBytes,
                                FileHandoffDecision decision, Sinks.Many<String> sink) {
        emit(sink, "正在并行处理文件 [" + fileName + "]，同时启动深度思考...", "thinking");

        Schedulers.boundedElastic().schedule(() -> {
            try {
                String parsedContent = fileParserAgent.parseFile(fileId, fileName, fileBytes, null);

                emit(sink, "\n【文件解析结果】\n" + parsedContent + "\n【深度思考继续】\n", "text");

                String enhancedQuestion = buildEnhancedQuestion(question, fileName, parsedContent);

                Flux<String> deepResult = planExecuteAgent.stream(conversationId, enhancedQuestion);

                deepResult.subscribe(
                        chunk -> sink.tryEmitNext(chunk),
                        error -> {
                            sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                            sink.tryEmitComplete();
                        },
                        () -> sink.tryEmitComplete()
                );
            } catch (Exception e) {
                log.error("并行文件处理失败: {}", e.getMessage());
                emit(sink, "文件并行处理失败，继续深度思考...", "thinking");

                Flux<String> deepResult = planExecuteAgent.stream(conversationId, question);
                deepResult.subscribe(
                        chunk -> sink.tryEmitNext(chunk),
                        error -> {
                            sink.tryEmitNext(createErrorResponse("深度思考异常: " + error.getMessage()));
                            sink.tryEmitComplete();
                        },
                        () -> sink.tryEmitComplete()
                );
            }
        });
    }

    private String buildEnhancedQuestion(String originalQuestion, String fileName, String parsedContent) {
        return originalQuestion + "\n\n" +
                "【附加文件信息】\n" +
                "文件名: " + fileName + "\n" +
                "文件内容摘要:\n" + parsedContent + "\n" +
                "请在深度思考时结合上述文件内容进行综合分析。";
    }

    private String getFileType(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "unknown";
    }

    private Long saveQuestion(String conversationId, String question) {
        if (conversationId != null && sessionService != null) {
            try {
                AiSession saved = sessionService.saveQuestion(
                        SaveQuestionRequest.builder()
                                .sessionId(conversationId)
                                .question(question)
                                .build()
                );
                currentSessionId = saved.getId();
                return saved.getId();
            } catch (Exception e) {
                log.warn("保存问题失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private void emit(Sinks.Many<String> sink, String content, String type) {
        sink.tryEmitNext(createResponse(content, type));
    }
}
