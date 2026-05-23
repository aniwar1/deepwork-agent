package cn.hollis.llm.mentor.agent.agent.a2a;

import cn.hollis.llm.mentor.agent.agent.a2a.model.AgentCard;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Agent Card 端点
 * 遵循 A2A Protocol 规范，Agent Card 位于 /.well-known/agent.json
 */
@RestController
@RequiredArgsConstructor
public class A2aAgentCardEndpoint {

    private final A2aConfig a2aConfig;

    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getAgentCard() {
        return JSON.toJSONString(a2aConfig.getAgentCard());
    }

    /**
     * 根据 skill id 查找对应的 Agent URL
     */
    public String findAgentUrlBySkill(String skillId) {
        AgentCard card = a2aConfig.getAgentCard();
        if (card.getSkills() == null) {
            return null;
        }
        return card.getSkills().stream()
                .filter(s -> skillId.equals(s.getId()))
                .findFirst()
                .map(s -> card.getUrl())
                .orElse(null);
    }
}
