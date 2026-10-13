<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import AidiagMark from '../components/AidiagMark.vue'
import { useSessionStore } from '../stores/session'
import { accessMode, consoleReturnPath } from '../utils/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const session = useSessionStore()
const router = useRouter()
const route = useRoute()
const expired = route.query.reason === 'expired'
const returnPath = computed(() => consoleReturnPath(route.query.redirect))

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await session.login(username.value, password.value)
    await router.replace(returnPath.value)
  } catch {
    error.value = '账号或密码错误，或登录暂时受限。'
  } finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section v-if="accessMode" class="login-form" aria-label="Cloudflare 登录">
      <div class="brand"><AidiagMark /><span>AIDiag</span></div>
      <h1 data-page-heading tabindex="-1">登录</h1>
      <p class="purpose">通过 Cloudflare 账号访问告警事件与诊断结论。</p>
      <p v-if="expired" role="status" class="session-notice">会话已过期，请重新登录。</p>
      <p v-else-if="session.restoreError" role="status" class="session-notice">{{ session.restoreError }}</p>
      <a class="access-login" :href="returnPath">使用 Cloudflare 账号登录</a>
    </section>
    <form v-else class="login-form" @submit.prevent="submit">
      <div class="brand"><AidiagMark /><span>AIDiag</span></div>
      <h1 data-page-heading tabindex="-1">登录</h1>
      <p class="purpose">用于阅读告警事件与诊断结论。</p>
      <p v-if="expired" role="status" class="session-notice">会话已过期，请重新登录。</p>
      <label>管理员账号<ElInput v-model="username" maxlength="128" autocomplete="username" /></label>
      <label>密码<ElInput v-model="password" type="password" maxlength="256" show-password autocomplete="current-password" /></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <ElButton native-type="submit" :loading="loading" :disabled="!username || !password">登录</ElButton>
    </form>
  </main>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; place-items: center; padding: 32px 16px; }
.login-form { width: min(360px, 100%); }
.brand { display: inline-flex; align-items: center; gap: 10px; margin-bottom: 28px; font-weight: 650; }
.brand :deep(.mark) { width: 28px; height: 28px; color: var(--accent-strong); }
h1 { margin: 0 0 8px; font-size: 24px; letter-spacing: -.02em; }
.purpose { margin: 0 0 24px; color: var(--text-muted); font-size: 13px; }
label { display: grid; gap: 8px; margin: 16px 0; color: var(--text-soft); font-size: 13px; font-weight: 600; }
.login-form :deep(.el-input__wrapper) { min-height: 46px; border-radius: var(--radius-sm); background: var(--surface-2); box-shadow: 0 0 0 1px var(--line) inset; padding: 5px 12px; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: var(--focus-ring); }
.login-form :deep(.el-input__inner) { height: 36px; color: var(--text); }
.login-form :deep(.el-button) { width: 100%; min-height: 46px; margin-top: 8px; border-color: var(--accent); border-radius: var(--radius-sm); background: var(--accent); color: var(--bg); font-weight: 720; }
.login-form :deep(.el-button.is-disabled) { border-color: var(--line); background: var(--surface-2); color: var(--text-faint); }
.access-login { display: flex; align-items: center; justify-content: center; min-height: 46px; border-radius: var(--radius-sm); background: var(--accent); color: var(--bg); font-size: 14px; font-weight: 720; }
.access-login:hover { filter: brightness(1.08); }
.error { margin: -2px 0 8px; color: var(--critical); font-size: 13px; }
.session-notice { margin: -8px 0 18px; color: var(--warning); font-size: 13px; }
</style>
