package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.a2a.model.A2AMessage;
import cn.hollis.llm.mentor.agent.agent.a2a.model.AgentCard;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件处理决策器
 * 在深度思考过程中检测到文件时，由 LLM 驱动决定处理策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileHandoffDecider {

    private final ChatModel chatModel;
    private final A2aConfig a2aConfig;
    private final A2aAgentClient a2aAgentClient;

    private static final String DECISION_PROMPT = """
            ## 任务
            你是一个智能路由决策器。当用户在深度思考任务进行中上传了一个文件时，你需要决定如何处理这个文件。

            ## 当前场景
            用户正在执行一个深度思考/研究任务，此时突然上传了一个附件文件。

            ## 文件信息
            - 文件名: %s
            - 文件类型: %s
            - 文件大小: %d bytes

            ## 用户原始问题
            %s

            ## 决策选项
            1. CONTINUE: 文件与当前任务无关，可以忽略，继续深度思考
            2. INTERRUPT: 文件内容重要且复杂，需要中断当前思考，交给专门的文件处理 Agent 解析
            3. PARALLEL: 文件可以边处理边思考，不阻塞主流程（如：简单文件可以直接读取摘要）

            ## 决策规则
            - PDF/DOCX/Word 等复杂文档 → INTERRUPT（需要专门解析）
            - 图片文件（PNG/JPG）且问题涉及图片分析 → INTERRUPT
            - TXT/纯文本文件 → PARALLEL（可直接读取内容）
            - 文件名明显与研究主题相关 → INTERRUPT
            - 文件与当前任务完全无关 → CONTINUE

            ## 输出格式
            只输出以下 JSON，不要输出任何其他内容：
            {
              "strategy": "CONTINUE|INTERRUPT|PARALLEL",
              "targetAgent": "file_parser_agent",
              "skillId": "file_process",
              "reasoning": "决策原因（20字以内）"
            }
            """;

    /**
     * 决策文件处理策略
     */
    public FileHandoffDecision decide(
            String fileName,
            String fileType,
            long fileSize,
            String userQuestion
    ) {
        log.info("开始文件处理决策: fileName={}, fileType={}, fileSize={}",
                fileName, fileType, fileSize);

        String prompt = String.format(
                DECISION_PROMPT,
                fileName,
                fileType,
                fileSize,
                userQuestion != null ? userQuestion : "(无)"
        );

        try {
            ChatClient chatClient = ChatClient.builder(chatModel).build();

            ChatClientResponse response = chatClient.prompt()
                    .messages(List.of(
                            new SystemMessage("你是一个智能路由决策器，请严格按照JSON格式输出。"),
                            new UserMessage(prompt)
                    ))
                    .call()
                    .chatClientResponse();

            String rawResponse = response.chatResponse().getResult().getOutput().getText();
            return parseDecision(rawResponse);
        } catch (Exception e) {
            log.error("文件处理决策失败，默认使用 INTERRUPT: fileName={}", fileName, e);
            return FileHandoffDecision.interrupt(
                    "file_parser_agent",
                    "file_process",
                    "决策失败，默认中断"
            );
        }
    }

    /**
     * 解析 LLM 决策结果
     */
    private FileHandoffDecision parseDecision(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) {
            return FileHandoffDecision.continueTask("空响应");
        }

        try {
            String jsonStr = extractJson(rawResponse);
            JSONObject json = JSON.parseObject(jsonStr);

            String strategyStr = json.getString("strategy");
            FileHandoffStrategy strategy;
            try {
                strategy = FileHandoffStrategy.valueOf(strategyStr);
            } catch (Exception e) {
                strategy = FileHandoffStrategy.CONTINUE;
            }

            String targetAgent = json.getString("targetAgent");
            String skillId = json.getString("skillId");
            String reasoning = json.getString("reasoning");

            return new FileHandoffDecision(strategy, targetAgent, skillId, reasoning, 0);
        } catch (Exception e) {
            log.warn("解析决策结果失败: raw={}, error={}", rawResponse, e.getMessage());
            return FileHandoffDecision.continueTask("解析失败");
        }
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }
}
