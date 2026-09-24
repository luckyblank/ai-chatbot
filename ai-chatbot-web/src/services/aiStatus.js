import { reactive } from 'vue'
import { systemAPI } from './api'
export { describeAiStatus } from './aiStatusDisplay'

export const aiStatusState = reactive({ status: null, loading: false })
let requestSequence = 0

export function resetAiStatus() {
  requestSequence += 1
  aiStatusState.status = null
  aiStatusState.loading = false
}

export async function refreshAiStatus() {
  const currentRequest = ++requestSequence
  // A failed refresh must not leave a previously configured state on screen.
  aiStatusState.status = null
  aiStatusState.loading = true
  try {
    const status = await systemAPI.aiStatus()
    if (currentRequest === requestSequence) aiStatusState.status = status
  } catch {
    // The UI must not claim that AI is configured when the server is unavailable.
    if (currentRequest === requestSequence) aiStatusState.status = null
  } finally {
    if (currentRequest === requestSequence) aiStatusState.loading = false
  }
}
