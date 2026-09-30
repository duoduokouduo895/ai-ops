<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import HintTerm from '../components/HintTerm.vue'
import IncidentRecord from '../components/IncidentRecord.vue'
import PageHeader from '../components/PageHeader.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import TimeSeriesChart from '../components/TimeSeriesChart.vue'
import { consoleApi } from '../api/client'
import type { IncidentSummary, SignalRange, SignalView, SignalsView } from '../api/types'
import { seriesColor, formatValue, type ChartSeries } from '../utils/chart'
import { isNotFound, isUnauthorized } from '../utils/http'
import { formatLocal } from '../utils/time'

const SERVICE_ID = 'ai-chat'
const RANGES: SignalRange[] = ['1h', '6h', '24h']
const GROUPS = [
  { id: 'golden', title: '可用性与请求', lead: '用户能否访问、请求以什么结果结束、首 Token 等待多久。' },
  { id: 'saturation', title: '容量', lead: '流式连接、数据库连接池与文档投递是否开始排队。' },
  { id: 'dependency', title: '依赖', lead: '知识检索依赖 Elasticsearch 的失败情况。' },
]

const route = useRoute()
const router = useRouter()
const range = computed<SignalRange>(() => {
  const value = route.query.range
  return typeof value === 'string' && (RANGES as string[]).includes(value) ? value as SignalRange : '1h'
})

const view = ref<SignalsView | null>(null)
const incidents = ref<IncidentSummary[]>([])
const incidentsFailed = ref(false)
const loading = ref(true)
const failed = ref<'unavailable' | 'missing' | null>(null)
let requestId = 0

async function load() {
  const id = ++requestId
  loading.value = true
  failed.value = null
  try {
    const signals = (await consoleApi.signals(SERVICE_ID, range.value)).data
    if (id !== requestId) return
    view.value = signals
    loadIncidents(signals.otelServiceName)
  } catch (error) {
    if (id !== requestId || isUnauthorized(error)) return
    failed.value = isNotFound(error) ? 'missing' : 'unavailable'
  } finally {
    if (id === requestId) loading.value = false
  }
}

async function loadIncidents(service: string) {
  incidentsFailed.value = false
  try {
    incidents.value = (await consoleApi.incidents({ limit: 5, service })).data.items
  } catch (error) {
    if (!isUnauthorized(error)) incidentsFailed.value = true
  }
}

function selectRange(next: SignalRange) {
  if (next !== range.value) void router.replace({ query: { ...route.query, range: next } })
}

watch(range, load)
onMounted(load)

const start = computed(() => (view.value ? Date.parse(view.value.start) / 1000 : 0))
const end = computed(() => (view.value ? Date.parse(view.value.end) / 1000 : 0))

function chartSeries(signal: SignalView): ChartSeries[] {
  const single = signal.series.length <= 1
  return signal.series.map((item) => ({
    label: single ? signal.title : item.label,
    color: seriesColor(item.label, single),
    points: item.points.map((point) => [point.t, point.v] as [number, number]),
  }))
}

const grouped = computed(() => GROUPS.map((group) => ({
  ...group,
  signals: (view.value?.signals ?? []).filter((signal) => signal.group === group.id),
})).filter((group) => group.signals.length))

const availability = computed(() => view.value?.signals.find((signal) => signal.id === 'availability') ?? null)
const availabilityRatio = computed(() => {
  const points = availability.value?.series.flatMap((item) => item.points) ?? []
  if (!points.length) return null
  return points.filter((point) => point.v >= 0.5).length / points.length
})
const firingRules = computed(() => view.value?.signals.filter((signal) => signal.alertState === 'FIRING').length ?? 0)
const breaches = computed(() => view.value?.signals.filter((signal) => signal.status === 'BREACH').length ?? 0)
const rulesKnown = computed(() => view.value?.signals.every((signal) => signal.alertState !== 'UNKNOWN') ?? false)
</script>

<template>
  <AppShell section="ai-chat">
    <PageHeader title="AI-chat" topic="page.ai-chat">
      <template #actions>
        <p v-if="view" class="read-at mono"><HintTerm topic="term.queried-at">查询时间</HintTerm> {{ formatLocal(view.queriedAt) }}</p>
        <div class="ranges" role="radiogroup" aria-label="时间窗口">
          <button v-for="item in RANGES" :key="item" type="button" role="radio" :aria-checked="range === item" :class="{ selected: range === item }" @click="selectRange(item)">{{ item }}</button>
        </div>
        <button class="action-button" type="button" :disabled="loading" @click="load">刷新</button>
      </template>
    </PageHeader>

    <StatePanel v-if="failed === 'missing'" title="Service Catalog 中没有 AI-chat 信号配置" detail="只有 diagnosisProfile 为 ai-chat-v1 的服务才提供固定信号。" />
    <StatePanel v-else-if="failed" kind="error" title="暂时无法读取 AI-chat 信号" detail="控制面或 Prometheus 不可达。缺失的数据不会被画成 0。" action="重新检查" @action="load" />
    <div v-else-if="!view" class="skeleton-stack" role="status" aria-label="正在读取信号"><span class="skeleton" /><span class="skeleton" /><span class="skeleton" /></div>
    <template v-else>
      <section class="summary" :class="{ refreshing: loading }" aria-label="AI-chat 当前摘要">
        <dl>
          <div>
            <dt>外部探针</dt>
            <dd><StatusBadge domain="signal" :value="String(availability?.status ?? 'NO_DATA')" /> <span class="mono">{{ formatValue(availability?.current, 'up') }}</span></dd>
          </div>
          <div>
            <dt><HintTerm topic="term.availability-ratio">Availability · {{ view.range }}</HintTerm></dt>
            <dd class="mono">{{ availabilityRatio === null ? '—' : `${(availabilityRatio * 100).toFixed(availabilityRatio === 1 ? 0 : 2)}%` }}</dd>
          </div>
          <div>
            <dt>触发中的规则</dt>
            <dd class="mono">{{ rulesKnown ? firingRules : '未知' }}</dd>
          </div>
          <div>
            <dt><HintTerm topic="term.threshold">越线信号</HintTerm></dt>
            <dd class="mono">{{ breaches }}</dd>
          </div>
        </dl>
        <p class="identity mono">{{ view.otelServiceName }} · {{ view.environment }} · 步长 {{ view.stepSeconds }}s</p>
      </section>

      <section v-for="group in grouped" :key="group.id" class="group" :class="{ refreshing: loading }" :aria-labelledby="`group-${group.id}`">
        <header class="group-head">
          <h2 :id="`group-${group.id}`" class="section-title">{{ group.title }}</h2>
          <p>{{ group.lead }}</p>
        </header>
        <div class="signals">
          <article v-for="signal in group.signals" :key="signal.id" class="signal" :data-status="signal.status">
            <header>
              <div class="signal-title">
                <h3>{{ signal.title }}</h3>
                <strong class="mono">{{ signal.series.length > 1 ? '' : formatValue(signal.current, signal.unit) }}</strong>
              </div>
              <div class="signal-meta">
                <StatusBadge domain="signal" :value="signal.status" />
                <template v-if="signal.alertName">
                  <span class="rule mono">{{ signal.alertName }}</span>
                  <StatusBadge domain="rule" :value="signal.alertState ?? 'UNKNOWN'" />
                </template>
              </div>
            </header>
            <p v-if="signal.status === 'QUERY_FAILED'" class="failed">这条信号本次查询失败（{{ signal.reason }}），其他信号不受影响。</p>
            <TimeSeriesChart
              v-else
              :series="chartSeries(signal)"
              :start="start"
              :end="end"
              :step-seconds="view.stepSeconds"
              :unit="signal.unit"
              :threshold="signal.threshold"
              :label="`${signal.title}，最近 ${view.range}`"
              :height="signal.id === 'availability' ? 120 : 168"
            />
            <p class="description">{{ signal.description }}</p>
          </article>
        </div>
      </section>

      <section class="related" aria-labelledby="related-incidents">
        <div class="section-head">
          <h2 id="related-incidents" class="section-title">相关事件</h2>
          <RouterLink :to="{ name: 'incidents', query: { service: view.otelServiceName } }">查看全部<span aria-hidden="true"> →</span></RouterLink>
        </div>
        <StatePanel v-if="incidentsFailed" kind="error" title="无法读取相关事件" detail="信号图不受影响。" action="重试" @action="loadIncidents(view.otelServiceName)" />
        <StatePanel v-else-if="!incidents.length" title="当前查询未返回 AI-chat 事件" detail="这只说明没有事件记录，不能据此宣称 AI-chat 全部健康。" />
        <div v-else class="incident-list">
          <IncidentRecord v-for="item in incidents" :key="item.id" :item="item" />
        </div>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
.read-at { margin: 0; color: var(--text-muted); font-size: 13px; }
.ranges { display: inline-flex; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-1); padding: 2px; }
.ranges button { min-width: 44px; min-height: 38px; border: 0; border-radius: var(--radius-xs); background: transparent; color: var(--text-muted); cursor: pointer; font: 600 12px/1 var(--font-mono); }
.ranges button:hover { color: var(--text-soft); }
.ranges button.selected { background: var(--accent-wash); color: var(--text); box-shadow: inset 0 0 0 1px oklch(0.51 0.09 288 / .5); }
.refreshing { opacity: .6; transition: opacity var(--duration-base) var(--ease); }
.summary { margin-bottom: 32px; border-block: 1px solid var(--line); padding: 14px 0 12px; }
.summary dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px 24px; margin: 0; }
dt { color: var(--text-muted); font-size: 13px; }
dd { display: flex; align-items: center; gap: 8px; margin: 6px 0 0; font-size: 18px; font-weight: 600; letter-spacing: -.02em; }
.identity { margin: 12px 0 0; color: var(--text-faint); font-size: 12px; }
.group { margin-bottom: 36px; }
.group-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 14px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.group-head p { margin: 0; color: var(--text-muted); font-size: 13px; }
.signals { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 40px; }
.signal { min-width: 0; border-bottom: 1px solid var(--line-soft); padding: 18px 0 14px; }
.signal > header { display: grid; gap: 6px; margin-bottom: 10px; }
.signal-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.signal-title h3 { margin: 0; font-size: 14px; font-weight: 620; }
.signal-title strong { color: var(--text); font-size: 16px; font-weight: 620; white-space: nowrap; }
.signal[data-status="BREACH"] .signal-title strong { color: var(--critical); }
.signal-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
.rule { color: var(--text-muted); font-size: 12px; }
.description { margin: 8px 0 0; color: var(--text-muted); font-size: 12px; }
.failed { display: grid; min-height: 120px; place-items: center; margin: 0; border: 1px dashed var(--line); border-radius: var(--radius-sm); color: var(--text-muted); font-size: 13px; text-align: center; padding: 16px; }
.section-head { display: flex; align-items: end; justify-content: space-between; gap: 16px; padding-bottom: 8px; }
.section-head a { display: inline-flex; min-height: 44px; align-items: center; color: var(--accent-strong); font-size: 13px; }
.incident-list { border-bottom: 1px solid var(--line); }
.skeleton-stack { display: grid; gap: 16px; }
.skeleton-stack span:first-child { height: 72px; }
.skeleton-stack span { height: 220px; }
@media (max-width: 900px) {
  .signals { grid-template-columns: 1fr; }
  .summary dl { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
