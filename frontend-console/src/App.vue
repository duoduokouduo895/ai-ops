<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'

const session = useSessionStore()
const router = useRouter()
const focusPageHeading = () => document.querySelector<HTMLElement>('[data-page-heading]')?.focus({ preventScroll: true })
const handleSessionExpired = () => {
  // Initial restoration is handled by the route guard. Do not overwrite its requested return path.
  if (!session.initialized) return
  session.expire()
  if (router.currentRoute.value.name !== 'login') void router.replace({
    name: 'login', query: { reason: 'expired', redirect: router.currentRoute.value.fullPath },
  })
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
