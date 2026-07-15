<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'

const session = useSessionStore()
const router = useRouter()
const handleSessionExpired = () => {
  session.expire()
  if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login', query: { reason: 'expired' } })
}

onMounted(() => {
  window.addEventListener('aidiag:session-expired', handleSessionExpired)
  void session.restore()
})
onBeforeUnmount(() => window.removeEventListener('aidiag:session-expired', handleSessionExpired))
</script>

<template>
  <RouterView v-slot="{ Component }">
    <Transition name="route" mode="out-in">
      <component :is="Component" />
    </Transition>
  </RouterView>
</template>
