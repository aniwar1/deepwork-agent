package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.BaseAgent;
import cn.hollis.llm.mentor.agent.entity.vo.UpdateAnswerRequest;
import cn.hollis.llm.mentor.agent.service.AgentTaskManager;
import cn.hollis.llm.mentor.agent.service.AiSessionService;
import cn.hollis.llm.mentor.agent.service.FileParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件解析 Agent
 * 作为 A2A 子 Agent，负责解析各类文件（PDF、DOCX、TXT、图片）
 * 通过 A2A 协议接收任务并返回解析结果
 */
@Slf4j
@Component
public class FileParserAgent extends BaseAgent {

    private final FileParserService fileParserService;
    private final List<ToolCallback> tools;
    private ChatClient chatClient;
    private static final int MAX_ROUNDS = 3;

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一个专业的文件解析助手，擅长从各类文档中提取关键信息。

            ## 能力
            - 解析 PDF、Word (DOCX)、TXT 等文档
            - 从图片中识别文字（OCR）
            - 理解文件结构，提取核心内容

            ## 输出规则
            1. 解析完成后，以结构化方式输出文件内容摘要
            2. 如果文件内容过长，给出关键章节和要点
            3. 最终答案必须包含解析结果的完整文本
            """;

    public FileParserAgent(
            ChatModel chatModel,
            FileParserService fileParserService,
            List<ToolCallback> tools,
            AiSessionService sessionService,
            AgentTaskManager taskManager
    ) {
        super("FileParserAgent", chatModel, "file-parser");
        this.fileParserService = fileParserService;
        this.tools = tools;
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.usedTools = new HashSet<>();
        initChatClient();
    }

    private void initChatClient() {
        if (tools != null && !tools.isEmpty()) {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();
            this.chatClient = ChatClient.builder(chatModel)
                    .defaultOptions(toolOptions)
                    .defaultToolCallbacks(tools)
                    .build();
        } else {
            this.chatClient = ChatClient.builder(chatModel).build();
        }
    }

    /**
     * A2A 入口：接收文件解析任务
     */
    public String parseFile(String fileId, String fileName, byte[] fileBytes, String taskId) {
        log.info("FileParserAgent 收到解析任务: fileId={}, fileName={}, taskId={}",
                fileId, fileName, taskId);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean finished = new AtomicBoolean(false);
        StringBuilder resultBuffer = new StringBuilder();

        try {
            parseFileInternal(fileId, fileName, fileBytes, sink, finished, resultBuffer);

            return resultBuffer.toString();
        } catch (Exception e) {
            log.error("文件解析失败: fileId={}, error={}", fileId, e.getMessage(), e);
            return "文件解析失败: " + e.getMessage();
        }
    }

    /**
     * A2A 流式入口
     */
    public Flux<String> parseFileStream(String fileId, String fileName, byte[] fileBytes, String taskId) {
        log.info("FileParserAgent 收到流式解析任务: fileId={}, fileName={}, taskId={}",
                fileId, fileName, taskId);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean finished = new AtomicBoolean(false);
        StringBuilder resultBuffer = new StringBuilder();

        parseFileInternal(fileId, fileName, fileBytes, sink, finished, resultBuffer);

        return sink.asFlux()
                .doOnNext(resultBuffer::append)
                .doOnComplete(() -> finished.set(true))
                .doOnCancel(() -> finished.set(true));
    }

    private void parseFileInternal(
            String fileId,
            String fileName,
            byte[] fileBytes,
            Sinks.Many<String> sink,
            AtomicBoolean finished,
            StringBuilder resultBuffer
    ) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        String fileType = getFileType(fileName);

        String parsedContent;
        try {
            sink.tryEmitNext(createThinkingResponse("正在解析文件..."));

            if (fileBytes != null && fileBytes.length > 0) {
                parsedContent = parseDirectly(fileName, fileBytes);
            } else if (fileId != null) {
                parsedContent = parseByFileId(fileId);
            } else {
                parsedContent = "文件信息不完整，无法解析";
            }

            sink.tryEmitNext(createThinkingResponse("文件解析完成，正在分析内容..."));
        } catch (Exception e) {
            log.error("文件解析异常: {}", e.getMessage(), e);
            sink.tryEmitNext(createErrorResponse("文件解析失败: " + e.getMessage()));
            sink.tryEmitComplete();
            return;
        }

        messages.add(new UserMessage(
                "请分析以下文件内容，提取关键信息并给出摘要。\n\n" +
                "文件名: " + fileName + "\n" +
                "文件类型: " + fileType + "\n" +
                "文件内容:\n" + parsedContent
        ));

        chatClient.prompt()
                .messages(messages)
                .stream()
                .content()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (!finished.get()) {
                        sink.tryEmitNext(createTextResponse(chunk));
                    }
                })
                .doOnComplete(() -> {
                    resultBuffer.append("【文件解析完成】\n");
                    resultBuffer.append("文件名: ").append(fileName).append("\n");
                    resultBuffer.append("类型: ").append(fileType).append("\n");
                    resultBuffer.append("解析结果: ").append(parsedContent);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    log.error("文件分析失败: {}", err.getMessage());
                    sink.tryEmitNext(createErrorResponse("文件分析失败: " + err.getMessage()));
                    sink.tryEmitComplete();
                })
                .subscribe();
    }

    private String parseDirectly(String fileName, byte[] fileBytes) {
        return fileParserService.parseFileBytes(fileName, fileBytes);
    }

    private String parseByFileId(String fileId) {
        return "fileId=" + fileId + " (需要从数据库/MinIO加载)";
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

    @Override
    public Flux<String> execute(String conversationId, String question) {
        return Flux.just(createTextResponse("FileParserAgent 不支持直接 execute，请使用 parseFile 方法"));
    }
}
