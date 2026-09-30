<script setup lang="ts">
import { CircleAlert, CircleCheck, CircleHelp, CircleX } from '@lucide/vue'
import type { HealthState, ServiceState } from '../../shared/homeops'

const props = defineProps<{ state: HealthState | ServiceState; compact?: boolean }>()
const labels = { healthy: '正常', attention: '留意', critical: '严重', unknown: '未知', up: '在线', down: '离线' }
</script>

<template>
  <span class="status-pill" :class="[`is-${props.state}`, { compact }]">
    <CircleCheck v-if="props.state === 'healthy' || props.state === 'up'" :size="14" aria-hidden="true" />
    <CircleAlert v-else-if="props.state === 'attention'" :size="14" aria-hidden="true" />
    <CircleX v-else-if="props.state === 'critical' || props.state === 'down'" :size="14" aria-hidden="true" />
    <CircleHelp v-else :size="14" aria-hidden="true" />
    {{ labels[props.state] }}
  </span>
</template>
