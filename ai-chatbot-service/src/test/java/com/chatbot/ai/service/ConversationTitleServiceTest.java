package com.chatbot.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationTitleServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void usesCleanSafeFallbackWhenTitleModelIsUnavailable() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConversationTitleService service = new ConversationTitleService(provider);

        String title = service.generate("  我的订单已经超过预计送达时间，请帮我查询物流进度！！！  ");

        assertThat(title).isEqualTo("我的订单已经超过预计送达时间，请帮我查询");
        assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(20);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stripsModelStylePrefixesQuotesAndTrailingPunctuation() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        ConversationTitleService service = new ConversationTitleService(provider);

        assertThat(service.normalizeTitle("标题：\"企业账号 MFA 解绑申请。\""))
                .isEqualTo("企业账号 MFA 解绑申请");
    }
}
