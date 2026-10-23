// Keep the submission identity after an uncertain response, including page reloads in this tab.
// Successful submissions release it so a deliberate subsequent execution gets a new identity.
export type WorkflowSubmission = { objective?: string; context?: { severity: string; approved: boolean } }
const pendingKeys = new Map<string, string>()

export async function submitWorkflowOnce<T>(workflowId: number, payload: WorkflowSubmission,
  send: (payload: WorkflowSubmission & { requestKey: string }) => Promise<T>): Promise<T> {
  const snapshot = JSON.parse(JSON.stringify(payload)) as WorkflowSubmission
  const storageKey = `eap.workflow.pending:${workflowId}:${JSON.stringify(snapshot)}`
  let requestKey = pendingKeys.get(storageKey)
  if (!requestKey) {
    try { requestKey = sessionStorage.getItem(storageKey) || undefined } catch { /* Storage may be unavailable. */ }
    requestKey ||= globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
    pendingKeys.set(storageKey, requestKey)
    try { sessionStorage.setItem(storageKey, requestKey) } catch { /* Keep the in-memory identity. */ }
  }
  const result = await send({ ...snapshot, requestKey })
  if (pendingKeys.get(storageKey) === requestKey) pendingKeys.delete(storageKey)
  try { if (sessionStorage.getItem(storageKey) === requestKey) sessionStorage.removeItem(storageKey) } catch { /* Best effort cleanup. */ }
  return result
}
