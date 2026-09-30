<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Activity,
  BatteryCharging,
  Cloud,
  Cpu,
  HardDrive,
  History,
  MemoryStick,
  Network,
  RefreshCw,
  Server,
  ShieldCheck,
  Thermometer,
  ChevronDown,
  CircleAlert,
  CircleCheck,
} from '@lucide/vue'
import type { HistoryResponse, Overview, ServiceState, ServiceSummary } from '../shared/homeops'
import { fetchHistory, fetchOverview } from './api'
import SparklineChart from './components/SparklineChart.vue'
import StatusPill from './components/StatusPill.vue'

type HistoryRange = HistoryResponse['range']
type ServiceGroup = {
  key: ServiceSummary['group']
  label: string
  description: string
  services: ServiceSummary[]
  state: ServiceState
  onlineCount: number
}

const overview = ref<Overview | null>(null)
const history = ref<HistoryResponse | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const historyLoading = ref(false)
const error = ref<string | null>(null)
const historyError = ref<string | null>(null)
const historyRange = ref<HistoryRange>('24h')
const expandedGroups = ref<Set<ServiceSummary['group']>>(new Set())
let timer: number | undefined
let activeLoad: Promise<void> | null = null
let historyRequestId = 0

const stateCopy = computed(() => overview.value?.state ?? 'unknown')
const groupedServices = computed<ServiceGroup[]>(() => {
  const groups: Array<Omit<ServiceGroup, 'state' | 'onlineCount'>> = [
    { key: 'host', label: '主机与入口', description: '宿主机、Docker 与公网维护链路', services: [] },
    { key: 'application', label: '核心应用', description: '对外提供能力的业务服务', services: [] },
    { key: 'data', label: '数据服务', description: '持久化、缓存、消息与检索依赖', services: [] },
    { key: 'telemetry', label: '可观测性', description: '指标、日志与链路追踪', services: [] },
  ]
  for (const service of overview.value?.services ?? []) {
    groups.find((group) => group.key === service.group)?.services.push(service)
  }
  return groups.map((group) => {
    const onlineCount = group.services.filter((service) => service.state === 'up').length
    const state: ServiceState = group.services.some((service) => service.state === 'down')
      ? 'down'
      : group.services.some((service) => service.state === 'unknown') || group.services.length === 0
        ? 'unknown'
        : 'up'
    return { ...group, state, onlineCount }
  })
})
const activeServices = computed(() => overview.value?.services.filter((service) => service.state === 'up').length ?? 0)

function emptyValue(): string { return '--' }
function formatPercent(value: number | null | undefined): string { return value == null ? emptyValue() : `${value.toFixed(1)}%` }
function formatTemperature(value: number | null | undefined): string { return value == null ? emptyValue() : `${value.toFixed(1)}°C` }
function formatBytes(value: number | null | undefined): string {
  if (value == null) return emptyValue()
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let current = value
  let index = 0
  while (current >= 1024 && index < units.length - 1) { current /= 1024; index += 1 }
  return `${current.toFixed(index >= 3 ? 1 : 0)} ${units[index]}`
}
function formatRate(value: number | null | undefined): string { return value == null ? emptyValue() : `${formatBytes(value)}/s` }
function formatUptime(seconds: number | null | undefined): string {
  if (seconds == null) return emptyValue()
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  return `${days} 天 ${hours} 小时`
}
function formatRelative(iso: string | null): string {
  if (!iso) return '无时间信息'
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1_000))
  if (seconds < 60) return `${seconds} 秒前`
  if (seconds < 3_600) return `${Math.floor(seconds / 60)} 分钟前`
  return `${Math.floor(seconds / 3_600)} 小时前`
}
function powerSourceLabel(source: Overview['power']['source']): string {
  return { 'wall-meter': '插座实时', battery: '电池实时', 'measured-baseline': '实测基线', unavailable: '暂无数据' }[source]
}
function groupStateLabel(group: ServiceGroup): string {
  if (group.state === 'down') return `${group.onlineCount} / ${group.services.length} 在线`
  if (group.state === 'unknown') return `${group.onlineCount} / ${group.services.length} 已确认`
  return '全部在线'
}
function isExpanded(group: ServiceSummary['group']): boolean { return expandedGroups.value.has(group) }
function toggleGroup(group: ServiceSummary['group']): void {
  const next = new Set(expandedGroups.value)
  if (next.has(group)) next.delete(group)
  else next.add(group)
  expandedGroups.value = next
}

async function requestDashboard(): Promise<void> {
  const requestId = ++historyRequestId
  const [overviewResult, historyResult] = await Promise.allSettled([
    fetchOverview(),
    fetchHistory(historyRange.value),
  ])

  if (overviewResult.status === 'fulfilled') {
    overview.value = overviewResult.value
    error.value = null
  } else {
    error.value = overviewResult.reason instanceof Error ? overviewResult.reason.message : '无法读取 HomeOps 数据'
  }

  if (requestId !== historyRequestId) return
  if (historyResult.status === 'fulfilled') {
    history.value = historyResult.value
    historyError.value = null
  } else {
    historyError.value = historyResult.reason instanceof Error ? historyResult.reason.message : '历史趋势暂时不可用'
  }
}

async function load(silent = false): Promise<void> {
  if (activeLoad) return activeLoad
  if (silent) refreshing.value = true
  else loading.value = true

  activeLoad = requestDashboard()
  try {
    await activeLoad
  } finally {
    activeLoad = null
    loading.value = false
    refreshing.value = false
  }
}

async function selectHistoryRange(range: HistoryRange): Promise<void> {
  if (historyLoading.value || range === historyRange.value) return
  const previousRange = historyRange.value
  const requestId = ++historyRequestId
  historyRange.value = range
  historyLoading.value = true
  try {
    const nextHistory = await fetchHistory(range)
    if (requestId !== historyRequestId) return
    history.value = nextHistory
    historyError.value = null
  } catch (cause) {
    if (requestId !== historyRequestId) return
    historyRange.value = previousRange
    historyError.value = cause instanceof Error ? cause.message : '历史趋势暂时不可用'
  } finally {
    historyLoading.value = false
  }
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => void load(true), 30_000)
})
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="#top" aria-label="HomeOps 首页">
        <span class="brand-mark"><Server :size="18" aria-hidden="true" /></span>
        <span><strong>HomeOps</strong><small>家庭服务器状态</small></span>
      </a>
      <nav class="topbar-actions" aria-label="页面操作">
        <span v-if="overview" class="freshness"><i :class="overview.freshness.state" />{{ formatRelative(overview.freshness.observedAt) }}更新</span>
        <a class="history-link" href="#history" aria-label="查看历史"><History :size="16" aria-hidden="true" />查看历史</a>
        <button type="button" class="icon-button" :disabled="refreshing" aria-label="刷新数据" @click="load(true)">
          <RefreshCw :size="17" :class="{ spinning: refreshing }" aria-hidden="true" />
        </button>
      </nav>
    </header>

    <main id="top">
      <div v-if="loading && !overview" class="initial-loading" aria-live="polite">
        <span class="loading-block large" /><span class="loading-block" />
        <p>正在建立家庭服务器视图...</p>
      </div>

      <section v-else-if="error && !overview" class="offline-state" aria-live="assertive">
        <Cloud :size="30" aria-hidden="true" />
        <p class="eyebrow">边缘页面在线，源站状态未确认</p>
        <h1>家庭服务器数据暂时不可达</h1>
        <p>{{ error }}。这通常意味着家庭主机、Tunnel、Grafana 或 Access 服务链路不可用。</p>
        <button type="button" @click="load()"><RefreshCw :size="16" />重新检查</button>
      </section>

      <template v-else-if="overview">
        <section class="overall-card" :class="`state-${stateCopy}`" aria-labelledby="overall-title">
          <div class="overall-titlebar">
            <CircleCheck v-if="stateCopy === 'healthy'" :size="21" aria-hidden="true" />
            <CircleAlert v-else :size="21" aria-hidden="true" />
            <h1 id="overall-title">{{ overview.headline }}</h1>
            <StatusPill :state="overview.state" compact />
          </div>
          <div class="overall-body">
            <p>{{ overview.summary }}</p>
            <dl>
              <div><dt>连续运行</dt><dd>{{ formatUptime(overview.system.uptimeSeconds) }}</dd></div>
              <div><dt>服务在线</dt><dd>{{ activeServices }} / {{ overview.services.length }}</dd></div>
              <div><dt>活动告警</dt><dd>{{ overview.alerts.length }}</dd></div>
              <div><dt>数据来源</dt><dd>{{ overview.siteLabel }}</dd></div>
            </dl>
          </div>
        </section>

        <p v-if="error" class="refresh-warning" role="status"><CircleAlert :size="16" aria-hidden="true" />最新刷新失败，当前显示上次成功数据。{{ error }}</p>

        <section class="system-panel panel" aria-labelledby="system-status-title">
          <div class="panel-heading system-heading">
            <div><p class="eyebrow">当前快照</p><h2 id="system-status-title">系统状态</h2></div>
            <span><ShieldCheck :size="15" aria-hidden="true" />只读视图</span>
          </div>

          <div class="system-groups">
            <article v-for="group in groupedServices" :key="group.key" class="system-group">
              <button type="button" class="group-summary" :aria-expanded="isExpanded(group.key)" :aria-controls="`group-${group.key}`" @click="toggleGroup(group.key)">
                <span class="group-state-icon" :class="group.state">
                  <CircleCheck v-if="group.state === 'up'" :size="17" aria-hidden="true" />
                  <CircleAlert v-else :size="17" aria-hidden="true" />
                </span>
                <span class="group-title"><strong>{{ group.label }}</strong><small>{{ group.services.length }} 个组件</small></span>
                <span class="group-result" :class="group.state">{{ groupStateLabel(group) }}</span>
                <ChevronDown :size="17" :class="{ expanded: isExpanded(group.key) }" aria-hidden="true" />
              </button>

              <div class="component-strip" :aria-label="`${group.label}组件状态条`">
                <span v-for="service in group.services" :key="service.id" class="component-segment" :class="service.state" :title="`${service.name}: ${service.state === 'up' ? '在线' : service.state === 'down' ? '离线' : '未知'}`" />
              </div>

              <div v-if="isExpanded(group.key)" :id="`group-${group.key}`" class="component-list">
                <p class="group-description">{{ group.description }}</p>
                <div v-for="service in group.services" :key="service.id" class="component-row">
                  <span class="component-name"><strong>{{ service.name }}</strong><small>{{ service.detail }}</small></span>
                  <StatusPill :state="service.state" compact />
                </div>
              </div>
            </article>
          </div>
        </section>

        <section class="host-panel panel" aria-labelledby="host-title">
          <div class="panel-heading"><div><p class="eyebrow">主机快照</p><h2 id="host-title">资源与温度</h2></div><span>当前读数</span></div>
          <div class="metric-grid">
            <article><span class="metric-icon"><Cpu :size="17" /></span><div><small>CPU</small><strong>{{ formatPercent(overview.system.cpuPercent) }}</strong><em>Load {{ overview.system.load1 ?? emptyValue() }}</em></div></article>
            <article><span class="metric-icon"><MemoryStick :size="17" /></span><div><small>内存</small><strong>{{ formatPercent(overview.system.memoryUsedPercent) }}</strong><em>{{ formatBytes(overview.system.memoryUsedBytes) }} / {{ formatBytes(overview.system.memoryTotalBytes) }}</em></div></article>
            <article><span class="metric-icon"><HardDrive :size="17" /></span><div><small>根磁盘</small><strong>{{ formatPercent(overview.system.rootDiskUsedPercent) }}</strong><em>{{ formatBytes(overview.system.rootDiskUsedBytes) }} / {{ formatBytes(overview.system.rootDiskTotalBytes) }}</em></div></article>
            <article><span class="metric-icon"><Thermometer :size="17" /></span><div><small>CPU / NVMe</small><strong>{{ formatTemperature(overview.system.cpuTemperatureCelsius) }}</strong><em>SSD {{ formatTemperature(overview.system.nvmeTemperatureCelsius) }}</em></div></article>
            <article><span class="metric-icon"><Network :size="17" /></span><div><small>网络吞吐</small><strong>↓ {{ formatRate(overview.system.networkReceiveBytesPerSecond) }}</strong><em>↑ {{ formatRate(overview.system.networkTransmitBytesPerSecond) }}</em></div></article>
          </div>
        </section>

        <div class="detail-grid">
          <section class="power-panel panel" aria-labelledby="power-title">
            <div class="panel-heading"><div><p class="eyebrow">供电与估算</p><h2 id="power-title">功耗</h2></div><span class="source-label" :class="{ live: overview.power.live }">{{ powerSourceLabel(overview.power.source) }}</span></div>
            <div class="power-reading">
              <div><strong>{{ overview.power.watts?.toFixed(2) ?? emptyValue() }}</strong><span>W</span></div>
              <p>{{ overview.power.explanation }}</p>
            </div>
            <dl class="power-facts">
              <div><dt><BatteryCharging :size="15" />供电</dt><dd>{{ overview.power.acOnline === null ? '未知' : overview.power.acOnline ? '交流供电' : '电池供电' }}</dd></div>
              <div><dt>电池电量</dt><dd>{{ overview.power.batteryPercent ?? emptyValue() }}%</dd></div>
              <div><dt>电池健康度</dt><dd>{{ overview.power.batteryHealthPercent ?? emptyValue() }}%</dd></div>
              <div><dt>30 天估算</dt><dd>{{ overview.power.monthlyKwh?.toFixed(2) ?? emptyValue() }} kWh</dd></div>
              <div><dt>年度估算</dt><dd>{{ overview.power.yearlyKwh?.toFixed(1) ?? emptyValue() }} kWh</dd></div>
            </dl>
          </section>

          <aside class="attention-panel panel" aria-labelledby="attention-title">
            <div class="panel-heading"><div><p class="eyebrow">告警</p><h2 id="attention-title">需要留意</h2></div><Activity :size="19" aria-hidden="true" /></div>
            <div v-if="overview.alerts.length" class="alerts-list">
              <article v-for="alert in overview.alerts" :key="`${alert.name}-${alert.activeAt}`" :class="`alert-${alert.severity}`"><strong>{{ alert.summary }}</strong><span>{{ alert.name }}，{{ formatRelative(alert.activeAt) }}</span></article>
            </div>
            <div v-else class="quiet-state"><CircleCheck :size="23" aria-hidden="true" /><strong>没有活动告警</strong><span>Prometheus 当前没有 firing 规则。</span></div>
            <div class="safety-note"><ShieldCheck :size="17" /><p><strong>只读边界</strong><span>不会执行命令、自动重启或切断电源。</span></p></div>
          </aside>
        </div>

        <section id="history" class="trend-panel panel" aria-labelledby="history-title">
          <div class="panel-heading trend-heading">
            <div><p class="eyebrow">真实采样</p><h2 id="history-title">运行趋势</h2></div>
            <div class="range-control" aria-label="历史时间范围">
              <button v-for="range in (['6h', '24h', '7d'] as HistoryRange[])" :key="range" type="button" :class="{ active: historyRange === range }" :disabled="historyLoading" @click="selectHistoryRange(range)">{{ range }}</button>
            </div>
          </div>
          <div v-if="history" class="trend-list" :class="{ dimmed: historyLoading }">
            <SparklineChart v-for="(series, index) in history.series" :key="series.key" :points="series.points" :label="series.label" :unit="series.unit" :range-label="history.range" :color="['#35c9a5', '#86cdb9', '#b8cbc5'][index]" />
          </div>
          <div v-else class="history-empty"><span class="loading-block" /><p>历史趋势暂时不可用，当前读数仍可查看。</p></div>
          <p v-if="historyError" class="history-error" role="status">{{ historyError }}</p>
        </section>

        <section class="evidence-note" aria-labelledby="notes-title">
          <div><p class="eyebrow">数据边界</p><h2 id="notes-title">测量说明</h2></div>
          <ul><li v-for="item in overview.limitations" :key="item">{{ item }}</li></ul>
        </section>
      </template>
    </main>

    <footer><span>HomeOps</span><span>只读家庭服务器状态页</span></footer>
  </div>
</template>
