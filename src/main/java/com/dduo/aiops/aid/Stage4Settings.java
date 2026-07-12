package com.dduo.aiops.aid;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Bounded Stage 4 settings. Provider-specific settings remain with the selected Spring AI provider. */
@Component
public class Stage4Settings {

    private final boolean enabled;
    private final String promptVersion;
    private final int maxEvidence;
    private final int timeoutSeconds;

    public Stage4Settings(Environment environment) {
        this.enabled = environment.getProperty("aidiag.stage4.enabled", Boolean.class, false);
        this.promptVersion = environment.getProperty("aidiag.stage4.prompt-version", "stage4-v1");
        this.maxEvidence = environment.getProperty("aidiag.stage4.max-evidence", Integer.class, 12);
        this.timeoutSeconds = environment.getProperty("aidiag.stage4.timeout-seconds", Integer.class, 12);
        if (promptVersion.isBlank() || promptVersion.length() > 64) {
            throw new IllegalArgumentException("aidiag.stage4.prompt-version must be 1 to 64 characters");
        }
        if (maxEvidence < 1 || maxEvidence > 20) {
            throw new IllegalArgumentException("aidiag.stage4.max-evidence must be between 1 and 20");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("aidiag.stage4.timeout-seconds must be between 1 and 30");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public int maxEvidence() {
        return maxEvidence;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}
