const ACCESS_TOKEN_KEY = 'eap.access_token'

export function getAccessToken(): string {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? ''
}

export function setAccessToken(token: string): void {
  const normalized = token.trim().replace(/^Bearer\s+/i, '')
  if (normalized) sessionStorage.setItem(ACCESS_TOKEN_KEY, normalized)
  else sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  window.dispatchEvent(new Event('eap-auth-changed'))
}

export function clearAccessToken(): void {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  window.dispatchEvent(new Event('eap-auth-changed'))
}
