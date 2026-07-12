package com.dduo.aiops.aid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class Stage4SettingsTests {

    @Test
    void usesConservativeDefaultBudgets() {
        var settings = new Stage4Settings(new MockEnvironment());

        assertThat(settings.dailyRequestLimit()).isEqualTo(20);
        assertThat(settings.maxPromptChars()).isEqualTo(16_000);
        assertThat(settings.maxOutputTokens()).isEqualTo(1_200);
        assertThat(settings.retentionDays()).isEqualTo(30);
    }

    @Test
    void rejectsAnUnboundedDailyLimit() {
        var environment = new MockEnvironment().withProperty("aidiag.stage4.daily-request-limit", "101");

        assertThatThrownBy(() -> new Stage4Settings(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-request-limit");
    }
}
