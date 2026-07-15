import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusBadge from '../src/components/StatusBadge.vue'

describe('StatusBadge', () => {
  it('uses semantic success styling for supported outcomes', () => {
    const wrapper = mount(StatusBadge, { props: { value: 'SUPPORTED' } })
    expect(wrapper.text()).toContain('SUPPORTED')
    expect(wrapper.attributes('data-tone')).toBe('success')
  })

  it('does not present unknown as healthy', () => {
    const wrapper = mount(StatusBadge, { props: { value: 'UNKNOWN' } })
    expect(wrapper.attributes('data-tone')).toBe('warning')
  })
})
