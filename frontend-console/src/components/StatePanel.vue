<script setup lang="ts">
withDefaults(defineProps<{ title: string; detail?: string; action?: string; kind?: 'loading' | 'empty' | 'error' | 'success' }>(), { kind: 'empty' })
defineEmits<{ action: [] }>()
</script>

<template>
  <section class="state" :data-kind="kind" :aria-live="kind === 'loading' ? 'polite' : undefined">
    <span class="state-mark" aria-hidden="true"><i /><i /><i /></span>
    <div><h2>{{ title }}</h2><p v-if="detail">{{ detail }}</p></div>
    <button v-if="action" class="action-button" type="button" @click="$emit('action')">{{ action }}</button>
  </section>
</template>

<style scoped>
.state { min-height: 240px; display: grid; place-items: center; align-content: center; justify-items: center; gap: 14px; border-block: 1px solid var(--line); padding: 32px 20px; text-align: center; }
.state-mark { display: inline-flex; align-items: end; gap: 4px; height: 24px; color: var(--unknown); }
.state-mark i { width: 4px; border-radius: 99px; background: currentColor; }
.state-mark i:nth-child(1) { height: 7px; }.state-mark i:nth-child(2) { height: 15px; }.state-mark i:nth-child(3) { height: 10px; }
h2 { margin: 0; font-size: 16px; line-height: 1.3; }
p { max-width: 52ch; margin: 7px 0 0; color: var(--text-muted); }
.state[data-kind="error"] .state-mark { color: var(--critical); }
.state[data-kind="success"] .state-mark { color: var(--success); }
.state[data-kind="loading"] .state-mark { color: var(--accent); }
.state[data-kind="loading"] .state-mark i { animation: status-pulse 1.1s var(--ease) infinite alternate; }
.state[data-kind="loading"] .state-mark i:nth-child(2) { animation-delay: 90ms; }.state[data-kind="loading"] .state-mark i:nth-child(3) { animation-delay: 180ms; }
@keyframes status-pulse { to { opacity: .45; transform: translateY(-2px); } }
</style>
