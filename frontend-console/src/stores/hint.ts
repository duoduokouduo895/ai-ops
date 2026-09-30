import { defineStore } from 'pinia'
import { ref } from 'vue'

export type HintKind = 'tooltip' | 'panel'

export interface HintState {
  topicId: string
  kind: HintKind
  pinned: boolean
  trigger: HTMLElement
}

export const useHintStore = defineStore('hint', () => {
  const active = ref<HintState | null>(null)
  let showTimer = 0
  let hideTimer = 0
  let dismissedFor: HTMLElement | null = null

  function clearTimers() {
    window.clearTimeout(showTimer)
    window.clearTimeout(hideTimer)
    showTimer = 0
    hideTimer = 0
  }

  function open(topicId: string, kind: HintKind, trigger: HTMLElement, pinned: boolean) {
    clearTimers()
    dismissedFor = null
    active.value = { topicId, kind, pinned, trigger }
  }

  function scheduleShow(topicId: string, kind: HintKind, trigger: EventTarget | null, delay: number) {
    if (!(trigger instanceof HTMLElement)) return
    if (dismissedFor && dismissedFor !== trigger) dismissedFor = null
    if (dismissedFor === trigger) return
    if (active.value?.pinned && active.value.trigger === trigger) return
    clearTimers()
    showTimer = window.setTimeout(() => open(topicId, kind, trigger, false), delay)
  }

  function pin(topicId: string, kind: HintKind, trigger: EventTarget | null) {
    if (!(trigger instanceof HTMLElement)) return
    if (active.value?.pinned && active.value.trigger === trigger && active.value.topicId === topicId) {
      close()
      return
    }
    open(topicId, kind, trigger, true)
  }

  function scheduleHide() {
    window.clearTimeout(showTimer)
    showTimer = 0
    if (active.value?.pinned) return
    if (active.value && document.activeElement === active.value.trigger) return
    hideTimer = window.setTimeout(() => {
      if (active.value?.pinned) return
      active.value = null
    }, 400)
  }

  function cancelHide() {
    window.clearTimeout(hideTimer)
    hideTimer = 0
  }

  function release(trigger?: EventTarget | null) {
    if (trigger instanceof HTMLElement && dismissedFor === trigger) dismissedFor = null
  }

  function close() {
    dismissedFor = active.value?.trigger ?? dismissedFor
    clearTimers()
    active.value = null
  }

  return { active, scheduleShow, pin, scheduleHide, cancelHide, release, close }
})
