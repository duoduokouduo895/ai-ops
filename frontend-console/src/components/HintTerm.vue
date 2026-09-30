<script setup lang="ts">
import { computed } from 'vue'
import { useHintStore } from '../stores/hint'

const props = defineProps<{ topic: string; name?: string }>()
const hint = useHintStore()
const described = computed(() => hint.active?.topicId === props.topic)

function trigger(event: Event) {
  return event.currentTarget
}

function onLeave(event: Event) {
  hint.scheduleHide()
  hint.release(trigger(event))
}

function onClick(event: MouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  hint.pin(props.topic, 'panel', trigger(event))
}
</script>

<template>
  <button
    class="hint-term"
    type="button"
    :aria-label="name || `查看 ${topic} 的含义`"
    :aria-describedby="described ? 'aidiag-hint' : undefined"
    :aria-expanded="described && hint.active?.pinned ? 'true' : 'false'"
    @mouseenter="hint.scheduleShow(topic, 'tooltip', trigger($event), 250)"
    @mouseleave="onLeave"
    @focus="hint.scheduleShow(topic, 'tooltip', trigger($event), 0)"
    @blur="hint.scheduleHide()"
    @click="onClick"
  >
    <slot />
  </button>
</template>

<style scoped>
.hint-term {
  display: inline;
  margin: 0;
  border: 0;
  border-bottom: 1px dashed color-mix(in oklch, currentColor 55%, transparent);
  background: transparent;
  padding: 0;
  color: inherit;
  cursor: help;
  font: inherit;
  text-align: inherit;
}
.hint-term:hover, .hint-term:focus-visible { color: var(--text); }
.hint-term:focus-visible { box-shadow: var(--focus-ring); border-radius: 2px; }
</style>
