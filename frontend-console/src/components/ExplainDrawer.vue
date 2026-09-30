<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import 'element-plus/es/components/drawer/style/css'
import { lookup, pageIntro } from '../glossary'
import { useExplainStore } from '../stores/explain'

const explain = useExplainStore()
const current = computed(() => lookup(explain.topicId))
const paragraphs = computed(() => pageIntro(current.value))
const body = ref<HTMLElement | null>(null)
const compact = ref(false)
const media = window.matchMedia('(max-width: 720px)')

function syncSize() { compact.value = media.matches }

watch(() => [explain.open, explain.topicId], async ([open]) => {
  if (!open) return
  await nextTick()
  body.value?.scrollTo({ top: 0 })
})

onBeforeUnmount(() => media.removeEventListener('change', syncSize))
syncSize()
media.addEventListener('change', syncSize)
</script>

<template>
  <ElDrawer
    class="explain-drawer"
    :model-value="explain.open"
    :size="compact ? '100%' : '420px'"
    direction="rtl"
    :with-header="true"
    destroy-on-close
    @update:model-value="explain.open = $event"
    @closed="explain.restoreFocus()"
  >
    <template #header>
      <div class="drawer-head">
        <h2>{{ current.term }} · 页面说明</h2>
        <p>{{ current.zh }}</p>
      </div>
    </template>
    <div ref="body" class="drawer-body">
      <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
    </div>
  </ElDrawer>
</template>

<style scoped>
.drawer-head h2 { margin: 0; font-size: 18px; letter-spacing: -.02em; overflow-wrap: anywhere; }
.drawer-head p { margin: 6px 0 0; color: var(--text-muted); font-size: 13px; overflow-wrap: anywhere; }
.drawer-body { display: grid; gap: 14px; padding-bottom: 24px; }
.drawer-body p { margin: 0; color: var(--text); font-size: 16px; line-height: 1.6; overflow-wrap: anywhere; }
</style>
