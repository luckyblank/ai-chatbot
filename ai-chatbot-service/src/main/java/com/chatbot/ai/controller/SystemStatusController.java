package com.chatbot.ai.controller;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    private final Environment environment;

    public SystemStatusController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/ai-status")
    public AiStatus aiStatus() {
        boolean enabled = environment.getProperty("app.ai.enabled", Boolean.class, false);
        String configuredKey = environment.getProperty("spring.ai.openai.api-key", "");
        boolean configured = enabled && hasConfiguredKey(configuredKey);
        String model = environment.getProperty("spring.ai.openai.chat.options.model", "");
        return new AiStatus(enabled, configured, "DashScope", model);
    }

    static boolean hasConfiguredKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String value = key.trim();
        return !"disabled-when-ai-is-off".equals(value) && !value.startsWith("replace-");
    }

    /** Config presence only; this endpoint does not make a provider request or expose the key. */
    public record AiStatus(boolean enabled, boolean configured, String provider, String model) { }
}
