package cn.hollis.llm.mentor.agent.agent.a2a.model;

/**
 * A2A Agent Card - 代理能力描述文件
 * 遵循 A2A Protocol v1.0.0 规范
 */
public class AgentCard {

    private String name;
    private String url;
    private String version = "1.0.0";
    private AgentCapabilities capabilities;
    private java.util.List<AgentSkill> skills;
    private java.util.List<String> defaultInputModes;
    private java.util.List<String> defaultOutputModes;
    private SecuritySchemes securitySchemes;

    public AgentCard() {
    }

    private AgentCard(Builder builder) {
        this.name = builder.name;
        this.url = builder.url;
        this.version = builder.version;
        this.capabilities = builder.capabilities;
        this.skills = builder.skills;
        this.defaultInputModes = builder.defaultInputModes;
        this.defaultOutputModes = builder.defaultOutputModes;
        this.securitySchemes = builder.securitySchemes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public AgentCapabilities getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(AgentCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public java.util.List<AgentSkill> getSkills() {
        return skills;
    }

    public void setSkills(java.util.List<AgentSkill> skills) {
        this.skills = skills;
    }

    public java.util.List<String> getDefaultInputModes() {
        return defaultInputModes;
    }

    public void setDefaultInputModes(java.util.List<String> defaultInputModes) {
        this.defaultInputModes = defaultInputModes;
    }

    public java.util.List<String> getDefaultOutputModes() {
        return defaultOutputModes;
    }

    public void setDefaultOutputModes(java.util.List<String> defaultOutputModes) {
        this.defaultOutputModes = defaultOutputModes;
    }

    public SecuritySchemes getSecuritySchemes() {
        return securitySchemes;
    }

    public void setSecuritySchemes(SecuritySchemes securitySchemes) {
        this.securitySchemes = securitySchemes;
    }

    /**
     * Agent 能力描述
     */
    public static class AgentCapabilities {
        private boolean streaming;
        private boolean pushNotifications;

        public AgentCapabilities() {
        }

        public AgentCapabilities(boolean streaming, boolean pushNotifications) {
            this.streaming = streaming;
            this.pushNotifications = pushNotifications;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public boolean isPushNotifications() {
            return pushNotifications;
        }

        public void setPushNotifications(boolean pushNotifications) {
            this.pushNotifications = pushNotifications;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean streaming = false;
            private boolean pushNotifications = false;

            public Builder streaming(boolean streaming) {
                this.streaming = streaming;
                return this;
            }

            public Builder pushNotifications(boolean pushNotifications) {
                this.pushNotifications = pushNotifications;
                return this;
            }

            public AgentCapabilities build() {
                return new AgentCapabilities(streaming, pushNotifications);
            }
        }
    }

    /**
     * Agent Skill 描述
     */
    public static class AgentSkill {
        private String id;
        private String name;
        private String description;

        public AgentSkill() {
        }

        public AgentSkill(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public static class Builder {
            private String id;
            private String name;
            private String description;

            public Builder id(String id) {
                this.id = id;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public AgentSkill build() {
                return new AgentSkill(id, name, description);
            }
        }
    }

    /**
     * 安全认证配置
     */
    public static class SecuritySchemes {
    }

    public static class Builder {
        private String name;
        private String url;
        private String version = "1.0.0";
        private AgentCapabilities capabilities;
        private java.util.List<AgentSkill> skills;
        private java.util.List<String> defaultInputModes;
        private java.util.List<String> defaultOutputModes;
        private SecuritySchemes securitySchemes;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder capabilities(AgentCapabilities capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder skills(java.util.List<AgentSkill> skills) {
            this.skills = skills;
            return this;
        }

        public Builder defaultInputModes(java.util.List<String> defaultInputModes) {
            this.defaultInputModes = defaultInputModes;
            return this;
        }

        public Builder defaultOutputModes(java.util.List<String> defaultOutputModes) {
            this.defaultOutputModes = defaultOutputModes;
            return this;
        }

        public Builder securitySchemes(SecuritySchemes securitySchemes) {
            this.securitySchemes = securitySchemes;
            return this;
        }

        public AgentCard build() {
            return new AgentCard(this);
        }
    }
}
