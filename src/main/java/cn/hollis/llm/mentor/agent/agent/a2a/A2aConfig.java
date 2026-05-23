package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.a2a.model.AgentCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * A2A 配置类
 * 配置 AgentCard 并暴露 /.well-known/agent.json 端点
 */
@Slf4j
@Component
public class A2aConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${spring.ai.alibaba.base-url:}")
    private String aiBaseUrl;

    private final ChatModel chatModel;

    public A2aConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    private AgentCard agentCard;

    @PostConstruct
    public void init() {
        String baseUrl = determineBaseUrl();
        this.agentCard = AgentCard.builder()
                .name("deepwork-orchestrator")
                .url(baseUrl + "/a2a/")
                .version("1.0.0")
                .capabilities(AgentCard.AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .skills(List.of(
                        AgentCard.AgentSkill.builder()
                                .id("deep_think")
                                .name("Deep Thinking")
                                .description("Complex reasoning, research and analysis with plan-execute pattern")
                                .build(),
                        AgentCard.AgentSkill.builder()
                                .id("web_search")
                                .name("Web Search")
                                .description("Internet search for real-time information")
                                .build(),
                        AgentCard.AgentSkill.builder()
                                .id("file_process")
                                .name("File Processing")
                                .description("Route file handling to specialized agents for PDF/DOCX/TXT parsing")
                                .build()
                ))
                .defaultInputModes(List.of("text", "application/json"))
                .defaultOutputModes(List.of("text/event-stream", "application/json"))
                .build();

        log.info("A2A AgentCard 初始化完成: name={}, url={}", agentCard.getName(), agentCard.getUrl());
    }

    private String determineBaseUrl() {
        if (aiBaseUrl != null && !aiBaseUrl.isEmpty()) {
            return aiBaseUrl;
        }
        return "http://localhost:" + serverPort;
    }

    public AgentCard getAgentCard() {
        return agentCard;
    }
}
