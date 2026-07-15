<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { CatalogView } from '../api/types'
const catalog=ref<CatalogView|null>(null);const failed=ref(false);const loading=ref(true)
async function load(){loading.value=true;failed.value=false;try{catalog.value=(await consoleApi.services()).data}catch{failed.value=true}finally{loading.value=false}}onMounted(load)
</script>
<template><AppShell section="services"><header><p class="eyebrow">Compiled service catalog</p><h1 class="page-title">Services</h1><p>只读展示 Git 版本化配置与固定探针结果；网页不允许动态编辑查询或地址。</p></header>
<StatePanel v-if="failed" title="无法读取 Service Catalog" detail="控制面可能离线，已加载的前端不会把 UNKNOWN 误报为 DOWN。" action="重新检查" @action="load"/>
<div v-else-if="loading" class="skeleton" style="height:360px"/>
<section v-else class="service-list"><article v-for="service in catalog?.services" :key="service.id" class="service panel"><div class="service-head"><div><p class="eyebrow mono">{{service.otelServiceName}}</p><h2>{{service.displayName}}</h2></div><StatusBadge :value="service.status"/></div><dl><div><dt>Environment</dt><dd>{{service.environment}}</dd></div><div><dt>Diagnosis profile</dt><dd class="mono">{{service.diagnosisProfile}}</dd></div><div><dt>Application</dt><dd><StatusBadge :value="service.applicationStatus"/></dd></div></dl><div class="probes"><div v-for="probe in service.dependencies" :key="probe.id"><span>{{probe.id}}</span><small>{{probe.kind}}</small><StatusBadge :value="probe.status"/></div></div><a v-if="service.grafanaUrl" :href="service.grafanaUrl" target="_blank" rel="noreferrer">在 Grafana 深入查看 ↗</a></article></section>
<footer v-if="catalog" class="catalog-meta mono">schema v{{catalog.version}} · compiled {{new Date(catalog.compiledAt).toLocaleString('zh-CN')}} · sha256 {{catalog.sourceDigest.slice(0,12)}}…</footer>
</AppShell></template>
<style scoped>header>p:last-child{color:var(--text-muted);margin-top:-10px}.service-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.service{padding:22px}.service-head{display:flex;justify-content:space-between;align-items:start}.service h2{margin:3px 0 20px;font-size:23px}.service dl{display:grid;grid-template-columns:repeat(3,1fr);border-block:1px solid var(--line-soft);margin:0;padding:15px 0}.service dl div{padding:0 12px;border-right:1px solid var(--line-soft)}.service dl div:first-child{padding-left:0}.service dl div:last-child{border:0}.service dt{color:var(--text-muted);font-size:10px;text-transform:uppercase;letter-spacing:.08em}.service dd{margin:6px 0 0}.probes{margin:16px 0}.probes>div{display:grid;grid-template-columns:1fr 70px auto;gap:12px;padding:10px 0;border-bottom:1px solid var(--line-soft)}.probes small{color:var(--text-muted)}.service>a{display:inline-block;color:oklch(.78 .1 288);font-size:12px;margin-top:8px}.catalog-meta{margin-top:18px;color:var(--text-muted);font-size:10px}@media(max-width:950px){.service-list{grid-template-columns:1fr}}@media(max-width:520px){.service dl{grid-template-columns:1fr;gap:12px}.service dl div{border:0;padding:0}}</style>
