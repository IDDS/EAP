/** Revoke the exact token captured when logout began, even if the active session changes meanwhile. */
export async function revokeSessionToken(token: string): Promise<boolean> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 12_000)
  try {
    const response = await fetch('/api/platform/session/logout', {
      method: 'POST', headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store', signal: controller.signal,
    })
    if (!response.ok) return false
    const body: unknown = await response.json()
    if (typeof body !== 'object' || body === null) return false
    const result = body as { success?: unknown; data?: { revoked?: unknown } }
    return result.success === true && result.data?.revoked === true
  } catch {
    return false
  } finally {
    window.clearTimeout(timeout)
  }
}
