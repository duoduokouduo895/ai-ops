package com.dduo.aiops.advisory;

/** Boundary around the model vendor. Stage 4 business code does not depend on a vendor SDK. */
public interface AdvisoryModelClient {

    AiDiagnosisAdvice generate(AiDiagnosisPrompt prompt);

    String provider();

    String modelName();
}
