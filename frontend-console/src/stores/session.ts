import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { bindCsrf, clearCsrf, consoleApi } from '../api/client'
import type { SessionView } from '../api/types'

export const useSessionStore = defineStore('session', () => {
  const current = ref<SessionView | null>(null)
  const initialized = ref(false)
  const authenticated = computed(() => current.value !== null)

  async function restore() {
    if (initialized.value) return
    try {
      const { data } = await consoleApi.session()
      current.value = data
      bindCsrf(data.csrfToken)
    } catch {
      current.value = null
      clearCsrf()
    } finally {
      initialized.value = true
    }
  }

  async function login(username: string, password: string) {
    const { data } = await consoleApi.login(username, password)
    current.value = data
    bindCsrf(data.csrfToken)
    initialized.value = true
  }

  async function logout() {
    try { await consoleApi.logout() } finally {
      current.value = null
      clearCsrf()
    }
  }

  function expire() {
    current.value = null
    initialized.value = true
    clearCsrf()
  }

  return { current, initialized, authenticated, restore, login, logout, expire }
})
