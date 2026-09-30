package com.dduo.aiops.advisory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiAdvisorySettingsTests {

    @Test
    void usesConservativeDefaultBudgets() {
        var settings = new AiAdvisorySettings(new MockEnvironment());

        assertThat(settings.dailyRequestLimit()).isEqualTo(20);
        assertThat(settings.maxPromptChars()).isEqualTo(16_000);
        assertThat(settings.maxOutputTokens()).isEqualTo(1_200);
        assertThat(settings.retentionDays()).isEqualTo(30);
    }

    @Test
    void rejectsAnUnboundedDailyLimit() {
        var environment = new MockEnvironment().withProperty("aidiag.stage4.daily-request-limit", "101");

        assertThatThrownBy(() -> new AiAdvisorySettings(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-request-limit");
    }
}
