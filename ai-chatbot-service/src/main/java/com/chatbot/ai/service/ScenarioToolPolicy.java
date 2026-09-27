package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Enforces the server-side tool allowlist for every supported scenario.
 * Front-end labels and prompt instructions improve usability, but they are not
 * security boundaries; only callbacks returned here are exposed to the model.
 */
@Component
public class ScenarioToolPolicy {
    private final ObjectProvider<ToolCallbackProvider> callbackProvider;

    public ScenarioToolPolicy(ObjectProvider<ToolCallbackProvider> callbackProvider) {
        this.callbackProvider = callbackProvider;
    }

    public List<FunctionCallback> allowedCallbacks(ScenarioDefinition scenario) {
        Set<String> allowedNames = allowedToolNames(scenario);
        if (allowedNames.isEmpty()) {
            return List.of();
        }
        ToolCallbackProvider provider = callbackProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        return Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> allowedNames.contains(callback.getName()))
                .map(callback -> (FunctionCallback) callback)
                .toList();
    }

    public boolean isAllowed(ScenarioDefinition scenario, String callbackName) {
        return callbackName != null && allowedToolNames(scenario).contains(callbackName);
    }

    public Set<String> allowedToolNames(ScenarioDefinition scenario) {
        if (scenario == null) return Set.of();
        return ScenarioToolCatalog.callbackNamesFor(scenario.getTools());
    }
}
