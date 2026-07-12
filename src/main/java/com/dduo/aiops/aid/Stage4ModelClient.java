package com.dduo.aiops.aid;

/** Boundary around the model vendor. Stage 4 business code does not depend on a vendor SDK. */
public interface Stage4ModelClient {

    AiDiagnosisAdvice generate(AiDiagnosisPrompt prompt);

    String provider();

    String modelName();
}
