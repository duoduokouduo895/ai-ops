import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useExplainStore = defineStore('explain', () => {
  const open = ref(false)
  const topicId = ref('glossary')
  let origin: HTMLElement | null = null

  function show(id: string, event?: Event) {
    const target = event?.currentTarget
    origin = target instanceof HTMLElement ? target : null
    topicId.value = id
    open.value = true
  }

  function close() {
    open.value = false
  }

  function restoreFocus() {
    origin?.focus()
    origin = null
  }

  return { open, topicId, show, close, restoreFocus }
})
