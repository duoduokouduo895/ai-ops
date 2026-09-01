<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { IncidentSummary, Overview } from '../api/types'

const overview = ref<Overview | null>(null)
const incidents = ref<IncidentSummary[]>([])
const loading = ref(true)
const failed = ref(false)
const format = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(new Date(value)) : '尚无数据'
async function load() { loading.value = true; failed.value = false; try { const [a, b] = await Promise.all([consoleApi.overview(), consoleApi.incidents({ limit: 8 })]); overview.value = a.data; incidents.value = b.data.items } catch { failed.value = true } finally { loading.value = false } }
onMounted(load)
</script>

<template>
  <AppShell section="inbox">
    <header class="page-head"><h1 class="page-title">今天，系统需要你关注什么？</h1></header>
    <StatePanel v-if="failed" kind="error" title="控制面暂时不可达" detail="前端仍可加载，但 GCP API、TLS、Nginx 或 AIDiag 本身可能不可用。请从外部探针继续确认。" action="重新检查" @action="load" />
    <template v-else>
      <section class="attention" aria-label="当前注意力摘要">
        <div class="attention-heading"><h2 class="section-title">当前注意力</h2></div>
        <dl><div><dt>待处理</dt><dd>{{ overview?.activeIncidents ?? '—' }}</dd></div><div><dt>观察中</dt><dd>{{ overview?.pendingRuns ?? '—' }}</dd></div><div><dt>最近恢复</dt><dd>{{ overview?.recoveredLast24Hours ?? '—' }}</dd></div><div class="fresh"><dt>证据更新</dt><dd class="mono">{{ format(overview?.lastEvidenceAt ?? null) }}</dd></div></dl>
      </section>
      <section class="queue" aria-labelledby="recent-incidents"><div class="section-head"><div><h2 id="recent-incidents" class="section-title">最近 Incident</h2></div><RouterLink :to="{ name: 'incidents' }">查看全部<span aria-hidden="true"> →</span></RouterLink></div>
        <div v-if="loading" class="queue-skeleton"><span class="skeleton"/><span class="skeleton"/><span class="skeleton"/></div>
        <StatePanel v-else-if="!incidents.length" title="当前没有 Incident" detail="采集链路仍在运行；没有告警不代表平台没有工作。" />
        <TransitionGroup v-else name="record" tag="div" class="incident-list">
          <RouterLink v-for="item in incidents" :key="item.id" class="incident-row" :to="{ name: 'casefile', params: { id: item.id } }"><time class="mono">{{ format(item.lastAlertAt) }}</time><div class="incident-summary"><strong>{{ item.title }}</strong><small>{{ item.primaryService }}</small></div><StatusBadge :value="item.status"/><span class="evidence-count mono" :aria-label="`${item.evidenceCount} evidence`">{{ item.evidenceCount }}</span></RouterLink>
        </TransitionGroup>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
.page-head { margin-bottom: 26px; }.attention { margin-bottom: 28px; border-block: 1px solid var(--line); }.attention-heading { padding: 12px 0; }.attention dl { display: grid; grid-template-columns: repeat(3, minmax(130px, .72fr)) minmax(260px, 1.6fr); margin: 0; }.attention dl > div { min-height: 88px; border-right: 1px solid var(--line-soft); padding: 14px 20px 16px 0; }.attention dl > div + div { padding-left: 20px; }.attention dl > div:last-child { border: 0; }.attention dt { display: block; color: var(--text-muted); font-size: 10px; }.attention dd { margin: 11px 0 0; font: 540 28px/1 var(--font-mono); letter-spacing: -.05em; }.attention .fresh dd { font-size: 13px; letter-spacing: 0; }.queue { border-top: 1px solid var(--line); }.section-head { display: flex; align-items: end; justify-content: space-between; gap: 20px; padding: 16px 0 10px; }.section-head a { color: var(--accent-strong); font-size: 12px; }.incident-list { border-bottom: 1px solid var(--line); }.incident-row { display: grid; grid-template-columns: 170px minmax(220px, 1fr) 120px 36px; gap: 20px; align-items: center; border-top: 1px solid var(--line-soft); padding: 15px 12px; transition: background var(--duration-fast) var(--ease); }.incident-row:hover { background: var(--surface-1); }.incident-row time, .incident-row small, .evidence-count { color: var(--text-muted); font-size: 10px; }.incident-summary strong { display: block; font-size: 14px; }.incident-summary small { display: block; margin-top: 4px; }.queue-skeleton { display: grid; gap: 1px; border-top: 1px solid var(--line-soft); padding: 0; }.queue-skeleton span { height: 56px; border-radius: 0; }
@media (max-width: 980px) { .attention dl { grid-template-columns: repeat(2, 1fr); }.attention dl > div:nth-child(2) { border-right: 0; }.attention dl > div:nth-child(-n+2) { border-bottom: 1px solid var(--line-soft); }.incident-row { grid-template-columns: 1fr auto; }.incident-row time, .evidence-count { display: none; } }
@media (max-width: 560px) { .page-head { margin-bottom: 26px; }.attention dl > div { min-height: 96px; padding: 14px 12px 16px 0; }.attention dl > div + div { padding-left: 12px; }.attention dd { font-size: 25px; }.attention .fresh { grid-column: 1 / -1; border-right: 0; }.section-head { align-items: start; }.incident-row { gap: 12px; padding-inline: 0; } }
</style>
