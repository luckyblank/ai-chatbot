package com.chatbot.ai.domain.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRun {
    private String id;
    private String workflowId;
    private String workflowName;
    private String conversationId;
    /** Authenticated operator that created the run; never populated from workflow input. */
    private String ownerId;
    @Builder.Default
    private String mode = "execution";
    private String status;
    /** Immutable definition version captured when this run started. */
    private String definitionVersion;
    /** Node that would execute next from the last durable checkpoint. */
    private String nextNodeId;
    /** Human-readable durable checkpoint name, exposed for recovery diagnostics. */
    private String checkpoint;
    /** Run-level explanation for waiting, failure, or interruption states. */
    private String statusMessage;
    private Map<String, Object> input;
    private Object output;
    @Builder.Default
    private List<WorkflowEdge> traversedEdges = new ArrayList<>();
    private String waitingNodeId;
    private Instant startedAt;
    private Instant completedAt;
    @Builder.Default
    private List<RunStep> steps = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunStep {
        private String nodeId;
        private String nodeName;
        private String nodeType;
        private String status;
        private String detail;
        private Object input;
        private Object output;
        private String error;
        private long durationMs;
        private Instant startedAt;
        private Instant completedAt;
    }
}
