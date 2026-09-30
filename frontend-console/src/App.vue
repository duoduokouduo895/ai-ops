<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'

const session = useSessionStore()
const router = useRouter()
const focusPageHeading = () => document.querySelector<HTMLElement>('[data-page-heading]')?.focus({ preventScroll: true })
const handleSessionExpired = () => {
  session.expire()
  if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login', query: { reason: 'expired' } })
}

onMounted(async () => {
  window.addEventListener('aidiag:session-expired', handleSessionExpired)
  void session.restore()
  await nextTick()
  focusPageHeading()
})
onBeforeUnmount(() => window.removeEventListener('aidiag:session-expired', handleSessionExpired))
</script>

<template>
  <RouterView v-slot="{ Component }">
    <Transition name="route" mode="out-in" @after-enter="focusPageHeading">
      <KeepAlive include="IncidentsView">
        <component :is="Component" />
      </KeepAlive>
    </Transition>
  </RouterView>
</template>
