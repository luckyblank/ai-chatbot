package com.chatbot.ai.domain.workflow;

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
public class WorkflowDefinition {
    private String id;
    private String code;
    private String name;
    private String scenarioCode;
    private String description;
    private boolean enabled;
    @Builder.Default
    private List<WorkflowNode> nodes = new ArrayList<>();
    /** null means legacy implicit sequential edges; an explicit empty list means no edges. */
    private List<WorkflowEdge> edges;
    private Instant createdAt;
    private Instant updatedAt;
}
