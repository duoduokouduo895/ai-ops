<script setup lang="ts">
import { computed } from 'vue'
import { statusTopic } from '../glossary/ids'
import { statusTone } from '../glossary/statusTone'
import type { StatusDomain } from '../glossary/types'
import { useHintStore } from '../stores/hint'

const props = defineProps<{ value: string; domain: StatusDomain; explain?: boolean }>()
const hint = useHintStore()
const tone = computed(() => statusTone(props.domain, props.value))
const topic = computed(() => statusTopic(props.domain, props.value))
const described = computed(() => hint.active?.topicId === topic.value)

function trigger(event: Event) {
  return event.currentTarget
}

function onEnter(event: Event) {
  hint.scheduleShow(topic.value, 'tooltip', trigger(event), 250)
}

function onFocus(event: Event) {
  hint.scheduleShow(topic.value, 'tooltip', trigger(event), 0)
}

function onLeave(event: Event) {
  hint.scheduleHide()
  hint.release(trigger(event))
}

function onClick(event: MouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  hint.pin(topic.value, 'tooltip', trigger(event))
}
</script>

<template>
  <button
    class="status"
    type="button"
    :data-tone="tone"
    :aria-label="`查看 ${value} 的含义`"
    :aria-describedby="described ? 'aidiag-hint' : undefined"
    :aria-expanded="described && hint.active?.pinned ? 'true' : 'false'"
    @mouseenter="onEnter"
    @mouseleave="onLeave"
    @focus="onFocus"
    @blur="hint.scheduleHide()"
    @click="onClick"
    @keydown.enter.prevent="onClick($event as unknown as MouseEvent)"
    @keydown.space.prevent="onClick($event as unknown as MouseEvent)"
  >
    <span class="badge">
      <i aria-hidden="true" />
      <span>{{ value }}</span>
    </span>
  </button>
</template>

<style scoped>
.status {
  display: inline-flex;
  max-width: 100%;
  min-height: 32px;
  align-items: center;
  margin: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  padding: 4px 6px;
  color: inherit;
  cursor: help;
}
.status:hover .badge { color: var(--text); }
.status:focus-visible { box-shadow: var(--focus-ring); }
.badge { display: inline-flex; max-width: 100%; align-items: center; gap: 7px; color: var(--text-soft); font: 600 12px/1.25 var(--font-mono); letter-spacing: .025em; }
.badge i { width: 7px; height: 7px; flex: 0 0 auto; border: 1px solid currentColor; border-radius: 50%; background: transparent; }
.badge span { min-width: 0; overflow-wrap: anywhere; }
.status[data-tone="success"] .badge { color: var(--success); }
.status[data-tone="success"] i { background: var(--success); }
.status[data-tone="warning"] .badge { color: var(--warning); }
.status[data-tone="warning"] i { background: var(--warning); }
.status[data-tone="critical"] .badge { color: var(--critical); }
.status[data-tone="critical"] i { background: var(--critical); }
.status[data-tone="unknown"] .badge { color: var(--unknown); }
.status[data-tone="unknown"] i { border-style: dashed; }
@media (pointer: coarse) {
  .status { min-height: 44px; }
}
</style>
