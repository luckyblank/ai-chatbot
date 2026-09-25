package com.chatbot.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationEvidenceSelectorTest {
    @Test
    void selectsARelevantSentenceAndItsMarkdownSection() {
        String chunk = """
                # 一、服务范围
                客服需要确认用户问题并记录订单信息。

                # 三、七日无理由退货判断
                网络销售通常应先判断消费者是否处于签收后的七日窗口，再判断商品是否属于法定例外。
                若商品存在质量问题，应依据质量保障规则另行处理。
                """;

        CitationEvidenceSelector.Evidence evidence = CitationEvidenceSelector.select(chunk,
                "七日无理由退货怎么判断？", "先核对签收后的七日窗口和法定例外。[资料 1]", 1);

        assertThat(evidence.sectionTitle()).isEqualTo("三、七日无理由退货判断");
        assertThat(evidence.excerpt()).contains("签收后的七日窗口").doesNotContain("客服需要确认");
        assertThat(chunk).contains(evidence.excerpt());
    }

    @Test
    void capsAHitInsideALongSentenceWithoutReturningTheWholeChunk() {
        String chunk = "背景说明".repeat(90) + "签收后七日内可申请无理由退货" + "其他说明".repeat(90);

        CitationEvidenceSelector.Evidence evidence = CitationEvidenceSelector.select(chunk,
                "签收后七日内如何退货", "签收后七日内可申请无理由退货。[资料 1]", 1);

        assertThat(evidence.excerpt()).contains("签收后七日内可申请无理由退货");
        assertThat(evidence.excerpt().length()).isLessThanOrEqualTo(220);
        assertThat(chunk).contains(evidence.excerpt().replace("…", ""));
    }

    @Test
    void countsOnlyValidSourcesCitedInTheAnswer() {
        assertThat(CitationEvidenceSelector.citedSourceNumbers(
                "按政策处理。[资料 2] 还可参考 [资料 2] 和 [资料 9]。", 3))
                .containsExactly(2);
    }

    @Test
    void doesNotPretendToLocateEvidenceWhenNothingMatches() {
        CitationEvidenceSelector.Evidence evidence = CitationEvidenceSelector.select(
                "# 安全说明\n请定期更新账户密码。", "七日无理由退货期限", "", 1);
        assertThat(evidence.excerpt()).isEmpty();
    }
}
