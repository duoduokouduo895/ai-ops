package com.dduo.aiops.diagnosis;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class FaultLabDiagnosisRuleProvider implements DiagnosisRuleProvider {

    @Override
    public List<DiagnosisRule> rules() {
        return List.of(
                rule("FaultLabHikariPoolExhausted", "faultlab_hikari_holders_active{service_name=\"payment-service\"}", 4,
                        "Hikari active holders", "faultlab_hikari_connection_timeout", "HIKARI_POOL_EXHAUSTION",
                        "CONTROLLED_HIKARI_POOL_EXHAUSTION", "受控 Hikari 持有连接达到池上限，并出现连接获取超时事件。",
                        "Disable the controlled Hikari fault and verify pool recovery"),
                rule("FaultLabThreadPoolRejected", "faultlab_thread_pool_saturated{service_name=\"payment-service\"}", 1,
                        "worker saturation", "faultlab_thread_pool_rejected", "THREAD_POOL_EXHAUSTION",
                        "CONTROLLED_THREAD_POOL_EXHAUSTION", "受控 Payment 工作线程池发生任务拒绝。",
                        "Disable the controlled thread-pool fault and verify request recovery"),
                rule("FaultLabDownstreamTimeout",
                        "faultlab_fault_active{service_name=\"payment-service\",fault=\"downstream-timeout\"}", 1,
                        "controlled downstream-timeout state", "faultlab_downstream_delay_started", "DOWNSTREAM_TIMEOUT",
                        "CONTROLLED_DOWNSTREAM_TIMEOUT", "受控 Payment 下游延迟处于开启状态，并出现下游延迟事件。",
                        "Disable the controlled downstream-timeout fault and verify Order recovery"),
                rule("FaultLabDatabaseSlowQuery",
                        "faultlab_fault_active{service_name=\"payment-service\",fault=\"database-slow-query\"}", 1,
                        "controlled database-slow-query state", "faultlab_database_slow_query_started", "DATABASE_SLOW_QUERY",
                        "CONTROLLED_DATABASE_SLOW_QUERY", "受控 Payment 数据库慢查询处于开启状态，并出现慢查询事件。",
                        "Disable the controlled database-slow-query fault and verify Order recovery"));
    }

    private static DiagnosisRule rule(String alertName, String metricQuery, double threshold, String metricDescription,
            String logEvent, String candidate, String groundTruth, String description, String action) {
        return new DiagnosisRule("payment-service", alertName, metricQuery, threshold, metricDescription,
                "{service_name=\"payment-service\"} |= \"" + logEvent + "\"", logEvent,
                "{ resource.service.name = \"payment-service\" }", candidate, groundTruth, description,
                "[{\"action\":\"" + action + "\"}]");
    }
}
