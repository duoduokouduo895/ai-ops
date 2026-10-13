import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { bindCsrf, clearCsrf, consoleApi } from '../api/client'
import type { SessionView } from '../api/types'
import { accessMode, accessLogoutPath } from '../utils/auth'
import { isUnauthorized } from '../utils/http'

export const useSessionStore = defineStore('session', () => {
  const current = ref<SessionView | null>(null)
  const initialized = ref(false)
  const restoreError = ref('')
  const authenticated = computed(() => current.value !== null)
  let restoring: Promise<void> | null = null

  async function restore() {
    if (initialized.value) return
    restoring ??= loadSession()
    await restoring
  }

  async function loadSession() {
    try {
      const { data } = await consoleApi.session()
      if (!data || typeof data.principal !== 'string' || !data.principal
          || (accessMode && data.authMode !== 'access')) throw new Error('CONSOLE_AUTH_MODE_MISMATCH')
      current.value = data
      if (!accessMode) bindCsrf(data.csrfToken)
    } catch (error) {
      current.value = null
      restoreError.value = isUnauthorized(error) ? '' : '暂时无法进入控制台，请重新登录或稍后重试。'
      clearCsrf()
    } finally {
      initialized.value = true
      restoring = null
    }
  }

  async function login(username: string, password: string) {
    if (accessMode) throw new Error('Console login is managed by Cloudflare Access')
    const { data } = await consoleApi.login(username, password)
    current.value = data
    bindCsrf(data.csrfToken)
    initialized.value = true
  }

  async function logout() {
    if (accessMode) {
      expire()
      window.location.assign(accessLogoutPath())
      return
    }
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

  return { current, initialized, authenticated, restoreError, accessMode, restore, login, logout, expire }
})
