export type OidcConfig = { issuer: string; clientId: string; scopes: string }
export type OidcTransaction = {
  issuer: string
  clientId: string
  state: string
  verifier: string
  redirectUri: string
  returnTo: string
  createdAt: number
}

type Discovery = { issuer: string; authorization_endpoint: string; token_endpoint: string; code_challenge_methods_supported?: string[] }
const TRANSACTION_KEY = 'eap.oidc.transaction'
const MAX_AGE_MS = 10 * 60 * 1000

export function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function randomPkceValue(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)))
}

export async function pkceChallenge(verifier: string): Promise<string> {
  return base64Url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))))
}

export function safeReturnPath(value: string): string {
  if (!value.startsWith('/') || value.startsWith('//') || /[\\\s]/.test(value)) return '/'
  const parsed = new URL(value, 'https://eap.invalid')
  if (parsed.origin !== 'https://eap.invalid' || /^\/auth\/callback\/?$/.test(parsed.pathname)) return '/'
  return parsed.pathname + parsed.search + parsed.hash
}

function providerUrl(value: string): URL {
  const url = new URL(value)
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) || url.username || url.password || url.hash) {
    throw new Error('身份提供方地址必须使用 HTTPS（本机调试可使用 HTTP）')
  }
  return url
}

async function discover(config: OidcConfig): Promise<Discovery> {
  if (!config.clientId.trim() || !config.issuer) throw new Error('平台尚未配置企业登录')
  const issuer = providerUrl(config.issuer)
  if (issuer.search) throw new Error('身份提供方 issuer 不能包含查询参数')
  const response = await fetch(`${config.issuer.replace(/\/$/, '')}/.well-known/openid-configuration`, {
    credentials: 'omit', cache: 'no-store', redirect: 'error',
  })
  if (!response.ok) throw new Error('无法读取企业身份提供方配置')
  const metadata = await response.json() as Discovery
  if (metadata.issuer !== config.issuer) throw new Error('身份提供方 issuer 与平台配置不一致')
  if (!metadata.authorization_endpoint || !metadata.token_endpoint) throw new Error('身份提供方缺少授权或令牌端点')
  providerUrl(metadata.authorization_endpoint)
  providerUrl(metadata.token_endpoint)
  if (metadata.code_challenge_methods_supported && !metadata.code_challenge_methods_supported.includes('S256')) {
    throw new Error('身份提供方不支持 PKCE S256')
  }
  return metadata
}

export async function startOidcSignIn(config: OidcConfig, returnTo: string): Promise<void> {
  const metadata = await discover(config)
  const verifier = randomPkceValue()
  const transaction: OidcTransaction = {
    issuer: config.issuer, clientId: config.clientId, state: randomPkceValue(), verifier,
    redirectUri: `${window.location.origin}/auth/callback`, returnTo: safeReturnPath(returnTo), createdAt: Date.now(),
  }
  const url = providerUrl(metadata.authorization_endpoint)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', config.clientId)
  url.searchParams.set('redirect_uri', transaction.redirectUri)
  url.searchParams.set('scope', [...new Set(['openid', ...config.scopes.split(/\s+/).filter(Boolean)])].join(' '))
  url.searchParams.set('state', transaction.state)
  url.searchParams.set('code_challenge', await pkceChallenge(verifier))
  url.searchParams.set('code_challenge_method', 'S256')
  sessionStorage.setItem(TRANSACTION_KEY, JSON.stringify(transaction))
  window.location.assign(url.href)
}

export function consumeOidcCallback(search: string, config: OidcConfig, origin: string, now = Date.now()): { code: string; transaction: OidcTransaction } {
  const raw = sessionStorage.getItem(TRANSACTION_KEY)
  sessionStorage.removeItem(TRANSACTION_KEY)
  if (!raw) throw new Error('登录请求不存在或已使用，请重新登录')
  const transaction = JSON.parse(raw) as OidcTransaction
  const params = new URLSearchParams(search)
  if (typeof transaction.state !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(transaction.state)
      || params.getAll('state').length !== 1 || params.get('state') !== transaction.state) {
    throw new Error('登录 state 校验失败，请重新登录')
  }
  if (!Number.isFinite(transaction.createdAt) || now - transaction.createdAt > MAX_AGE_MS || now < transaction.createdAt
      || transaction.issuer !== config.issuer || transaction.clientId !== config.clientId
      || transaction.redirectUri !== `${origin}/auth/callback`
      || typeof transaction.verifier !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(transaction.verifier)) {
    throw new Error('登录请求已过期或配置已改变，请重新登录')
  }
  if (params.has('iss') && (params.getAll('iss').length !== 1 || params.get('iss') !== config.issuer)) {
    throw new Error('登录响应 issuer 不一致')
  }
  if (params.has('error')) throw new Error('企业身份提供方未完成授权，请重新登录')
  if (params.getAll('code').length !== 1 || !params.get('code')) throw new Error('登录响应缺少授权码')
  transaction.returnTo = safeReturnPath(typeof transaction.returnTo === 'string' ? transaction.returnTo : '/')
  return { code: params.get('code')!, transaction }
}

export async function finishOidcSignIn(config: OidcConfig, search: string): Promise<{ accessToken: string; returnTo: string }> {
  const { code, transaction } = consumeOidcCallback(search, config, window.location.origin)
  const metadata = await discover(config)
  const response = await fetch(metadata.token_endpoint, {
    method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', client_id: config.clientId,
      code, redirect_uri: transaction.redirectUri, code_verifier: transaction.verifier }),
  })
  if (!response.ok) throw new Error('登录令牌交换失败，请重新登录')
  const token = await response.json() as { access_token?: string; token_type?: string }
  if (typeof token.access_token !== 'string' || !token.access_token.trim() || token.token_type?.toLowerCase() !== 'bearer') {
    throw new Error('身份提供方没有返回有效的 Bearer 访问令牌')
  }
  return { accessToken: token.access_token, returnTo: transaction.returnTo }
}
