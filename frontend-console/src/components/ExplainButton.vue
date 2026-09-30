<script setup lang="ts">
import { computed } from 'vue'
import { useExplainStore } from '../stores/explain'
import { useHintStore } from '../stores/hint'

const props = withDefaults(defineProps<{ topic: string; label?: string; name?: string; mode?: 'page' | 'panel' }>(), {
  mode: 'panel',
})
const explain = useExplainStore()
const hint = useHintStore()
const expanded = computed(() => props.mode === 'panel' && hint.active?.topicId === props.topic && hint.active?.pinned)

function open(event: Event) {
  event.preventDefault()
  event.stopPropagation()
  const target = event.currentTarget
  if (props.mode === 'page') {
    hint.close()
    explain.show(props.topic, event)
    return
  }
  hint.pin(props.topic, 'panel', target)
}
</script>

<template>
  <button
    class="explain"
    type="button"
    :class="{ labeled: label }"
    :aria-label="name || (label ? `查看${label}` : `查看 ${topic} 的含义`)"
    :aria-expanded="mode === 'panel' ? (expanded ? 'true' : 'false') : undefined"
    :aria-controls="expanded ? 'aidiag-hint' : undefined"
    @click="open"
  >
    <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
      <circle cx="10" cy="10" r="7.25" fill="none" stroke="currentColor" stroke-width="1.4"/>
      <circle cx="10" cy="6.4" r="1" fill="currentColor"/>
      <path d="M10 9.1v5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
    </svg>
    <span v-if="label">{{ label }}</span>
  </button>
</template>

<style scoped>
.explain {
  display: inline-flex;
  width: auto;
  flex: 0 0 auto;
  min-width: 44px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  background: transparent;
  padding: 0 6px;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}
.explain:hover, .explain:focus-visible { color: var(--accent-strong); }
.explain.labeled { color: var(--text-soft); }
</style>
