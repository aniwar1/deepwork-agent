package cn.hollis.llm.mentor.agent.agent.a2a.model;

/**
 * A2A 消息结构 - 遵循 A2A Protocol v1.0.0 JSON-RPC 2.0 规范
 */
public class A2AMessage {

    private String role;
    private java.util.List<MessagePart> parts;
    private String taskId;
    private String contextId;

    public A2AMessage() {
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public java.util.List<MessagePart> getParts() {
        return parts;
    }

    public void setParts(java.util.List<MessagePart> parts) {
        this.parts = parts;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    /**
     * 消息片段
     */
    public static class MessagePart {
        private String type;
        private String text;
        private String mimeType;
        private byte[] data;

        public MessagePart() {
        }

        public static MessagePart text(String text) {
            MessagePart part = new MessagePart();
            part.type = "text";
            part.text = text;
            return part;
        }

        public static MessagePart file(String mimeType, byte[] data) {
            MessagePart part = new MessagePart();
            part.type = "file";
            part.mimeType = mimeType;
            part.data = data;
            return part;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public byte[] getData() {
            return data;
        }

        public void setData(byte[] data) {
            this.data = data;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String role;
        private java.util.List<MessagePart> parts = new java.util.ArrayList<>();
        private String taskId;
        private String contextId;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder addTextPart(String text) {
            this.parts.add(MessagePart.text(text));
            return this;
        }

        public Builder addFilePart(String mimeType, byte[] data) {
            this.parts.add(MessagePart.file(mimeType, data));
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder contextId(String contextId) {
            this.contextId = contextId;
            return this;
        }

        public A2AMessage build() {
            A2AMessage msg = new A2AMessage();
            msg.role = this.role;
            msg.parts = this.parts;
            msg.taskId = this.taskId;
            msg.contextId = this.contextId;
            return msg;
        }
    }
}
