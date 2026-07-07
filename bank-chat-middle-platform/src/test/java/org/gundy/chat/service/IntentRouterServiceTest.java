package org.gundy.chat.service;

import org.gundy.chat.entity.intent.IntentRouteResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterServiceTest {
    private final IntentRouterService router = new IntentRouterService(new EntityExtractorService());

    @Test
    void routesBankCustomerLevelQuestionToRag() {
        IntentRouteResult result = router.route(null, "招行的客户等级是怎么样的", null, false);

        assertThat(result.getRequestedSkill()).isEqualTo("RAG_QUERY");
        assertThat(result.isForceSkill()).isTrue();
        assertThat(result.isClearHistory()).isTrue();
        assertThat(result.getEntities().getBankNames()).contains("招行");
        assertThat(result.getEntities().getBusinessTerms()).contains("客户等级");
    }

    @Test
    void routesSpecificCustomerAumQuestionToCustomerAum() {
        IntentRouteResult result = router.route(null, "查询客户张伟AUM", null, false);

        assertThat(result.getRequestedSkill()).isEqualTo("CUSTOMER_AUM");
        assertThat(result.getEntities().getCustomerNames()).contains("张伟");
    }

    @Test
    void routesGoldPriceQuestionToGoldPrice() {
        IntentRouteResult result = router.route(null, "黄金价格是多少", null, false);

        assertThat(result.getRequestedSkill()).isEqualTo("GOLD_PRICE");
        assertThat(result.isForceSkill()).isTrue();
    }
}
