package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.a2a.model.A2AMessage;
import cn.hollis.llm.mentor.agent.agent.a2a.model.TaskStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A2A 客户端 - 用于调用其他 Agent
 * 封装 A2A SendMessage 和 SendStreamingMessage 操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class A2aAgentClient {

    private final A2aConfig a2aConfig;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_RETRIES = 2;

    /**
     * 调用远程 Agent（同步）
     */
    public String invokeAgent(String agentUrl, String task, String skillId) {
        String taskId = UUID.randomUUID().toString();

        A2AMessage message = A2AMessage.builder()
                .role("user")
                .taskId(taskId)
                .contextId(taskId)
                .addTextPart(task)
                .build();

        String requestBody = buildJsonRpcRequest("message/send", message);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                WebClient webClient = buildWebClient(agentUrl);
                String response = webClient.post()
                        .uri("/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(DEFAULT_TIMEOUT)
                        .block();

                return parseResponse(response);
            } catch (Exception e) {
                log.warn("A2A 调用 Agent 失败 (attempt {}): agent={}, skill={}, error={}",
                        attempt + 1, agentUrl, skillId, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("A2A 调用失败: " + e.getMessage(), e);
                }
            }
        }

        return null;
    }

    /**
     * 调用远程 Agent（流式）
     */
    public Flux<String> invokeAgentStream(String agentUrl, String task, String skillId) {
        String taskId = UUID.randomUUID().toString();

        A2AMessage message = A2AMessage.builder()
                .role("user")
                .taskId(taskId)
                .contextId(taskId)
                .addTextPart(task)
                .build();

        String requestBody = buildJsonRpcRequest("message/sendStreaming", message);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean completed = new AtomicBoolean(false);

        buildWebClient(agentUrl).post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(DEFAULT_TIMEOUT)
                .subscribe(
                        response -> {
                            sink.tryEmitNext(response);
                            sink.tryEmitComplete();
                            completed.set(true);
                        },
                        error -> {
                            if (!completed.get()) {
                                log.warn("A2A 流式调用失败: agent={}, skill={}, error={}",
                                        agentUrl, skillId, error.getMessage());
                                sink.tryEmitError(error);
                            }
                        }
                );

        return sink.asFlux();
    }

    /**
     * 构建 JSON-RPC 请求
     */
    private String buildJsonRpcRequest(String method, A2AMessage params) {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        request.put("params", params);
        return request.toJSONString();
    }

    /**
     * 解析 A2A 响应
     */
    private String parseResponse(String response) {
        if (response == null) {
            return null;
        }

        try {
            JSONObject json = JSON.parseObject(response);

            if ("2.0".equals(json.getString("jsonrpc"))) {
                Object result = json.get("result");
                if (result instanceof JSONObject) {
                    return ((JSONObject) result).getString("content");
                }
                return result != null ? result.toString() : null;
            }

            if (json.containsKey("error")) {
                throw new RuntimeException("A2A 响应错误: " + json.getString("error"));
            }
        } catch (Exception e) {
            log.debug("解析 A2A 响应失败，尝试直接返回: {}", response);
        }

        return response;
    }

    private WebClient buildWebClient(String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 创建 A2A Task
     */
    public A2aTask createTask(String taskId, String contextId) {
        return new A2aTask(taskId, contextId, TaskStatus.SUBMITTED);
    }

    /**
     * A2A Task 模型
     */
    public static class A2aTask {
        private final String taskId;
        private final String contextId;
        private TaskStatus status;
        private String result;

        public A2aTask(String taskId, String contextId, TaskStatus status) {
            this.taskId = taskId;
            this.contextId = contextId;
            this.status = status;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getContextId() {
            return contextId;
        }

        public TaskStatus getStatus() {
            return status;
        }

        public void setStatus(TaskStatus status) {
            this.status = status;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }
    }
}
