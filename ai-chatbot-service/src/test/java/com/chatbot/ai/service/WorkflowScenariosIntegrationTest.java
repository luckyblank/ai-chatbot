package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.vo.SaveWorkflowRequest;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "app.ai.enabled=false", "app.storage.root=target/test-workflow-scenarios",
    "spring.datasource.url=jdbc:h2:mem:workflow-scenarios;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class WorkflowScenariosIntegrationTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin-scenarios", "admin", "管理员", "ADMIN");
    @Autowired WorkflowService service;
    @Autowired WorkflowRepository repository;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"commerce-support", "saas-success", "it-service", "merchant-ops"})
    void eachBusinessScenarioExecutesBothBranchesAndDurableApprovalDecisions(String scenario) {
        var template = template(scenario);
        var workflow = create(template);
        int ticketsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ai_service_ticket", Integer.class);
        for (var sample : template.testCases()) {
            var run = service.run(workflow.getId(), new RunWorkflowRequest(null, sample.input(), null), ADMIN);
            assertThat(run.getStatus()).as(sample.name()).isEqualTo(sample.expectedStatus());
            assertThat(run.getTraversedEdges()).anyMatch(edge -> sample.expectedBranch().equals(edge.getBranch()));
            assertThat(run.getSteps()).anyMatch(step -> "tool".equals(step.getNodeType()) && "completed".equals(step.getStatus()));
            assertThat(service.getRun(workflow.getId(), run.getId(), ADMIN).getStatus()).isEqualTo(run.getStatus());
            if ("waiting".equals(run.getStatus())) {
                service.resumeRun(workflow.getId(), run.getId(), true, ADMIN).collectList().block();
                var approved = service.getRun(workflow.getId(), run.getId(), ADMIN);
                assertThat(approved.getStatus()).isEqualTo("completed");
                assertThat(approved.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start", "query", "check", "review", "done");
                var rejected = service.run(workflow.getId(), new RunWorkflowRequest(null, sample.input(), null), ADMIN);
                service.resumeRun(workflow.getId(), rejected.getId(), false, ADMIN).collectList().block();
                var persisted = service.getRun(workflow.getId(), rejected.getId(), ADMIN);
                assertThat(persisted.getStatus()).isEqualTo("failed");
                assertThat(persisted.getSteps()).noneMatch(step -> "done".equals(step.getNodeId()));
                assertThat(persisted.getSteps().get(persisted.getSteps().size() - 1).getError()).contains("人工拒绝");
            } else {
                assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start", "query", "check", "alternate");
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_service_ticket", Integer.class)).isEqualTo(ticketsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"commerce-support", "saas-success", "it-service", "merchant-ops"})
    void missingBusinessIdentifierFailsAtToolAndStopsDownstream(String scenario) {
        var workflow = create(template(scenario));
        var run = service.run(workflow.getId(), new RunWorkflowRequest(null, Map.of("question", "缺少编号"), null), ADMIN);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start", "query");
        assertThat(run.getSteps().get(1).getError()).contains("缺少参数");
    }

    @Test
    void knowledgeScenarioWithoutBindingFailsClearlyInsteadOfPretendingToSucceed() {
        var workflow = create(template("knowledge-research"));
        var run = service.run(workflow.getId(), new RunWorkflowRequest(null, Map.of("question", "退款条件"), null), ADMIN);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps().get(1).getError()).contains("未选择知识库");
        assertThat(run.getSteps()).noneMatch(step -> "done".equals(step.getNodeId()));
    }

    @Test
    void defaultsHaveExecutableBranchesAndRestartPreservesUserChanges() {
        for (var template : WorkflowTemplateCatalog.templates()) {
            var seed = service.list().stream().filter(item -> template.code().equals(item.getCode())).findFirst().orElseThrow();
            assertThat(seed.getEdges()).isNotEmpty();
        }
        var seed = service.list().stream().filter(item -> "it-service-request".equals(item.getCode())).findFirst().orElseThrow();
        seed.getNodes().get(0).setName("用户自定义开始");
        repository.save(seed);
        repository.initialize();
        assertThat(service.get(seed.getId()).getNodes().get(0).getName()).isEqualTo("用户自定义开始");
    }

    private WorkflowTemplateCatalog.Template template(String scenario) {
        return WorkflowTemplateCatalog.templates().stream().filter(item -> scenario.equals(item.scenarioCode())).findFirst().orElseThrow();
    }
    private WorkflowDefinition create(WorkflowTemplateCatalog.Template template) {
        return service.create(new SaveWorkflowRequest(template.name(), template.scenarioCode(), template.description(), true, template.nodes(), template.edges()));
    }
}
