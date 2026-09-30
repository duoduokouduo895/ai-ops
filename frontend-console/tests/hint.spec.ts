import { describe, expect, it, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useHintStore } from '../src/stores/hint'

describe('hint store', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('lets the pointer travel into the hint before closing', () => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
    const store = useHintStore()
    const trigger = document.createElement('button')
    store.scheduleShow('incident.RESOLVED', 'tooltip', trigger, 250)
    vi.advanceTimersByTime(250)
    expect(store.active?.topicId).toBe('incident.RESOLVED')
    store.scheduleHide()
    vi.advanceTimersByTime(200)
    expect(store.active).not.toBeNull()
    store.cancelHide()
    vi.advanceTimersByTime(400)
    expect(store.active?.topicId).toBe('incident.RESOLVED')
  })

  it('does not immediately reopen after Escape on the same trigger', () => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
    const store = useHintStore()
    const trigger = document.createElement('button')
    store.pin('incident.RESOLVED', 'tooltip', trigger)
    store.close()
    store.scheduleShow('incident.RESOLVED', 'tooltip', trigger, 0)
    vi.advanceTimersByTime(0)
    expect(store.active).toBeNull()
    store.release(trigger)
    store.scheduleShow('incident.RESOLVED', 'tooltip', trigger, 0)
    vi.advanceTimersByTime(0)
    expect(store.active?.topicId).toBe('incident.RESOLVED')
  })

  it('still allows click-to-pin after Escape on the same trigger', () => {
    setActivePinia(createPinia())
    const store = useHintStore()
    const trigger = document.createElement('button')
    store.pin('incident.RESOLVED', 'tooltip', trigger)
    store.close()
    expect(store.active).toBeNull()
    store.pin('incident.RESOLVED', 'tooltip', trigger)
    expect(store.active?.pinned).toBe(true)
  })
})
