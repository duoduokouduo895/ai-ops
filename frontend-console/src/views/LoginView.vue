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
    <section class="login-copy">
      <AidiagMark />
      <p class="eyebrow">Evidence-driven operations</p>
      <h1>从告警到证据，<br>在一个案卷里还原故障。</h1>
      <p class="intro">AIDiag 将 Metric、Log、Trace 与确定性诊断串成可审查的因果链。AI 建议始终低于已验证证据。</p>
      <dl><div><dt>01</dt><dd>Observe</dd></div><div><dt>02</dt><dd>Corroborate</dd></div><div><dt>03</dt><dd>Explain</dd></div></dl>
    </section>
    <form class="login-card panel" @submit.prevent="submit">
      <p class="eyebrow">Console access</p><h2>登录控制台</h2><p class="hint">单管理员 · 持久化安全 Session · 无浏览器密钥</p>
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
.login-page{min-height:100vh;display:grid;grid-template-columns:minmax(0,1.15fr) minmax(360px,.65fr);gap:clamp(40px,8vw,140px);align-items:center;max-width:1180px;margin:auto;padding:48px 34px}.login-copy{max-width:650px}.login-copy :deep(.mark){margin-bottom:44px}.login-copy h1{font-size:clamp(40px,5vw,68px);line-height:1.06;letter-spacing:-.055em;margin:10px 0 24px;font-weight:620}.intro{color:var(--text-soft);font-size:16px;max-width:570px}.login-copy dl{display:flex;gap:28px;margin:46px 0 0}.login-copy dl div{border-top:1px solid var(--line);padding-top:12px;min-width:110px}.login-copy dt{font:11px var(--font-mono);color:var(--accent)}.login-copy dd{margin:5px 0;color:var(--text-muted)}.login-card{padding:32px}.login-card h2{font-size:25px;margin:5px 0}.hint{color:var(--text-muted);margin:0 0 28px}.login-card label{display:grid;gap:8px;color:var(--text-soft);margin:18px 0;font-size:12px}.login-card :deep(.el-input__wrapper){background:var(--surface-2);box-shadow:0 0 0 1px var(--line) inset;border-radius:6px;padding:5px 12px}.login-card :deep(.el-input__inner){color:var(--text);height:38px}.login-card :deep(.el-button){width:100%;height:46px;margin-top:8px;background:var(--accent);border-color:var(--accent);color:var(--bg);font-weight:700}.login-card small{display:block;color:var(--text-muted);margin-top:16px;text-align:center}.error{color:var(--critical);font-size:12px}
.session-notice{border-left:2px solid var(--warning);background:color-mix(in oklch,var(--warning) 8%,transparent);color:var(--text-soft);padding:10px 12px;font-size:12px}
@media(max-width:800px){.login-page{grid-template-columns:1fr;padding:28px 18px}.login-copy h1{font-size:38px}.login-copy dl{display:none}.login-copy :deep(.mark){margin-bottom:24px}}
</style>
