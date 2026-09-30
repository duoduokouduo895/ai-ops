import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from '../../src/App.vue'

describe('HomeOps overview', () => {
  it('labels the measured power baseline without presenting it as live power', async () => {
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('6.18')
    expect(wrapper.text()).toContain('实测基线')
    expect(wrapper.text()).toContain('不冒充实时插座功耗')
    expect(wrapper.text()).toContain('只读视图')
    expect(wrapper.text()).toContain('系统状态')
    wrapper.unmount()
  })

  it('expands a service group and switches the real history range', async () => {
    const wrapper = mount(App)
    await flushPromises()

    const hostGroup = wrapper.get('button[aria-controls="group-host"]')
    expect(hostGroup.attributes('aria-expanded')).toBe('false')
    await hostGroup.trigger('click')
    expect(hostGroup.attributes('aria-expanded')).toBe('true')
    expect(wrapper.text()).toContain('Cloudflare Tunnel')

    const sevenDayButton = wrapper.findAll('.range-control button').find((button) => button.text() === '7d')
    expect(sevenDayButton).toBeDefined()
    await sevenDayButton!.trigger('click')
    await flushPromises()
    expect(sevenDayButton!.classes()).toContain('active')
    expect(wrapper.get('svg[aria-label*="7d 趋势"]').attributes('aria-label')).toContain('7d 趋势')
    wrapper.unmount()
  })
})
