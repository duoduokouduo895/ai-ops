package com.dduo.aiops.diagnosis;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

@Component
public class AiChatDiagnosisRuleProvider implements DiagnosisRuleProvider {

    @Override
    public List<DiagnosisRule> rules() {
        return Stream.of(rulesFor("ai-chat-candidate"), rulesFor("ai-chat-stage5-lab"))
                .flatMap(List::stream).toList();
    }

    private static List<DiagnosisRule> rulesFor(String service) {
        return List.of(
                rule(service, "AiChatModelTimeout",
                        "increase(aichat_chat_requests_total{service_name=\"" + service + "\",outcome=\"timeout\"}[5m])", 1,
                        "model timeout requests in 5m",
                        "{service_name=\"" + service + "\",event=\"ai_chat_stream_finished\",result=\"timeout\"}",
                        "ai_chat_stream_finished:timeout",
                        "{ resource.service.name = \"" + service + "\" && duration > 10s }", "MODEL_PROVIDER_TIMEOUT",
                        "MODEL_PROVIDER_FIXED_DELAY_OR_TIMEOUT", "模型流在受控超时边界内未返回，聊天流以 timeout 结束。",
                        "Verify provider reachability and timeout budget; do not replay user prompts automatically"),
                rule(service, "AiChatR2dbcPoolExhaustion",
                        "max_over_time(aichat_r2dbc_pool_pending{service_name=\"" + service + "\"}[5m])", 1,
                        "maximum pending R2DBC acquisitions in 5m",
                        "{service_name=\"" + service + "\",event=\"r2dbc_pool_acquire\",result=\"timeout\"}",
                        "r2dbc_pool_acquire:timeout",
                        "{ resource.service.name = \"" + service + "\" && span.db.system = \"mysql\" && status = error }",
                        "R2DBC_POOL_EXHAUSTION", "R2DBC_POOL_CAPACITY_HELD_BY_CONTROLLED_LOAD",
                        "R2DBC 获取请求发生排队，并出现受控连接获取超时事件。",
                        "Stop the isolated load and verify pending acquisitions return to zero"),
                rule(service, "AiChatDocumentBacklog",
                        "max_over_time(aichat_document_outbox_pending{service_name=\"" + service + "\"}[10m])", 10,
                        "maximum pending document outbox events in 10m",
                        "{service_name=\"" + service + "\",event=\"document_outbox_dispatch\",result=\"retry\"}",
                        "document_outbox_dispatch:retry",
                        "{ resource.service.name = \"" + service + "\" && span.messaging.system = \"rabbitmq\" && status = error }",
                        "DOCUMENT_PIPELINE_BACKLOG", "ISOLATED_DOCUMENT_CONSUMER_PAUSED_OR_RATE_LIMITED",
                        "文档 outbox 待投递数量持续超过阈值，并出现投递重试事件。",
                        "Restore the isolated consumer or broker path and verify backlog drains before retrying documents"),
                rule(service, "AiChatSearchUnavailable",
                        "increase(aichat_search_requests_total{service_name=\"" + service + "\",outcome=\"error\"}[5m])", 3,
                        "search errors in 5m",
                        "{service_name=\"" + service + "\",event=\"knowledge_search\",result=\"error\"}",
                        "knowledge_search:error",
                        "{ resource.service.name = \"" + service + "\" && span.db.system = \"elasticsearch\" && status = error }",
                        "SEARCH_BACKEND_UNAVAILABLE", "ISOLATED_ELASTICSEARCH_PATH_BLOCKED",
                        "知识检索连续失败，并出现受控 Elasticsearch 不可用事件。",
                        "Restore Elasticsearch reachability and verify a privacy-safe search probe succeeds"));
    }

    private static DiagnosisRule rule(String service, String alertName, String metricQuery, double threshold,
            String metricDescription, String logQuery, String logMarker, String traceQuery, String candidate,
            String groundTruth, String description, String action) {
        return new DiagnosisRule(service, alertName, metricQuery, threshold, metricDescription, logQuery, logMarker,
                traceQuery, candidate, groundTruth, description, "[{\"action\":\"" + action + "\"}]");
    }
}
