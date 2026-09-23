package com.chatbot.ai.domain.workflow;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNode {
    @NotBlank
    @Size(max = 100)
    private String id;

    @NotBlank
    @Pattern(regexp = "input|condition|knowledge|tool|model|approval|output")
    private String type;

    @NotBlank
    @Size(max = 60)
    private String name;

    @Size(max = 160)
    private String description;
    @Size(max = 8000)
    private String config;

    @Min(0)
    @Max(10000)
    private Integer positionX;

    @Min(0)
    @Max(10000)
    private Integer positionY;
}
