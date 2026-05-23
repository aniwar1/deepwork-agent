package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.a2a.model.A2AMessage;
import cn.hollis.llm.mentor.agent.agent.a2a.model.TaskStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A JSON-RPC 端点
 * 遵循 A2A Protocol v1.0.0 JSON-RPC 2.0 规范
 */
@Slf4j
@RestController
@RequestMapping("/a2a")
@RequiredArgsConstructor
public class A2aController {

    private final OrchestratorAgent orchestratorAgent;
    private final FileParserAgent fileParserAgent;

    private final Map<String, TaskState> taskStore = new ConcurrentHashMap<>();

    /**
     * POST /a2a - JSON-RPC 消息端点
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Object handleMessage(@RequestBody String requestBody) {
        log.debug("收到 A2A 消息: {}", requestBody);

        try {
            JSONObject request = JSON.parseObject(requestBody);
            String method = request.getString("method");
            String id = request.getString("id");
            JSONObject params = request.getJSONObject("params");

            Object result = dispatch(method, params);

            JSONObject response = new JSONObject();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (Exception e) {
            log.error("A2A 消息处理失败: {}", e.getMessage(), e);
            JSONObject error = new JSONObject();
            error.put("jsonrpc", "2.0");
            error.put("error", Map.of(
                    "code", -32603,
                    "message", "Internal error: " + e.getMessage()
            ));
            return error;
        }
    }

    /**
     * POST /a2a/stream - SSE 流式消息端点
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleStreamingMessage(@RequestBody String requestBody) {
        log.debug("收到 A2A 流式消息");

        try {
            JSONObject request = JSON.parseObject(requestBody);
            String method = request.getString("method");
            JSONObject params = request.getJSONObject("params");
            return dispatchStream(method, params);
        } catch (Exception e) {
            log.error("A2A 流式消息处理失败: {}", e.getMessage(), e);
            return Flux.just("data: {\"error\": \"" + e.getMessage() + "\"}\n\n");
        }
    }

    private Object dispatch(String method, JSONObject params) {
        return switch (method) {
            case "message/send" -> handleSendMessage(params);
            case "tasks/get" -> handleGetTask(params);
            case "tasks/cancel" -> handleCancelTask(params);
            default -> throw new IllegalArgumentException("不支持的方法: " + method);
        };
    }

    private Flux<String> dispatchStream(String method, JSONObject params) {
        if ("message/sendStreaming".equals(method)) {
            return handleSendStreamingMessage(params);
        }
        return Flux.just("data: {\"error\": \"Unsupported method\"}\n\n");
    }

    private Object handleSendMessage(JSONObject params) {
        A2AMessage message = JSON.parseObject(params.toJSONString(), A2AMessage.class);

        String taskId = message.getTaskId();
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
        }
        String contextId = message.getContextId() != null ? message.getContextId() : taskId;

        TaskState state = new TaskState(taskId, contextId);
        taskStore.put(taskId, state);

        String text = extractText(message);
        String fileName = extractFileName(message);

        String result = orchestratorAgent.executeWithFile(null, text, null, fileName, null)
                .collectList()
                .map(list -> String.join("", list))
                .block();

        state.setStatus(TaskStatus.COMPLETED);
        state.setResult(result);

        JSONObject resultObj = new JSONObject();
        resultObj.put("taskId", taskId);
        resultObj.put("status", TaskStatus.COMPLETED.name());
        resultObj.put("content", result);
        return resultObj;
    }

    private Flux<String> handleSendStreamingMessage(JSONObject params) {
        A2AMessage message = JSON.parseObject(params.toJSONString(), A2AMessage.class);

        String taskId = message.getTaskId() != null ? message.getTaskId() : UUID.randomUUID().toString();
        String text = extractText(message);
        String fileName = extractFileName(message);

        TaskState state = new TaskState(taskId, message.getContextId());
        taskStore.put(taskId, state);

        return orchestratorAgent.executeWithFile(null, text, null, fileName, null)
                .map(chunk -> "data: " + chunk + "\n\n")
                .doOnComplete(() -> state.setStatus(TaskStatus.COMPLETED))
                .doOnError(e -> state.setStatus(TaskStatus.FAILED));
    }

    private Object handleGetTask(JSONObject params) {
        String taskId = params.getString("taskId");
        TaskState state = taskStore.get(taskId);

        if (state == null) {
            return Map.of("error", "Task not found: " + taskId);
        }
        return Map.of(
                "taskId", state.taskId,
                "status", state.status.name(),
                "result", state.result != null ? state.result : ""
        );
    }

    private Object handleCancelTask(JSONObject params) {
        String taskId = params.getString("taskId");
        TaskState state = taskStore.get(taskId);

        if (state == null) {
            return Map.of("error", "Task not found: " + taskId);
        }
        state.setStatus(TaskStatus.CANCELED);
        return Map.of("taskId", taskId, "status", "CANCELED");
    }

    private String extractText(A2AMessage message) {
        if (message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(A2AMessage.MessagePart::getText)
                .reduce("", (a, b) -> a + b);
    }

    private String extractFileName(A2AMessage message) {
        return null;
    }

    private static class TaskState {
        final String taskId;
        final String contextId;
        TaskStatus status;
        String result;

        TaskState(String taskId, String contextId) {
            this.taskId = taskId;
            this.contextId = contextId;
            this.status = TaskStatus.WORKING;
        }

        void setStatus(TaskStatus status) {
            this.status = status;
        }

        void setResult(String result) {
            this.result = result;
        }
    }
}
