package com.chatbot.ai.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateScenarioRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 40) String shortName,
        @NotBlank @Size(max = 500) String summary,
        @NotBlank @Pattern(regexp = "可选|推荐|必选") String knowledgeMode,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> tools,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 120) String> process,
        @NotBlank @Size(max = 1000) String guardrail
) {
}
