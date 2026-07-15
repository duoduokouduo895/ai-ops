import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'inbox', component: () => import('../views/InboxView.vue') },
    { path: '/services', name: 'services', component: () => import('../views/ServicesView.vue') },
    { path: '/incidents', name: 'incidents', component: () => import('../views/IncidentsView.vue') },
    { path: '/incidents/:id', name: 'casefile', component: () => import('../views/IncidentCasefileView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.initialized) await session.restore()
  if (!to.meta.public && !session.authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && session.authenticated) return { name: 'inbox' }
})

export default router
