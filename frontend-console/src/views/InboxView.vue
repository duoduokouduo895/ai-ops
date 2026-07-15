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
const format = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN',{dateStyle:'short',timeStyle:'medium',hour12:false}).format(new Date(value)) : '尚无数据'
async function load(){loading.value=true;failed.value=false;try{const [a,b]=await Promise.all([consoleApi.overview(),consoleApi.incidents({limit:8})]);overview.value=a.data;incidents.value=b.data.items}catch{failed.value=true}finally{loading.value=false}}
onMounted(load)
</script>
<template><AppShell section="inbox"><header><p class="eyebrow">Operations inbox · UTC evidence</p><h1 class="page-title">今天，系统需要你关注什么？</h1></header>
  <StatePanel v-if="failed" title="控制面暂时不可达" detail="前端仍可加载，但 GCP API、TLS、Nginx 或 AIDiag 本身可能不可用。请从外部探针继续确认。" action="重新检查" @action="load" />
  <template v-else>
    <section class="metrics" aria-label="关键状态">
      <article><span>待处理</span><strong>{{ overview?.activeIncidents ?? '—' }}</strong><small>active incidents</small></article>
      <article><span>观察中</span><strong>{{ overview?.pendingRuns ?? '—' }}</strong><small>diagnosis runs</small></article>
      <article><span>最近恢复</span><strong>{{ overview?.recoveredLast24Hours ?? '—' }}</strong><small>last 24 hours</small></article>
      <article class="fresh"><span>证据新鲜度</span><strong class="mono">{{ format(overview?.lastEvidenceAt ?? null) }}</strong><small>latest redacted evidence</small></article>
    </section>
    <section class="queue panel"><div class="section-head"><div><p class="eyebrow">Priority queue</p><h2>最近 Incident</h2></div><RouterLink :to="{name:'incidents'}">查看全部 →</RouterLink></div>
      <div v-if="loading" class="skeleton" style="height:220px" />
      <StatePanel v-else-if="!incidents.length" title="当前没有 Incident" detail="采集链路仍在运行；没有告警不代表平台没有工作。" />
      <RouterLink v-for="item in incidents" v-else :key="item.id" class="incident-row" :to="{name:'casefile',params:{id:item.id}}">
        <time class="mono">{{ format(item.lastAlertAt) }}</time><div><strong>{{ item.title }}</strong><small>{{ item.primaryService }} · {{ item.alertName }}</small></div><StatusBadge :value="item.status"/><span class="mono">{{ item.evidenceCount }} evidence</span><b>↗</b>
      </RouterLink>
    </section>
  </template>
</AppShell></template>
<style scoped>
header{margin-bottom:26px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(140px,1fr)) minmax(250px,1.6fr);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;margin-bottom:20px}.metrics article{min-height:126px;background:var(--surface-1);padding:20px 22px;border-right:1px solid var(--line);display:grid;align-content:space-between}.metrics article:last-child{border:0}.metrics span,.metrics small{color:var(--text-muted);font-size:11px}.metrics strong{font:500 34px/1 var(--font-mono);letter-spacing:-.05em}.metrics .fresh strong{font-size:15px;letter-spacing:0}.queue{padding:20px}.section-head{display:flex;align-items:end;justify-content:space-between;margin-bottom:12px}.section-head h2{margin:2px 0;font-size:20px}.section-head a{color:var(--text-soft);font-size:12px}.incident-row{display:grid;grid-template-columns:170px minmax(220px,1fr) 120px 110px 20px;gap:20px;align-items:center;border-top:1px solid var(--line-soft);padding:17px 6px;transition:background 150ms var(--ease)}.incident-row:hover{background:var(--surface-2)}.incident-row time,.incident-row>span{color:var(--text-muted);font-size:11px}.incident-row strong{display:block;font-size:14px}.incident-row small{display:block;color:var(--text-muted);margin-top:3px}.incident-row b{color:var(--accent)}
@media(max-width:950px){.metrics{grid-template-columns:repeat(2,1fr)}.metrics article{border-bottom:1px solid var(--line)}.incident-row{grid-template-columns:1fr auto}.incident-row time,.incident-row>span{display:none}}@media(max-width:560px){.metrics{grid-template-columns:1fr 1fr}.metrics .fresh{grid-column:1/-1}.metrics article{padding:15px;min-height:105px}.metrics strong{font-size:27px}}
</style>
