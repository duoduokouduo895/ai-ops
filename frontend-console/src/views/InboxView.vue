<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import HintTerm from '../components/HintTerm.vue'
import IncidentRecord from '../components/IncidentRecord.vue'
import PageHeader from '../components/PageHeader.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { IncidentSummary, Overview, PipelineHealthView } from '../api/types'
import { isUnauthorized } from '../utils/http'
import { formatLocal } from '../utils/time'

const overview = ref<Overview | null>(null)
const pipeline = ref<PipelineHealthView | null>(null)
const pipelineUnavailable = ref(false)
const incidents = ref<IncidentSummary[]>([])
const loading = ref(true)
const failed = ref(false)
const readAt = ref<Date | null>(null)

const knownPipeline = computed(() => {
  const status = pipeline.value?.pipelineStatus
  return status === 'IDLE' || status === 'IN_PROGRESS' || status === 'BACKLOG'
    || status === 'QUERY_FAILED' || status === 'NOT_SAMPLED' || status === 'SNAPSHOT_EXPIRED'
})

async function load() {
  loading.value = true
  failed.value = false
  pipelineUnavailable.value = false
  try {
    const [a, b, health] = await Promise.all([
      consoleApi.overview(),
      consoleApi.incidents({ limit: 8 }),
      consoleApi.pipelineHealth().catch((error) => {
        if (isUnauthorized(error)) throw error
        pipelineUnavailable.value = true
        return null
      }),
    ])
    overview.value = a.data
    incidents.value = b.data.items
    pipeline.value = health && 'data' in health ? health.data : null
    readAt.value = new Date()
  } catch (error) {
    if (isUnauthorized(error)) return
    failed.value = true
  } finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <AppShell section="inbox">
    <PageHeader title="Inbox" topic="page.inbox">
      <template #actions>
        <p v-if="readAt" class="read-at mono"><HintTerm topic="term.read-at">本次读取</HintTerm> {{ formatLocal(readAt) }}</p>
        <button class="action-button" type="button" :disabled="loading" @click="load">刷新</button>
      </template>
    </PageHeader>
    <StatePanel v-if="failed" kind="error" title="控制面暂时不可达" detail="无法读取 ops-api。空结果不会被显示成全部健康。" action="重新检查" @action="load" />
    <template v-else>
      <section class="summary" aria-label="当前摘要">
        <div v-if="loading" class="summary-skeleton" role="status" aria-label="正在读取摘要"><span class="skeleton"/><span class="skeleton"/><span class="skeleton"/></div>
        <dl v-else>
          <div><dt><HintTerm topic="term.active-incidents">Active incidents</HintTerm></dt><dd class="mono">{{ overview?.activeIncidents ?? '—' }}</dd></div>
          <div><dt><HintTerm topic="term.pending-runs">Pending runs</HintTerm></dt><dd class="mono">{{ overview?.pendingRuns ?? '—' }}</dd></div>
          <div><dt><HintTerm topic="term.recovered-24h">Resolved · 24h</HintTerm></dt><dd class="mono">{{ overview?.recoveredLast24Hours ?? '—' }}</dd></div>
        </dl>
        <p v-if="!loading" class="fresh">
          <HintTerm topic="term.last-evidence-at">最近事故证据</HintTerm>
          {{ formatLocal(overview?.lastEvidenceAt ?? null) }}
        </p>
        <div v-if="!loading" class="pipeline">
          <template v-if="pipelineUnavailable || !pipeline">
            <span>处理链路 暂无信息</span>
          </template>
          <template v-else>
            <span>处理链路</span>
            <StatusBadge v-if="knownPipeline" domain="pipeline" :value="String(pipeline.pipelineStatus)" />
            <span v-else class="mono">{{ pipeline.pipelineStatus }}</span>
            <span class="mono"><HintTerm topic="term.pending-count">待办</HintTerm> {{ pipeline.pendingRuns ?? '—' }}</span>
          </template>
        </div>
      </section>
      <section class="queue" aria-labelledby="recent-incidents">
        <div class="section-head">
          <h2 id="recent-incidents" class="section-title">Recent incidents</h2>
          <RouterLink :to="{ name: 'incidents' }">查看全部<span aria-hidden="true"> →</span></RouterLink>
        </div>
        <div v-if="loading" class="queue-skeleton"><span class="skeleton"/><span class="skeleton"/><span class="skeleton"/></div>
        <StatePanel v-else-if="!incidents.length" title="当前查询未返回事件" detail="这只说明本次请求没有事件记录，不能据此宣称采集链正常或全部服务健康。" />
        <div v-else class="incident-list">
          <IncidentRecord v-for="item in incidents" :key="item.id" :item="item" />
        </div>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
.summary { margin-bottom: 28px; border-block: 1px solid var(--line); padding: 14px 0 16px; }
.summary dl, .summary-skeleton { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px 24px; margin: 0; }
dt { color: var(--text-muted); font-size: 13px; }
dd { margin: 6px 0 0; font-size: 18px; font-weight: 600; letter-spacing: -.03em; }
.fresh, .read-at, .pipeline { margin: 12px 0 0; color: var(--text-muted); font-size: 13px; }
.fresh, .pipeline { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
.read-at { margin: 0; }
.summary-skeleton span { height: 52px; }
.section-head { display: flex; align-items: end; justify-content: space-between; gap: 16px; padding-bottom: 8px; }
.section-head a { display: inline-flex; min-height: 44px; align-items: center; color: var(--accent-strong); font-size: 13px; }
.incident-list { border-bottom: 1px solid var(--line); }
.queue-skeleton { display: grid; gap: 1px; border-top: 1px solid var(--line-soft); }
.queue-skeleton span { height: 56px; border-radius: 0; }
@media (max-width: 640px) {
  .summary dl, .summary-skeleton { grid-template-columns: 1fr; }
}
</style>
