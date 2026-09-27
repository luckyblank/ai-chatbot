package com.chatbot.ai.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRepositoryMigrationTest {
    @Test
    void upgradesOnlyUntouchedGeneralDefaultSteps() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scenario-general-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ScenarioRepository scenarios = new ScenarioRepository(new JdbcTemplate(dataSource), new ObjectMapper());
        scenarios.initialize();

        var general = scenarios.findByCode("general").orElseThrow();
        assertThat(general.getProcess()).containsExactly(
                "理解用户目标", "缺少关键信息时先澄清", "根据已知信息生成结果", "必要时说明下一步");

        general.setProcess(List.of("理解任务", "澄清约束", "生成结果", "确认下一步"));
        scenarios.save(general);
        scenarios.initialize();
        assertThat(scenarios.findByCode("general").orElseThrow().getProcess()).containsExactly(
                "理解用户目标", "缺少关键信息时先澄清", "根据已知信息生成结果", "必要时说明下一步");

        general = scenarios.findByCode("general").orElseThrow();
        general.setProcess(List.of("这是自定义步骤"));
        scenarios.save(general);
        scenarios.initialize();
        assertThat(scenarios.findByCode("general").orElseThrow().getProcess())
                .containsExactly("这是自定义步骤");
    }

    @Test
    void addsCustomerOrderLookupToExistingDefaultWithoutOverwritingCustomAllowlist() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scenario-order-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ScenarioRepository scenarios = new ScenarioRepository(new JdbcTemplate(dataSource), new ObjectMapper());
        scenarios.initialize();

        var commerce = scenarios.findByCode("commerce-support").orElseThrow();
        commerce.setTools(List.of("客户权益查询", "订单履约查询", "售后资格校验", "创建服务工单"));
        commerce.setProcess(List.of("核验订单", "判断问题类型", "匹配售后政策", "确认处理方案", "创建售后单"));
        scenarios.save(commerce);
        scenarios.initialize();
        assertThat(scenarios.findByCode("commerce-support").orElseThrow().getTools())
                .containsExactly("客户权益查询", "客户订单查询", "订单履约查询", "售后资格校验", "创建服务工单");
        assertThat(scenarios.findByCode("commerce-support").orElseThrow().getProcess())
                .contains("准备售后单并等待确认");

        commerce = scenarios.findByCode("commerce-support").orElseThrow();
        commerce.setTools(List.of("订单履约查询"));
        scenarios.save(commerce);
        scenarios.initialize();
        assertThat(scenarios.findByCode("commerce-support").orElseThrow().getTools())
                .containsExactly("订单履约查询");
    }
}
