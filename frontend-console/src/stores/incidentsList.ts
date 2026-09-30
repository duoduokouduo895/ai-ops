import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export const useIncidentsListStore = defineStore('incidentsList', () => {
  const query = ref<LocationQueryRaw>({})

  function remember(value: LocationQuery) {
    const next: LocationQueryRaw = {}
    for (const [key, item] of Object.entries(value)) {
      if (typeof item === 'string') next[key] = item
    }
    query.value = next
  }

  return { query, remember }
})
