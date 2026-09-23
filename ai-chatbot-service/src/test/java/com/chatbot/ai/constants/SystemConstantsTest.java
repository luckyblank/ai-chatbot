package com.chatbot.ai.constants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemConstantsTest {

    @Test
    void generalAssistantExplicitlyAllowsProgrammingWithoutBusinessContext() {
        assertThat(SystemConstants.GENERAL_ASSISTANT_SYSTEM)
                .contains("编程")
                .contains("普通问题可以直接回答")
                .contains("不要求用户补充企业业务背景")
                .contains("通用场景不调用客户、订单、工单等业务工具");
    }
}
