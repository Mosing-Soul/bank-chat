package org.gundy.chat.service;

import org.gundy.chat.entity.intent.ExtractedEntities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityExtractorServiceTest {
    private final EntityExtractorService extractor = new EntityExtractorService();

    @Test
    void doesNotTreatBusinessTermsAsCustomerNames() {
        ExtractedEntities result = extractor.extract("帮我查一下客户等级");

        assertThat(result.getCustomerNames()).isEmpty();
        assertThat(result.getBusinessTerms()).contains("客户等级");
    }

    @Test
    void extractsOnlyStrongFormatCustomerId() {
        ExtractedEntities result = extractor.extract("查询客户号 CUST001 的资产");

        assertThat(result.getCustomerIds()).containsExactly("CUST001");
        assertThat(result.getCustomerNames()).isEmpty();
    }

    @Test
    void doesNotGuessNaturalLanguageCustomerNameInJava() {
        ExtractedEntities result = extractor.extract("查询客户张伟当前AUM");

        assertThat(result.getCustomerNames()).isEmpty();
    }
}
