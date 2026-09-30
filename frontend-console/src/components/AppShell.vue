<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useExplainStore } from '../stores/explain'
import { useHintStore } from '../stores/hint'
import { useSessionStore } from '../stores/session'
import AidiagMark from './AidiagMark.vue'

const ExplainDrawer = defineAsyncComponent(() => import('./ExplainDrawer.vue'))
const HintLayer = defineAsyncComponent(() => import('./HintLayer.vue'))

withDefaults(defineProps<{ section: string; width?: 'page' | 'casefile' }>(), { width: 'page' })
const router = useRouter()
const session = useSessionStore()
const explain = useExplainStore()
const hint = useHintStore()
const mobileNavOpen = ref(false)
const signingOut = ref(false)
const nav = [
  { name: 'inbox', label: 'Inbox' },
  { name: 'ai-chat', label: 'AI-chat' },
  { name: 'incidents', label: 'Incidents' },
  { name: 'services', label: 'Services' },
]
async function signOut() {
  if (signingOut.value) return
  signingOut.value = true
  try { await session.logout(); await router.push({ name: 'login' }) } finally { signingOut.value = false }
}
function openGuide(event: Event) {
  hint.close()
  explain.show('page.guide', event)
  closeMobileNav()
}
function closeMobileNav() { mobileNavOpen.value = false }
function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (hint.active) return
  closeMobileNav()
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="shell">
    <aside class="rail" aria-label="控制台主导航">
      <RouterLink class="brand" :to="{ name: 'inbox' }">
        <AidiagMark />
        <span><strong>AIDiag</strong></span>
      </RouterLink>
      <nav class="desktop-nav" aria-label="主要导航">
        <RouterLink v-for="item in nav" :key="item.name" :to="{ name: item.name }" :class="{ active: section === item.name }">
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="rail-footer">
        <button class="guide" type="button" @click="openGuide($event)">使用说明</button>
        <button class="signout" type="button" :disabled="signingOut" :aria-busy="signingOut" @click="signOut">{{ signingOut ? 'Signing out…' : 'Sign out' }}</button>
      </div>
    </aside>

    <header class="mobile-bar">
      <RouterLink class="mobile-brand" :to="{ name: 'inbox' }" aria-label="AIDiag Inbox"><AidiagMark /><span>AIDiag</span></RouterLink>
      <button class="menu-button" type="button" :aria-expanded="mobileNavOpen" aria-controls="mobile-navigation" @click="mobileNavOpen = !mobileNavOpen">Menu</button>
    </header>
    <Transition name="drawer">
      <nav v-if="mobileNavOpen" id="mobile-navigation" class="mobile-nav" aria-label="移动端主导航">
        <RouterLink v-for="item in nav" :key="item.name" :to="{ name: item.name }" :class="{ active: section === item.name }" @click="closeMobileNav">
          <span>{{ item.label }}</span>
        </RouterLink>
        <button type="button" @click="openGuide($event)">使用说明</button>
        <button type="button" :disabled="signingOut" :aria-busy="signingOut" @click="signOut">{{ signingOut ? 'Signing out…' : 'Sign out' }}</button>
      </nav>
    </Transition>
    <main class="workspace"><div class="page" :data-width="width"><slot /></div></main>
    <ExplainDrawer />
    <HintLayer />
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; display: grid; grid-template-columns: 196px minmax(0, 1fr); }
.rail { position: sticky; top: 0; display: flex; height: 100vh; flex-direction: column; border-right: 1px solid var(--line); background: var(--surface-0); padding: 20px 12px 16px; }
.brand { display: flex; align-items: center; gap: 11px; padding: 5px 8px 24px; }
.brand :deep(.mark) { width: 26px; height: 26px; color: var(--accent-strong); }
.brand strong { display: block; font-size: 14px; letter-spacing: -.015em; }
.desktop-nav { display: grid; gap: 2px; }
.desktop-nav a { min-height: 44px; display: grid; align-content: center; border: 1px solid transparent; border-radius: var(--radius-sm); padding: 8px 10px; color: var(--text-muted); transition: color var(--duration-fast) var(--ease), border-color var(--duration-fast) var(--ease), background var(--duration-fast) var(--ease); }
.desktop-nav a:hover { background: var(--surface-1); color: var(--text-soft); }
.desktop-nav a.active { border-color: oklch(0.51 0.09 288 / .5); background: var(--accent-wash); color: var(--text); }
.desktop-nav span { font-size: 13px; font-weight: 610; }
.rail-footer { display: grid; gap: 4px; margin-top: auto; padding: 12px 8px 4px; border-top: 1px solid var(--line-soft); }
.guide, .signout { display: inline-flex; width: fit-content; min-height: 44px; align-items: center; border: 0; background: transparent; padding: 0; cursor: pointer; font-size: 13px; }
.guide { color: var(--text-soft); }
.guide:hover, .signout:hover { color: var(--text); }
.signout { color: var(--text-muted); font-size: 12px; }
.signout:disabled { color: var(--text-faint); cursor: wait; }
.workspace { min-width: 0; padding: 28px clamp(20px, 3vw, 40px) 48px; }
.page { max-width: 1120px; }
.page[data-width="casefile"] { max-width: 960px; }
.mobile-bar, .mobile-nav { display: none; }
.drawer-enter-active, .drawer-leave-active { transition: opacity var(--duration-base) var(--ease), transform var(--duration-base) var(--ease); }
.drawer-enter-from, .drawer-leave-to { opacity: 0; transform: translateY(-6px); }
@media (max-width: 760px) {
  .shell { display: block; }
  .rail { display: none; }
  .mobile-bar { position: sticky; z-index: 20; top: 0; display: flex; min-height: 60px; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--line); background: var(--surface-0); padding: 0 16px; }
  .mobile-brand { display: flex; align-items: center; gap: 8px; font-weight: 650; font-size: 14px; }
  .mobile-brand :deep(.mark) { width: 25px; height: 25px; color: var(--accent-strong); }
  .menu-button { min-height: 44px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-1); padding: 0 12px; color: var(--text-soft); cursor: pointer; font-size: 12px; }
  .mobile-nav { position: fixed; z-index: 19; top: 60px; right: 0; left: 0; display: grid; border-bottom: 1px solid var(--line); background: var(--surface-0); padding: 10px 16px 16px; }
  .mobile-nav a, .mobile-nav button { display: flex; min-height: 48px; align-items: center; border: 0; border-bottom: 1px solid var(--line-soft); background: transparent; padding: 0 2px; color: var(--text-soft); text-align: left; }
  .mobile-nav a.active { color: var(--accent-strong); }
  .mobile-nav button { color: var(--text-muted); cursor: pointer; }
  .mobile-nav button:disabled { color: var(--text-faint); cursor: wait; }
  .workspace { padding: 22px 16px 40px; }
}
</style>
