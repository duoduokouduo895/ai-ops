<script setup lang="ts">
import { ref } from 'vue'
import ExplainButton from './ExplainButton.vue'

const props = withDefaults(defineProps<{ title: string; summary?: string; defaultOpen?: boolean; topic?: string }>(), { defaultOpen: false })
const opened = ref(props.defaultOpen)

function expand() { opened.value = true }
function toggle() { opened.value = !opened.value }

defineExpose({ expand, opened })
</script>

<template>
  <section class="disclosure">
    <div class="head">
      <button type="button" class="toggle" :aria-expanded="opened" @click="toggle">
        <span class="copy">
          <h2 class="section-title">{{ title }}</h2>
          <small v-if="summary">{{ summary }}</small>
        </span>
        <b aria-hidden="true">{{ opened ? '收起' : '展开' }}</b>
      </button>
      <ExplainButton v-if="topic" :topic="topic" :name="`查看 ${title} 的含义`" />
    </div>
    <div v-show="opened" class="body"><slot /></div>
  </section>
</template>

<style scoped>
.disclosure { border-top: 1px solid var(--line); }
.disclosure:last-child { border-bottom: 1px solid var(--line); }
.head { display: flex; align-items: center; gap: 4px; }
.toggle {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 0;
  background: transparent;
  padding: 10px 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.copy { min-width: 0; }
h2 { margin: 0; font-size: 16px; overflow-wrap: anywhere; }
small { display: block; margin-top: 4px; color: var(--text-muted); font-size: 13px; overflow-wrap: anywhere; }
b { flex: 0 0 auto; color: var(--accent-strong); font-size: 13px; font-weight: 600; }
.head :deep(.explain) { flex: 0 0 auto; width: auto; }
.body { padding: 0 0 22px; }
</style>
