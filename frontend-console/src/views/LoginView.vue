<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import AidiagMark from '../components/AidiagMark.vue'
import { useSessionStore } from '../stores/session'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const session = useSessionStore()
const router = useRouter()
const route = useRoute()
const expired = route.query.reason === 'expired'

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await session.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch {
    error.value = '账号或密码错误，或登录暂时受限。'
  } finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-copy" aria-labelledby="login-product-title">
      <RouterLink class="login-brand" :to="{ name: 'login' }" aria-label="AIDiag 登录"><AidiagMark /><span>AIDiag</span></RouterLink>
      <h1 id="login-product-title">从告警到证据，<br>在一个案卷里还原故障。</h1>
      <p class="intro">AIDiag 将 Metric、Log、Trace 与确定性诊断串成可审查的因果链。</p>
    </section>

    <form class="login-form" @submit.prevent="submit">
      <div class="form-heading"><h2>登录控制台</h2></div>
      <p v-if="expired" role="status" class="session-notice">会话已过期，请重新登录。未提交的修改不会被重放。</p>
      <label>管理员标识<ElInput v-model="username" maxlength="128" autocomplete="username" placeholder="aidiag-admin" /></label>
      <label>密码<ElInput v-model="password" type="password" maxlength="256" show-password autocomplete="current-password" /></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <ElButton native-type="submit" :loading="loading" :disabled="!username || !password">进入 Incident Casefile</ElButton>
      <small>会话 Cookie 为 HttpOnly / Secure / SameSite=Strict。</small>
    </form>
  </main>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(340px, .58fr); gap: clamp(48px, 9vw, 150px); align-items: center; max-width: 1180px; margin: auto; padding: 52px 34px; }
.login-copy { max-width: 660px; }.login-brand { display: inline-flex; align-items: center; gap: 10px; margin-bottom: 52px; color: var(--text); font-weight: 650; }.login-brand :deep(.mark) { width: 31px; height: 31px; color: var(--accent-strong); }
h1 { margin: 0; font-size: 52px; line-height: 1.08; letter-spacing: -.04em; font-weight: 650; }.intro { max-width: 60ch; margin: 24px 0 0; color: var(--text-soft); font-size: 16px; line-height: 1.7; }
.login-form { border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface-1); padding: 32px; }.form-heading h2 { margin: 0 0 28px; font-size: 24px; letter-spacing: -.025em; }.login-form label { display: grid; gap: 8px; margin: 18px 0; color: var(--text-soft); font-size: 12px; font-weight: 600; }
.login-form :deep(.el-input__wrapper) { min-height: 46px; border-radius: var(--radius-sm); background: var(--surface-2); box-shadow: 0 0 0 1px var(--line) inset; padding: 5px 12px; }.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: var(--focus-ring); }.login-form :deep(.el-input__inner) { height: 36px; color: var(--text); }.login-form :deep(.el-button) { width: 100%; min-height: 46px; margin-top: 8px; border-color: var(--accent); border-radius: var(--radius-sm); background: var(--accent); color: var(--bg); font-weight: 720; }.login-form :deep(.el-button.is-disabled) { border-color: var(--line); background: var(--surface-2); color: var(--text-faint); }.login-form small { display: block; margin-top: 17px; color: var(--text-muted); text-align: center; font-size: 11px; }.error { margin: -3px 0 8px; color: var(--critical); font-size: 12px; }.session-notice { margin: 0 0 18px; border: 1px solid oklch(0.62 .09 83 / .45); border-radius: var(--radius-sm); background: var(--warning-soft); padding: 10px 12px; color: var(--text-soft); font-size: 12px; }
@media (max-width: 800px) { .login-page { grid-template-columns: 1fr; gap: 38px; padding: 30px 18px; }.login-brand { margin-bottom: 34px; } h1 { font-size: 38px; }.login-form { padding: 24px; } }
</style>
