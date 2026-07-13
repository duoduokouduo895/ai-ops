package com.dduo.aiops.diagnosis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class DiagnosisRuleRegistryTests {

    @Test
    void rejectsDuplicateServiceAndAlertAtStartup() {
        var provider = new AiChatDiagnosisRuleProvider();
        assertThatThrownBy(() -> new DiagnosisRuleRegistry(List.of(provider, provider)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate diagnosis rule");
    }
}
