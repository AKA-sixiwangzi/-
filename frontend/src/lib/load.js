import { ref, onMounted, watch } from 'vue'
import { session } from './api.js'
export function useLoad(fn) {
  const loading = ref(true),
    error = ref('')
  let generation = 0
  async function reload() {
    const key = ++generation
    loading.value = true
    error.value = ''
    try {
      await fn()
    } catch (e) {
      if (key === generation) error.value = e.message
    } finally {
      if (key === generation) loading.value = false
    }
  }
  onMounted(reload)
  watch(() => [session.demoId, session.mode], reload)
  return { loading, error, reload }
}
