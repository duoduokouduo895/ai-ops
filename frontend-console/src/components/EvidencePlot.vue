<script setup lang="ts">
import { computed } from 'vue'
import type { Evidence } from '../api/types'
const props=defineProps<{items:Evidence[];selected?:string}>()
const emit=defineEmits<{select:[id:string]}>()
const points=computed(()=>props.items.slice(0,60).map((item,index)=>({item,x:props.items.length<=1?50:4+(index/(Math.min(props.items.length,60)-1))*92,y:88-Math.max(0,Math.min(100,item.importance))*.7})))
const path=computed(()=>points.value.map((p,i)=>`${i?'L':'M'} ${p.x} ${p.y}`).join(' '))
</script>
<template><div class="plot" role="img" aria-label="按采集顺序展示证据重要度"><svg viewBox="0 0 100 100" preserveAspectRatio="none"><path class="grid" d="M0 25H100M0 50H100M0 75H100"/><path v-if="points.length>1" class="line" :d="path"/><circle v-for="p in points" :key="p.item.id" :cx="p.x" :cy="p.y" r="1.8" :class="{selected:p.item.id===selected}" tabindex="0" @click="emit('select',p.item.id)" @keydown.enter="emit('select',p.item.id)"/></svg><span>importance / collection order</span></div></template>
<style scoped>.plot{height:205px;position:relative;border-bottom:1px solid var(--line-soft)}svg{width:100%;height:180px;overflow:visible}.grid{stroke:var(--line-soft);stroke-width:.35}.line{fill:none;stroke:var(--accent);stroke-width:1.2;vector-effect:non-scaling-stroke}.plot circle{fill:var(--surface-1);stroke:var(--accent);stroke-width:1;cursor:pointer;vector-effect:non-scaling-stroke}.plot circle.selected{fill:var(--accent);stroke:var(--text);r:2.8}.plot span{position:absolute;left:0;bottom:6px;color:var(--text-muted);font:9px var(--font-mono)}</style>
