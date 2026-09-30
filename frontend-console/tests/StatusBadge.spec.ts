import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import StatusBadge from '../src/components/StatusBadge.vue'
import HintLayer from '../src/components/HintLayer.vue'
import { useHintStore } from '../src/stores/hint'

function mountBadge(props: { value: string; domain: 'incident' | 'service' | 'run' | 'report' }) {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(StatusBadge, { props, global: { plugins: [pinia] } })
}

describe('StatusBadge', () => {
  it('uses semantic success styling for supported report outcomes', () => {
    const wrapper = mountBadge({ value: 'SUPPORTED', domain: 'report' })
    expect(wrapper.text()).toContain('SUPPORTED')
    expect(wrapper.attributes('data-tone')).toBe('success')
  })

  it('keeps unknown service state distinct from healthy and warning states', () => {
    const wrapper = mountBadge({ value: 'UNKNOWN', domain: 'service' })
    expect(wrapper.attributes('data-tone')).toBe('unknown')
  })

  it('does not treat a completed diagnosis run as a recovered incident', () => {
    const run = mountBadge({ value: 'COMPLETED', domain: 'run' })
    const incident = mountBadge({ value: 'RESOLVED', domain: 'incident' })
    expect(run.attributes('data-tone')).toBe('success')
    expect(incident.attributes('data-tone')).toBe('success')
    expect(run.props('domain')).not.toBe(incident.props('domain'))
  })

  it('makes the whole status mark the explanation control', () => {
    const wrapper = mountBadge({ value: 'RESOLVED', domain: 'incident' })
    expect(wrapper.element.tagName).toBe('BUTTON')
    expect(wrapper.attributes('aria-label')).toBe('查看 RESOLVED 的含义')
    expect(wrapper.findAll('.explain')).toHaveLength(0)
  })

  it('pins a short local hint instead of opening the glossary drawer', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    const wrapper = mount({
      components: { StatusBadge, HintLayer },
      template: '<div><StatusBadge value="RESOLVED" domain="incident" /><HintLayer /></div>',
    }, { global: { plugins: [pinia, router], stubs: { teleport: true } } })
    await wrapper.get('[aria-label="查看 RESOLVED 的含义"]').trigger('click')
    expect(useHintStore().active?.topicId).toBe('incident.RESOLVED')
    expect(useHintStore().active?.pinned).toBe(true)
    expect(wrapper.text()).toContain('RESOLVED · 已记录恢复')
    expect(wrapper.text()).toContain('这条事件已记录恢复')
    expect(wrapper.text()).not.toContain('依据：')
  })
})
