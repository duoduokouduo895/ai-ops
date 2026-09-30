package com.dduo.aiops.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class SpringAiAdvisoryModelClientTests {

    @Test
    void appliesDeepSeekCostAndCompatibilityLimitsToEveryRequest() {
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<ChatModel>) mock(ObjectProvider.class);
        var environment = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.model", "deepseek-v4-flash")
                .withProperty("aidiag.stage4.max-output-tokens", "1200")
                .withProperty("aidiag.stage4.timeout-seconds", "12");
        var client = new SpringAiAdvisoryModelClient(provider, new ObjectMapper(), new AiAdvisorySettings(environment), environment);

        var options = client.requestOptions().build();

        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(options.getMaxTokens()).isEqualTo(1200);
        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getExtraBody()).isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
    }
}
