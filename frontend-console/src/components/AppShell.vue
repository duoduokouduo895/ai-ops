<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import AidiagMark from './AidiagMark.vue'

defineProps<{ section: string }>()
const router = useRouter()
const session = useSessionStore()
const nav = [
  { name: 'inbox', label: 'Inbox', glyph: '⌂' },
  { name: 'incidents', label: 'Incidents', glyph: '△' },
  { name: 'services', label: 'Services', glyph: '◇' },
]
async function signOut() { await session.logout(); await router.push({ name: 'login' }) }
</script>
<template>
  <div class="shell">
    <aside class="rail">
      <RouterLink class="brand" :to="{ name: 'inbox' }"><AidiagMark /></RouterLink>
      <nav aria-label="主要导航">
        <RouterLink v-for="item in nav" :key="item.name" :to="{ name: item.name }" :class="{ active: section === item.name }">
          <span class="glyph" aria-hidden="true">{{ item.glyph }}</span><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <button class="signout" type="button" @click="signOut" title="退出登录"><span>↪</span><small>Sign out</small></button>
    </aside>
    <main class="workspace"><slot /></main>
  </div>
</template>
<style scoped>
.shell { min-height:100vh; display:grid; grid-template-columns:96px minmax(0,1fr); }
.rail { position:sticky; top:0; height:100vh; border-right:1px solid var(--line); background:oklch(0.17 0.014 275); display:flex; flex-direction:column; align-items:center; padding:20px 10px 16px; }
.brand { color:var(--text); margin:0 0 30px; }
nav { width:100%; display:grid; gap:8px; }
nav a { min-height:66px; border:1px solid transparent; border-radius:var(--radius-sm); color:var(--text-muted); display:grid; place-items:center; align-content:center; gap:4px; font-size:11px; transition:background 160ms var(--ease),color 160ms var(--ease),border-color 160ms var(--ease); }
nav a:hover { color:var(--text); background:var(--surface-1); }
nav a.active { color:oklch(0.82 .11 288); background:var(--accent-soft); border-color:oklch(.58 .12 288 / .42); }
.glyph { font:22px/1 var(--font-mono); }.signout { margin-top:auto; border:0; background:transparent; color:var(--text-muted); display:grid; place-items:center; gap:2px; cursor:pointer; }.signout span{font-size:20px}.signout small{font-size:10px}
.workspace { min-width:0; padding:24px clamp(18px,3vw,44px) 48px; }
@media (max-width:760px){.shell{grid-template-columns:1fr}.rail{z-index:20; position:fixed; inset:auto 0 0; width:100%; height:68px; flex-direction:row; padding:7px 12px; border-right:0;border-top:1px solid var(--line)}.brand,.signout{display:none}nav{grid-template-columns:repeat(3,1fr);gap:5px}nav a{min-height:52px}.workspace{padding:18px 14px 88px}}
</style>
