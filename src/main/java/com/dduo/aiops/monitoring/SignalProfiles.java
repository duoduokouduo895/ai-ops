package com.dduo.aiops.monitoring;

import java.util.List;
import java.util.Map;

/**
 * Signal sets keyed by catalog {@code diagnosisProfile}. The AI-chat set mirrors the bounded-cardinality telemetry
 * contract and the thresholds of the {@code ai-chat-runtime} / {@code ai-chat-external-availability} rule groups,
 * so the console shows exactly what the alerting plane evaluates. {@code MonitoringSignalContractTests} guards the
 * thresholds against drift.
 */
public final class SignalProfiles {

    public static final String AI_CHAT = "ai-chat-v1";

    private static final List<SignalDefinition> AI_CHAT_SIGNALS = List.of(
            new SignalDefinition("availability", "golden", "外部可用性", "up",
                    "独立监控面的黑盒健康探针；0 表示探测失败，持续 2 分钟触发告警。",
                    "max(probe_success{job=\"blackbox-ai-chat\",target_kind=\"application\",service_name=\"%s\"})",
                    null, null, "AiChatProcessUnavailable"),
            new SignalDefinition("chat-throughput", "golden", "聊天请求（按结果）", "req/s",
                    "聊天流按结束结果分组的每秒请求数，outcome 为受控低基数标签。",
                    "sum by (outcome) (rate(aichat_chat_requests_total{service_name=\"%s\"}[5m]))",
                    "outcome", null, null),
            new SignalDefinition("ttft-p95", "golden", "首 Token 延迟 P95", "s",
                    "从请求进入到模型返回第一个 Token 的 95 分位耗时。",
                    "histogram_quantile(0.95, sum by (le) (rate(aichat_chat_ttft_seconds_bucket{service_name=\"%s\"}[5m])))",
                    null, null, null),
            new SignalDefinition("model-timeouts", "golden", "模型超时（5m）", "count",
                    "5 分钟内以 timeout 结束的聊天流数量。",
                    "sum(increase(aichat_chat_requests_total{service_name=\"%s\",outcome=\"timeout\"}[5m]))",
                    null, 1.0, "AiChatModelTimeout"),
            new SignalDefinition("active-streams", "saturation", "活跃 SSE 流", "streams",
                    "当前仍在推送的聊天流数量。",
                    "sum(aichat_chat_streams_active{service_name=\"%s\"})",
                    null, null, null),
            new SignalDefinition("r2dbc-pending", "saturation", "R2DBC 等待获取", "conn",
                    "5 分钟窗口内排队等待数据库连接的最大数量。",
                    "max(max_over_time(aichat_r2dbc_pool_pending{service_name=\"%s\"}[5m]))",
                    null, 1.0, "AiChatR2dbcPoolExhaustion"),
            new SignalDefinition("document-backlog", "saturation", "文档 Outbox 积压", "events",
                    "10 分钟窗口内尚未投递到 RabbitMQ 的文档事件最大数量。",
                    "max(max_over_time(aichat_document_outbox_pending{service_name=\"%s\"}[10m]))",
                    null, 10.0, "AiChatDocumentBacklog"),
            new SignalDefinition("search-errors", "dependency", "知识检索失败（5m）", "count",
                    "5 分钟内 Elasticsearch 检索失败次数。",
                    "sum(increase(aichat_search_requests_total{service_name=\"%s\",outcome=\"error\"}[5m]))",
                    null, 3.0, "AiChatSearchUnavailable"));

    private static final Map<String, List<SignalDefinition>> PROFILES = Map.of(AI_CHAT, AI_CHAT_SIGNALS);

    private SignalProfiles() {
    }

    public static List<SignalDefinition> forProfile(String diagnosisProfile) {
        return diagnosisProfile == null ? List.of() : PROFILES.getOrDefault(diagnosisProfile, List.of());
    }
}
