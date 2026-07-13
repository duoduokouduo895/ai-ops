package com.dduo.aiops.diagnosis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class DiagnosisRuleRegistry {

    private final Map<RuleKey, DiagnosisRule> rules;

    public DiagnosisRuleRegistry(List<DiagnosisRuleProvider> providers) {
        var registered = new LinkedHashMap<RuleKey, DiagnosisRule>();
        for (var provider : providers) {
            for (var rule : provider.rules()) {
                var key = new RuleKey(rule.serviceName(), rule.alertName());
                if (registered.putIfAbsent(key, rule) != null) {
                    throw new IllegalStateException("Duplicate diagnosis rule: " + key);
                }
            }
        }
        this.rules = Map.copyOf(registered);
    }

    public Optional<DiagnosisRule> find(String serviceName, String alertName) {
        if (serviceName == null || alertName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rules.get(new RuleKey(serviceName, alertName)));
    }

    private record RuleKey(String serviceName, String alertName) {
    }
}
