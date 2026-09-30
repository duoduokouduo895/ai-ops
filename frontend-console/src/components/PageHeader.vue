<script setup lang="ts">
import ExplainButton from './ExplainButton.vue'

defineProps<{ title: string; topic: string }>()
</script>

<template>
  <header class="page-head">
    <h1 class="page-title" data-page-heading tabindex="-1">{{ title }}</h1>
    <ExplainButton :topic="topic" mode="page" label="说明" :name="`查看 ${title} 的说明`" />
    <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-head { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; margin-bottom: 22px; }
.page-title { margin: 0; }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-left: auto; }
</style>
