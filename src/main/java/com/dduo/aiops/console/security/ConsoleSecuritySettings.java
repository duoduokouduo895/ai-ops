package com.dduo.aiops.console.security;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Immutable, fail-closed settings for the browser-facing console boundary. */
@Component
public final class ConsoleSecuritySettings {

    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final boolean enabled;
    private final String authMode;
    private final String accessTeamDomain;
    private final String accessAudience;
    private final String username;
    private final String passwordHash;
    private final String auditPepper;
    private final String allowedOrigin;
    private final boolean secureCookie;
    private final int idleHours;
    private final int absoluteHours;
    private final int maxSessions;
    private final int lockoutMinutes;
    private final int maxFailures;
    private final int retentionDays;

    public ConsoleSecuritySettings(Environment environment) {
        enabled = environment.getProperty("aidiag.console.enabled", Boolean.class, false);
        authMode = environment.getProperty("aidiag.console.auth-mode", "session").trim();
        accessTeamDomain = environment.getProperty("aidiag.console.access.team-domain", "").trim();
        accessAudience = environment.getProperty("aidiag.console.access.audience", "").trim();
        username = environment.getProperty("aidiag.console.username", "aidiag-admin").trim();
        passwordHash = environment.getProperty("aidiag.console.password-hash", "").trim();
        auditPepper = environment.getProperty("aidiag.console.audit-pepper", "").trim();
        allowedOrigin = environment.getProperty("aidiag.console.allowed-origin", "https://ops.dduo.me").trim();
        secureCookie = environment.getProperty("aidiag.console.secure-cookie", Boolean.class, true);
        idleHours = environment.getProperty("aidiag.console.idle-hours", Integer.class, 8);
        absoluteHours = environment.getProperty("aidiag.console.absolute-hours", Integer.class, 24);
        maxSessions = environment.getProperty("aidiag.console.max-sessions", Integer.class, 3);
        lockoutMinutes = environment.getProperty("aidiag.console.lockout-minutes", Integer.class, 15);
        maxFailures = environment.getProperty("aidiag.console.max-failures", Integer.class, 5);
        retentionDays = environment.getProperty("aidiag.console.retention-days", Integer.class, 30);
        validate();
    }

    private void validate() {
        if (!authMode.equals("session") && !authMode.equals("access")) {
            throw new IllegalArgumentException("aidiag.console.auth-mode must be session or access");
        }
        if (!PRINCIPAL.matcher(username).matches()) {
            throw new IllegalArgumentException("aidiag.console.username must be a controlled identifier");
        }
        if (!allowedOrigin.matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) {
            throw new IllegalArgumentException("aidiag.console.allowed-origin must be one HTTPS origin without a path");
        }
        if (idleHours < 1 || idleHours > 24 || absoluteHours < idleHours || absoluteHours > 72) {
            throw new IllegalArgumentException("aidiag.console session durations are outside the reviewed bounds");
        }
        if (maxSessions < 1 || maxSessions > 10 || lockoutMinutes < 1 || lockoutMinutes > 60
                || maxFailures < 2 || maxFailures > 20 || retentionDays < 1 || retentionDays > 365) {
            throw new IllegalArgumentException("aidiag.console security limits are outside the reviewed bounds");
        }
        if (enabled && accessMode()) {
            if (!accessTeamDomain.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.cloudflareaccess\\.com")
                    || accessTeamDomain.contains("replace-before-deploy")) {
                throw new IllegalStateException("Access requires a real team domain without scheme or path");
            }
            if (!accessAudience.matches("[A-Fa-f0-9]{64}")) {
                throw new IllegalStateException("Access requires the console application's 64-character AUD tag");
            }
        }
        if (enabled && !accessMode()) {
            if (!passwordHash.matches("^\\$2[aby]\\$[0-9]{2}\\$.{53}$")) {
                throw new IllegalStateException("Console is enabled without a valid BCrypt password hash secret");
            }
            if (auditPepper.length() < 32) {
                throw new IllegalStateException("Console is enabled without an audit pepper of at least 32 characters");
            }
        }
    }

    public boolean enabled() { return enabled; }
    public boolean accessMode() { return authMode.equals("access"); }
    public String accessIssuer() { return "https://" + accessTeamDomain; }
    public String accessAudience() { return accessAudience; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public String auditPepper() { return auditPepper; }
    public String allowedOrigin() { return allowedOrigin; }
    public boolean secureCookie() { return secureCookie; }
    public Duration idleDuration() { return Duration.ofHours(idleHours); }
    public Duration absoluteDuration() { return Duration.ofHours(absoluteHours); }
    public int maxSessions() { return maxSessions; }
    public Duration lockoutDuration() { return Duration.ofMinutes(lockoutMinutes); }
    public int maxFailures() { return maxFailures; }
    public int retentionDays() { return retentionDays; }
}
