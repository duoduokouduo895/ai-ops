<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { hintText, lookup } from '../glossary'
import { useExplainStore } from '../stores/explain'
import { useHintStore } from '../stores/hint'

const hint = useHintStore()
const explain = useExplainStore()
const route = useRoute()
const box = ref<HTMLElement | null>(null)
const style = ref({ top: '0px', left: '0px' })
const entry = computed(() => hint.active ? lookup(hint.active.topicId) : null)
const title = computed(() => entry.value ? `${entry.value.term} · ${entry.value.zh}` : '')
const body = computed(() => {
  if (!entry.value || !hint.active) return []
  if (hint.active.kind === 'panel') return [entry.value.what, entry.value.here].filter(Boolean).slice(0, 3)
  return [hintText(entry.value)]
})

function place() {
  const trigger = hint.active?.trigger
  const el = box.value
  if (!trigger || !el) return
  const rect = trigger.getBoundingClientRect()
  const width = el.offsetWidth
  const height = el.offsetHeight
  let left = rect.left
  let top = rect.bottom - 4
  if (left + width > window.innerWidth - 8) left = window.innerWidth - width - 8
  if (left < 8) left = 8
  if (top + height > window.innerHeight - 8) top = Math.max(-4, rect.top - height + 4)
  style.value = { top: `${Math.round(top)}px`, left: `${Math.round(left)}px` }
}

watch(() => hint.active, async () => {
  await nextTick()
  place()
})

watch(() => route.fullPath, () => hint.close())
watch(() => explain.open, (open) => { if (open) hint.close() })

function onKey(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !hint.active) return
  const trigger = hint.active.trigger
  const restore = document.activeElement === trigger || Boolean(box.value?.contains(document.activeElement))
  hint.close()
  if (restore) trigger.focus()
  event.preventDefault()
  event.stopPropagation()
}

function onPointerDown(event: PointerEvent) {
  if (!hint.active) return
  const target = event.target
  if (!(target instanceof Node)) return
  if (box.value?.contains(target) || hint.active.trigger.contains(target)) return
  hint.close()
}

onMounted(() => {
  window.addEventListener('keydown', onKey, true)
  window.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('resize', place)
  window.addEventListener('scroll', place, true)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
  window.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', place, true)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="hint.active && entry"
      id="aidiag-hint"
      ref="box"
      class="hint"
      :class="hint.active.kind"
      :role="hint.active.kind === 'panel' ? 'dialog' : 'tooltip'"
      :aria-modal="hint.active.kind === 'panel' ? 'false' : undefined"
      :aria-label="title"
      :style="style"
      @mouseenter="hint.cancelHide()"
      @mouseleave="hint.scheduleHide()"
    >
      <div class="hint-card">
        <strong>{{ title }}</strong>
        <p v-for="(paragraph, index) in body" :key="index">{{ paragraph }}</p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.hint {
  position: fixed;
  z-index: 40;
  width: min(320px, calc(100vw - 16px));
  padding: 12px 0;
  overflow: visible;
  pointer-events: auto;
}
.hint-card {
  max-height: min(336px, calc(100vh - 40px));
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  padding: 12px 14px;
  color: var(--text);
  user-select: text;
}
.hint strong {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.hint p {
  margin: 0 0 8px;
  color: var(--text);
  font-size: 16px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.hint p:last-child { margin-bottom: 0; }
</style>
