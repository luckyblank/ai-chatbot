package com.chatbot.ai.domain.scenario;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioDefinition {
    private String code;
    private String name;
    private String shortName;
    private String summary;
    private String knowledgeMode;
    @Builder.Default
    private List<String> tools = new ArrayList<>();
    @Builder.Default
    private List<String> process = new ArrayList<>();
    private String guardrail;
    private int sortOrder;
    private Instant updatedAt;
}
