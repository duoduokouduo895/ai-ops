<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import DisclosureSection from '../components/DisclosureSection.vue'
import HintTerm from '../components/HintTerm.vue'
import PageHeader from '../components/PageHeader.vue'
import StatePanel from '../components/StatePanel.vue'
import TimeSeriesChart from '../components/TimeSeriesChart.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { AiAttempt, Evidence, EvidenceType, IncidentDetail, ReportView, TimelineEntry } from '../api/types'
import { useIncidentsListStore } from '../stores/incidentsList'
import { isNotFound, isUnauthorized } from '../utils/http'
import { isConfirmedRootCause, isEvidenceTimelineEntry, reportLimitTitle } from '../utils/sampleTime'
import { formatDuration, formatLocal, timeZoneName } from '../utils/time'

const route = useRoute()
const list = useIncidentsListStore()
const id = String(route.params.id)
const incident = ref<IncidentDetail | null>(null)
const evidence = ref<Evidence[]>([])
const timeline = ref<TimelineEntry[]>([])
const report = ref<ReportView | null>(null)
const ai = ref<AiAttempt[]>([])
const loading = ref(true)
const failed = ref(false)
const evidenceError = ref(false)
const timelineError = ref(false)
const aiError = ref(false)
const reportState = ref<'loading' | 'ready' | 'missing' | 'error'>('loading')
const readAt = ref<Date | null>(null)
const evidenceSection = ref<{ expand: () => void } | null>(null)
const selected = ref('')
const tab = ref<'ALL' | 'METRIC' | 'LOG' | 'TRACE'>('ALL')
const techOpen = ref(false)

const visibleEvidence = computed(() => tab.value === 'ALL' ? evidence.value : evidence.value.filter((item) => item.type === tab.value))
const selectedEvidence = computed(() => visibleEvidence.value.find((item) => item.id === selected.value) || visibleEvidence.value[0])
const supportedFinding = computed(() => isConfirmedRootCause(report.value))
const unconfirmedCandidates = computed(() => supportedFinding.value ? [] : (report.value?.candidates || []))
const duration = computed(() => {
  if (!incident.value) return '—'
  if (incident.value.resolvedAt) return formatDuration(incident.value.startedAt, incident.value.resolvedAt)
  if (!readAt.value) return '—'
  return `${formatDuration(incident.value.startedAt, readAt.value.toISOString())}（截至本次读取）`
})

/** Downsampled curves have a wider stride than the 30s query step; derive it so real gaps still break the line. */
function curveStep(points: [number, number][]) {
  let step = Number.POSITIVE_INFINITY
  for (let index = 1; index < points.length; index++) step = Math.min(step, points[index][0] - points[index - 1][0])
  return Number.isFinite(step) && step > 0 ? step : 30
}

function evidencePresence(type: EvidenceType) {
  return evidence.value.some((item) => item.type === type) ? 'Available' : 'Missing'
}

function isEvidenceEntry(entry: TimelineEntry) {
  return isEvidenceTimelineEntry(entry)
}

function selectTab(value: typeof tab.value) {
  tab.value = value
  selected.value = (value === 'ALL' ? evidence.value : evidence.value.filter((item) => item.type === value))[0]?.id || ''
}

function selectTimeline(entry: TimelineEntry) {
  if (!isEvidenceEntry(entry)) return
  const item = evidence.value.find((candidate) => candidate.id === entry.evidenceId)
  if (!item) return
  evidenceSection.value?.expand()
  tab.value = item.type === 'METRIC' || item.type === 'LOG' || item.type === 'TRACE' ? item.type : 'ALL'
  selected.value = item.id
}

async function load() {
  loading.value = true
  failed.value = false
  evidenceError.value = false
  timelineError.value = false
  aiError.value = false
  reportState.value = 'loading'
  report.value = null
  try {
    incident.value = (await consoleApi.incident(id)).data
  } catch (error) {
    if (!isUnauthorized(error)) failed.value = true
    loading.value = false
    return
  }
  await Promise.all([
    consoleApi.evidence(id).then((result) => {
      evidence.value = result.data
      selected.value = result.data[0]?.id || ''
    }).catch((error) => { if (!isUnauthorized(error)) evidenceError.value = true }),
    consoleApi.timeline(id).then((result) => { timeline.value = result.data })
      .catch((error) => { if (!isUnauthorized(error)) timelineError.value = true }),
    consoleApi.aiAttempts(id).then((result) => { ai.value = result.data })
      .catch((error) => { if (!isUnauthorized(error)) aiError.value = true }),
    consoleApi.report(id).then((result) => {
      report.value = result.data
      reportState.value = 'ready'
    }).catch((error) => {
      if (isUnauthorized(error)) return
      reportState.value = isNotFound(error) ? 'missing' : 'error'
    }),
  ])
  readAt.value = new Date()
  loading.value = false
}

onMounted(load)
</script>

<template>
  <AppShell section="incidents" width="casefile">
    <nav class="crumb mono" aria-label="Breadcrumb">
      <RouterLink :to="{ name: 'incidents', query: list.query }">Incidents</RouterLink>
      <span aria-hidden="true">/</span>
      <span>Casefile</span>
    </nav>
    <PageHeader title="Incident Casefile" topic="page.casefile" />
    <StatePanel v-if="failed" kind="error" title="无法打开 Incident 案卷" detail="API 离线、Session 过期或资源不存在。没有证据时不会生成推断。" action="重试" @action="load"/>
    <div v-else-if="loading" class="case-skeleton" aria-label="正在读取 Incident 案卷"><span class="skeleton"/><span class="skeleton"/><span class="skeleton"/></div>
    <template v-else-if="incident">
      <DisclosureSection title="事件摘要" default-open>
        <p class="lead">{{ incident.title }}</p>
        <dl class="facts">
          <div><dt>Service</dt><dd>{{ incident.primaryService }}</dd></div>
          <div><dt>Environment</dt><dd>{{ incident.environment }}</dd></div>
          <div>
            <dt>Status</dt>
            <dd><StatusBadge domain="incident" :value="incident.status" /></dd>
          </div>
          <div>
            <dt>发生时间</dt>
            <dd class="mono">{{ formatLocal(incident.startedAt) }}</dd>
          </div>
          <div v-if="incident.resolvedAt">
            <dt>恢复时间</dt>
            <dd class="mono">{{ formatLocal(incident.resolvedAt) }}</dd>
          </div>
          <div>
            <dt>持续时间</dt>
            <dd class="mono">{{ duration }}</dd>
          </div>
          <div>
            <dt>Alert</dt>
            <dd class="mono">{{ incident.alertName }}</dd>
          </div>
        </dl>
        <p class="timezone">时间按浏览器时区显示（{{ timeZoneName() }}）</p>
        <details class="tech" :open="techOpen" @toggle="techOpen = ($event.target as HTMLDetailsElement).open">
          <summary>技术信息</summary>
          <dl class="facts">
            <div><dt>Incident ID</dt><dd class="mono">{{ incident.id }}</dd></div>
            <div><dt>Severity</dt><dd><StatusBadge domain="severity" :value="incident.severity" /></dd></div>
            <div><dt>Detected</dt><dd class="mono">{{ formatLocal(incident.detectedAt) }}</dd></div>
            <div><dt>Last alert</dt><dd class="mono">{{ formatLocal(incident.lastAlertAt) }}</dd></div>
            <div><dt>Updated</dt><dd class="mono">{{ formatLocal(incident.updatedAt) }}</dd></div>
            <div v-if="incident.diagnosisRunId"><dt>Run ID</dt><dd class="mono">{{ incident.diagnosisRunId }}</dd></div>
          </dl>
        </details>
      </DisclosureSection>

      <DisclosureSection title="诊断结论" default-open topic="term.deterministic-report">
        <template v-if="reportState === 'loading'">
          <p class="muted">正在读取确定性报告…</p>
        </template>
        <StatePanel v-else-if="reportState === 'error'" kind="error" title="报告请求失败" detail="事件摘要仍然有效。此区域失败不会改写已读取的事件状态。" />
        <StatePanel v-else-if="reportState === 'missing'" title="尚未生成确定性报告" detail="没有报告时不会把候选、错误提示或 AI 状态当成结论。" />
        <template v-else-if="supportedFinding">
          <p class="lead">{{ report?.rootCause }}</p>
          <p v-if="report?.description" class="description">{{ report.description }}</p>
          <dl class="facts">
            <div><dt>Verification</dt><dd><StatusBadge domain="report" :value="report?.verificationStatus || ''" /></dd></div>
            <div>
              <dt><HintTerm topic="term.support-score">Support score</HintTerm></dt>
              <dd class="score">
                <span class="mono">{{ report?.supportScore?.toFixed(1) ?? '—' }}</span>
              </dd>
            </div>
            <div><dt>支持证据</dt><dd class="mono">{{ report?.supportingEvidence ?? 0 }}</dd></div>
            <div><dt>反对证据</dt><dd class="mono">{{ report?.opposingEvidence ?? 0 }}</dd></div>
          </dl>
        </template>
        <template v-else>
          <StatePanel :title="reportLimitTitle(report)" :detail="report?.dispositionReason || (report?.verificationStatus ? `当前验证状态为 ${report.verificationStatus}。弱支持、证据不足或被否定都不能当作结论。` : '主要根因为空。空字段不会被编造成恢复原因或弃权原因。')" />
          <p v-if="report?.description" class="description">{{ report.description }}</p>
          <ul v-if="report?.limitations?.length" class="limits">
            <li v-for="item in report.limitations" :key="item">{{ item }}</li>
          </ul>
          <div v-if="unconfirmedCandidates.length" class="candidates">
            <h3>未确认候选</h3>
            <p class="muted">这些候选不能当作已确认根因。</p>
            <ul>
              <li v-for="item in unconfirmedCandidates" :key="item.id">
                <strong>{{ item.name }}</strong>
                <StatusBadge domain="report" :value="item.verificationStatus || ''" />
                <span class="mono">{{ item.supportScore?.toFixed(1) ?? '—' }}</span>
              </li>
            </ul>
          </div>
        </template>
        <p class="limit">仅展示脱敏摘要，不公开 raw payload。</p>
      </DisclosureSection>

      <DisclosureSection ref="evidenceSection" title="Evidence" topic="term.evidence" :summary="evidenceError ? '读取失败' : `${evidence.length} 条已读取`">
        <StatePanel v-if="evidenceError" kind="error" title="证据读取失败" detail="已保留事件摘要。缺失证据不会用模拟数据填补。" />
        <template v-else>
          <p class="presence">
            <span v-for="type in ['METRIC', 'LOG', 'TRACE'] as const" :key="type">
              {{ type }}
              <b :data-state="evidencePresence(type)">{{ evidencePresence(type) }}</b>
            </span>
          </p>
          <nav v-if="evidence.length" class="tabs" aria-label="证据类型">
            <button v-for="value in ['ALL', 'METRIC', 'LOG', 'TRACE'] as const" :key="value" type="button" :class="{ active: tab === value }" :aria-pressed="tab === value" @click="selectTab(value)">{{ value }}</button>
          </nav>
          <StatePanel v-if="!evidence.length" title="当前 Incident 没有证据" detail="确定性报告将保持等待或弃权状态。" />
          <StatePanel v-else-if="!visibleEvidence.length" title="当前类型没有证据" detail="Missing 表示本次返回中未见该类证据。" />
          <ul v-else class="evidence-list">
            <li v-for="item in visibleEvidence" :key="item.id">
              <button type="button" :class="{ active: item.id === selectedEvidence?.id }" :aria-pressed="item.id === selectedEvidence?.id" @click="selected = item.id">
                <strong>{{ item.type }}</strong>
                <span class="mono">{{ item.source }} · {{ item.serviceName }}</span>
                <time class="mono">采集 {{ formatLocal(item.collectedAt) }}</time>
                <small>范围 {{ formatLocal(item.startTime) }} → {{ formatLocal(item.endTime) }}</small>
                <p>{{ item.summary }}</p>
              </button>
              <div v-if="item.type === 'METRIC'" class="metric-curve">
                <p class="curve-head"><HintTerm topic="term.metric-curve">证据曲线</HintTerm></p>
                <TimeSeriesChart
                  v-if="item.metric?.points.length"
                  :series="[{ label: item.serviceName, color: 'var(--chart-single)', points: item.metric.points }]"
                  :start="Date.parse(item.startTime) / 1000"
                  :end="Date.parse(item.endTime) / 1000"
                  :step-seconds="curveStep(item.metric.points)"
                  unit="value"
                  :threshold="item.metric.threshold"
                  :height="140"
                  :label="`${item.serviceName} 诊断窗口内的指标曲线`"
                />
                <p v-else class="curve-missing">这条证据在曲线采集上线前生成，只保存了最大值{{ item.metric?.maximum != null ? `（${item.metric.maximum}）` : '' }}。</p>
              </div>
            </li>
          </ul>
        </template>
      </DisclosureSection>

      <DisclosureSection title="Timeline" topic="term.timeline" :summary="timelineError ? '读取失败' : `${timeline.length} 条记录`">
        <StatePanel v-if="timelineError" kind="error" title="时间线读取失败" detail="已保留事件摘要。" />
        <StatePanel v-else-if="!timeline.length" title="暂无时间线事件" />
        <ol v-else class="timeline">
          <li v-for="entry in timeline" :key="entry.type + entry.evidenceId + entry.occurredAt">
            <button v-if="isEvidenceEntry(entry)" type="button" :class="{ active: entry.evidenceId === selected }" @click="selectTimeline(entry)">
              <strong>{{ entry.type.replaceAll('_', ' ') }}</strong>
              <time class="mono">{{ formatLocal(entry.occurredAt) }}</time>
              <small>{{ entry.summary }}</small>
            </button>
            <div v-else class="moment">
              <strong>{{ entry.type.replaceAll('_', ' ') }}</strong>
              <time class="mono">{{ formatLocal(entry.occurredAt) }}</time>
              <small>{{ entry.summary }}</small>
            </div>
          </li>
        </ol>
      </DisclosureSection>

      <DisclosureSection title="Run details 与 AI advisory" topic="term.diagnosis-run" :summary="incident.diagnosisRunStatus || '无任务记录'">
        <dl class="facts">
          <div>
            <dt>Run status</dt>
            <dd><StatusBadge v-if="incident.diagnosisRunStatus" domain="run" :value="incident.diagnosisRunStatus" /><span v-else>—</span></dd>
          </div>
          <div><dt>Attempt</dt><dd class="mono">{{ incident.attempt ?? '—' }}</dd></div>
          <div><dt>Failure code</dt><dd class="mono">{{ incident.failureCode ?? '—' }}</dd></div>
          <div><dt>Run started</dt><dd class="mono">{{ formatLocal(incident.diagnosisStartedAt) }}</dd></div>
          <div><dt>Run finished</dt><dd class="mono">{{ formatLocal(incident.diagnosisFinishedAt) }}</dd></div>
        </dl>
        <h3>AI advisory</h3>
        <p class="limit">AI 为辅助信息，不能覆盖确定性报告。现有接口没有建议正文时不会补造。</p>
        <StatePanel v-if="aiError" kind="error" title="AI 尝试记录读取失败" detail="不影响已展示的事件摘要和确定性结论。" />
        <p v-else-if="!ai.length">没有 AI 尝试记录。</p>
        <ul v-else class="ai-list">
          <li v-for="attempt in ai" :key="attempt.id">
            <StatusBadge domain="ai" :value="attempt.status" />
            <span class="mono">{{ attempt.modelName || attempt.failureCode || 'controlled fallback' }}</span>
            <time class="mono">{{ formatLocal(attempt.createdAt) }}</time>
          </li>
        </ul>
      </DisclosureSection>
    </template>
  </AppShell>
</template>

<style scoped>
.crumb { display: flex; gap: 10px; margin-bottom: 12px; color: var(--text-muted); font-size: 12px; }
.crumb a { color: var(--text-soft); }
.lead { margin: 0 0 16px; font-size: 16px; overflow-wrap: anywhere; }
.description { max-width: 70ch; margin: 0 0 16px; color: var(--text-soft); white-space: pre-wrap; }
.facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 20px; margin: 0; }
dt { color: var(--text-muted); font-size: 12px; font-weight: 600; }
dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.score { display: flex; align-items: center; gap: 4px; }
.timezone, .limit, .muted { margin: 14px 0 0; color: var(--text-muted); font-size: 13px; }
.tech { margin-top: 16px; }
.tech summary { min-height: 44px; cursor: pointer; color: var(--accent-strong); font-size: 13px; font-weight: 600; }
.tech .facts { margin-top: 12px; }
.presence { display: flex; flex-wrap: wrap; gap: 12px 18px; align-items: center; margin: 0 0 14px; color: var(--text-soft); font-size: 13px; }
.presence b[data-state="Available"] { color: var(--success); font-weight: 650; }
.presence b[data-state="Missing"] { color: var(--text-muted); font-weight: 650; }
.tabs { display: flex; gap: 16px; overflow-x: auto; border-bottom: 1px solid var(--line); }
.tabs button { display: inline-flex; min-height: 44px; align-items: center; border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 0 2px; color: var(--text-muted); font: 600 12px var(--font-mono); cursor: pointer; }
.tabs button.active { border-color: var(--accent); color: var(--accent-strong); }
.metric-curve { padding: 4px 12px 14px; }
.curve-head { margin: 0 0 6px; color: var(--text-muted); font-size: 12px; }
.curve-missing { margin: 0; color: var(--text-muted); font-size: 12px; }
.evidence-list, .timeline, .ai-list { margin: 0; padding: 0; list-style: none; }
.evidence-list button, .timeline button, .moment {
  display: grid; width: 100%; gap: 4px; border: 1px solid transparent; border-radius: var(--radius-sm);
  background: transparent; padding: 12px 8px; color: var(--text); text-align: left;
}
.evidence-list button { cursor: pointer; }
.evidence-list button:hover, .timeline button:hover { background: var(--surface-1); }
.evidence-list button.active, .timeline button.active { border-color: oklch(0.54 .1 288 / .58); background: var(--accent-wash); }
.evidence-list p { max-width: 70ch; margin: 6px 0 0; color: var(--text-soft); white-space: pre-wrap; }
.evidence-list small, .timeline time, .timeline small, .moment time, .moment small, .ai-list time { color: var(--text-muted); font-size: 12px; }
h3 { margin: 22px 0 8px; font-size: 14px; }
.limits { max-width: 70ch; margin: 12px 0 0; padding-left: 18px; color: var(--text-soft); }
.candidates { margin-top: 16px; }
.candidates ul { margin: 0; padding: 0; list-style: none; }
.candidates li { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; border-top: 1px solid var(--line-soft); padding: 10px 0; }
.candidates .muted { display: flex; align-items: center; gap: 4px; margin: 0 0 8px; }
.ai-list li { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; border-top: 1px solid var(--line-soft); padding: 10px 0; }
.case-skeleton { display: grid; gap: 12px; }
.case-skeleton span { height: 88px; }
@media (max-width: 640px) { .facts { grid-template-columns: 1fr; } }
</style>
