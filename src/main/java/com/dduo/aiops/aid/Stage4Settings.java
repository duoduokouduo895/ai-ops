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
    private final int dailyRequestLimit;
    private final int maxPromptChars;
    private final int maxOutputTokens;
    private final int retentionDays;

    public Stage4Settings(Environment environment) {
        this.enabled = environment.getProperty("aidiag.stage4.enabled", Boolean.class, false);
        this.promptVersion = environment.getProperty("aidiag.stage4.prompt-version", "stage4-v1");
        this.maxEvidence = environment.getProperty("aidiag.stage4.max-evidence", Integer.class, 12);
        this.timeoutSeconds = environment.getProperty("aidiag.stage4.timeout-seconds", Integer.class, 12);
        this.dailyRequestLimit = environment.getProperty("aidiag.stage4.daily-request-limit", Integer.class, 20);
        this.maxPromptChars = environment.getProperty("aidiag.stage4.max-prompt-chars", Integer.class, 16_000);
        this.maxOutputTokens = environment.getProperty("aidiag.stage4.max-output-tokens", Integer.class, 1_200);
        this.retentionDays = environment.getProperty("aidiag.stage4.retention-days", Integer.class, 30);
        if (promptVersion.isBlank() || promptVersion.length() > 64) {
            throw new IllegalArgumentException("aidiag.stage4.prompt-version must be 1 to 64 characters");
        }
        if (maxEvidence < 1 || maxEvidence > 20) {
            throw new IllegalArgumentException("aidiag.stage4.max-evidence must be between 1 and 20");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("aidiag.stage4.timeout-seconds must be between 1 and 30");
        }
        if (dailyRequestLimit < 1 || dailyRequestLimit > 100) {
            throw new IllegalArgumentException("aidiag.stage4.daily-request-limit must be between 1 and 100");
        }
        if (maxPromptChars < 1_000 || maxPromptChars > 20_000) {
            throw new IllegalArgumentException("aidiag.stage4.max-prompt-chars must be between 1000 and 20000");
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 4_096) {
            throw new IllegalArgumentException("aidiag.stage4.max-output-tokens must be between 256 and 4096");
        }
        if (retentionDays < 1 || retentionDays > 365) {
            throw new IllegalArgumentException("aidiag.stage4.retention-days must be between 1 and 365");
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

    public int dailyRequestLimit() {
        return dailyRequestLimit;
    }

    public int maxPromptChars() {
        return maxPromptChars;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int retentionDays() {
        return retentionDays;
    }
}
