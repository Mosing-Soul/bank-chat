package org.gundy.chat.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.skill.enums.OperationStatus;
import org.gundy.chat.skill.model.PendingMessageOperation;
import org.gundy.chat.skill.repository.MessageOperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bank.skills.customer.core-simulator.latency-ms=0"
})
@AutoConfigureMockMvc
class SkillControllerTest {
    private static final String API_KEY = "local-dev-internal-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageOperationRepository operationRepository;

    @Test
    void searchCustomersSuccess() throws Exception {
        mockMvc.perform(get("/internal/skills/customers/search")
                        .header("X-Internal-Api-Key", API_KEY)
                        .param("name", "李娜"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].customerId").value("CUST003"))
                .andExpect(jsonPath("$.data[0].mock").value(true));
    }

    @Test
    void fuzzySearchCanReturnMultipleCustomers() throws Exception {
        mockMvc.perform(get("/internal/skills/customers/search")
                        .header("X-Internal-Api-Key", API_KEY)
                        .param("name", "张伟"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    void customerNotFoundWhenQueryAum() throws Exception {
        mockMvc.perform(get("/internal/skills/customers/NO_SUCH/aum")
                        .header("X-Internal-Api-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void queryAumSuccess() throws Exception {
        mockMvc.perform(get("/internal/skills/customers/CUST001/aum")
                        .header("X-Internal-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("张伟"))
                .andExpect(jsonPath("$.data.totalAum").value(8260000.00))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.dataSource").value("CORE_BANK_AUM_SIMULATOR"))
                .andExpect(jsonPath("$.data.mock").value(true));
    }

    @Test
    void previewMessageSuccessGeneratesOperationId() throws Exception {
        mockMvc.perform(post("/internal/skills/messages/preview")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"traceId\":\"trace-001\",\"customerId\":\"CUST001\",\"templateCode\":\"PRODUCT_MATURITY_REMINDER\",\"variables\":{\"productName\":\"稳健精选理财\",\"maturityDate\":\"2026-07-15\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-001"))
                .andExpect(jsonPath("$.data.operationId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.sensitiveWords", hasSize(0)));
    }

    @Test
    void previewFailsWhenTemplateVariableMissing() throws Exception {
        mockMvc.perform(post("/internal/skills/messages/preview")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"CUST001\",\"templateCode\":\"PRODUCT_MATURITY_REMINDER\",\"variables\":{\"productName\":\"稳健精选理财\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_TEMPLATE_VARIABLE"));
    }

    @Test
    void previewDetectsSensitiveWords() throws Exception {
        mockMvc.perform(post("/internal/skills/messages/preview")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"CUST001\",\"templateCode\":\"CUSTOM_CONTENT\",\"variables\":{\"content\":\"该产品保本高收益\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.sensitiveWords[0]").value("保本高收益"));
    }

    @Test
    void sendRejectedWhenNotConfirmed() throws Exception {
        String operationId = createPreviewOperation();
        mockMvc.perform(post("/internal/skills/messages/send")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + operationId + "\",\"confirmed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONFIRMATION_REQUIRED"));
    }

    @Test
    void sendRejectedWhenOperationDoesNotExist() throws Exception {
        mockMvc.perform(post("/internal/skills/messages/send")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"missing-operation\",\"confirmed\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OPERATION_NOT_FOUND"));
    }

    @Test
    void sendRejectedWhenOperationExpired() throws Exception {
        operationRepository.save(new PendingMessageOperation("expired-op", "CUST001", "张伟",
                "expired mock content", Collections.<String>emptyList(),
                OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1),
                OperationStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/internal/skills/messages/send")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"expired-op\",\"confirmed\":true}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("OPERATION_EXPIRED"));
    }

    @Test
    void duplicateSendRejected() throws Exception {
        String operationId = createPreviewOperation();
        String body = "{\"operationId\":\"" + operationId + "\",\"confirmed\":true}";
        mockMvc.perform(post("/internal/skills/messages/send")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));

        mockMvc.perform(post("/internal/skills/messages/send")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_SEND"));
    }

    @Test
    void wrongInternalApiKeyRejected() throws Exception {
        mockMvc.perform(get("/internal/skills/customers/search")
                        .header("X-Internal-Api-Key", "bad-key")
                        .param("name", "张伟"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_API_UNAUTHORIZED"));
    }

    private String createPreviewOperation() throws Exception {
        String response = mockMvc.perform(post("/internal/skills/messages/preview")
                        .header("X-Internal-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"CUST001\",\"templateCode\":\"PRODUCT_MATURITY_REMINDER\",\"variables\":{\"productName\":\"稳健精选理财\",\"maturityDate\":\"2026-07-15\"}}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        return root.path("data").path("operationId").asText();
    }
}
