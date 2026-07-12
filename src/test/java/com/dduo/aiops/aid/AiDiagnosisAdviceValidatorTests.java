package com.dduo.aiops.aid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AiDiagnosisAdviceValidatorTests {

    private final AiDiagnosisAdviceValidator validator = new AiDiagnosisAdviceValidator();

    @Test
    void acceptsOnlyReferencedEvidenceAndControlledRunbookActions() {
        var advice = new AiDiagnosisAdvice("Payment pool is likely saturated.", List.of("Pool saturation"),
                List.of("evidence-1"), List.of("Disable the controlled fault"), List.of("Only a preview"));

        var result = validator.validate(advice, Set.of("evidence-1"), Set.of("Disable the controlled fault"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.advice().recommendedRunbookActions()).containsExactly("Disable the controlled fault");
    }

    @Test
    void rejectsFabricatedEvidenceAndUncontrolledActions() {
        var fabricatedEvidence = new AiDiagnosisAdvice("Summary", List.of("A hypothesis"), List.of("invented-evidence"),
                List.of(), List.of());
        var uncontrolledAction = new AiDiagnosisAdvice("Summary", List.of("A hypothesis"), List.of(),
                List.of("Run arbitrary command"), List.of());

        assertThat(validator.validate(fabricatedEvidence, Set.of("evidence-1"), Set.of()).accepted()).isFalse();
        assertThat(validator.validate(uncontrolledAction, Set.of(), Set.of("Disable the controlled fault")).accepted()).isFalse();
    }
}
