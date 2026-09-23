package com.chatbot.ai.domain.vo;

import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SaveWorkflowRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 80) String scenarioCode,
        @Size(max = 500) String description,
        boolean enabled,
        @NotEmpty @Size(max = 30) @Valid List<WorkflowNode> nodes,
        List<WorkflowEdge> edges
) {
}
