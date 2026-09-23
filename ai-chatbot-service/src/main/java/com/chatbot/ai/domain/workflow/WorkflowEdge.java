package com.chatbot.ai.domain.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEdge {
    private String id;
    private String source;
    private String target;
    /** Conditional outcome, normally "true" or "false"; null for an unconditional edge. */
    private String branch;
}
