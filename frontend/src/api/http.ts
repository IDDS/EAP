import axios from 'axios'
import type { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/domain'
import { clearAccessToken, getAccessToken } from '@/api/auth'

declare module 'axios' {
  interface AxiosRequestConfig { silentError?: boolean }
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 12000,
})

let lastErrorMessage = ''
let lastErrorAt = 0

function requestErrorMessage(error: AxiosError<{ message?: string }>) {
  const status = error.response?.status
  const serverMessage = error.response?.data?.message?.trim()

  if (status === 401) return '企业认证已失效，请重新连接'
  if (status === 403) return '当前账号没有执行此操作的权限'
  if (status === 404) return '请求的资源不存在或已经移除'
  if (status === 409) return serverMessage || '当前数据状态发生冲突，请刷新后重试'
  if (status === 400 || status === 422) return serverMessage || '请求参数不完整，请检查后重试'
  if (status && status >= 500) return 'EAP 服务暂时不可用，请稍后重试'
  if (error.code === 'ECONNABORTED') return '请求超时，请检查服务状态后重试'
  if (!error.response) return '无法连接 EAP 服务，请确认后端服务已经启动'
  return serverMessage || '请求失败，请稍后重试'
}

function showRequestError(message: string) {
  const now = Date.now()
  if (message === lastErrorMessage && now - lastErrorAt < 3000) return
  lastErrorMessage = message
  lastErrorAt = now
  ElMessage.error({ message, duration: 3600, grouping: true, showClose: true })
}

http.interceptors.request.use(config => {
  const accessToken = getAccessToken()
  if (accessToken && config.url !== '/platform/status') config.headers.Authorization = `Bearer ${accessToken}`
  return config
})

http.interceptors.response.use(
  response => response,
  (error: AxiosError<{ message?: string }>) => {
    if (error?.response?.status === 401) {
      const token = getAccessToken()
      const authorization = error.config?.headers?.Authorization
      if (authorization === (token ? `Bearer ${token}` : undefined)) {
        clearAccessToken()
        window.dispatchEvent(new Event('eap-auth-required'))
      }
    }
    if (!error.config?.silentError) showRequestError(requestErrorMessage(error))
    return Promise.reject(error)
  },
)

export async function getData<T>(url: string, options: { silentError?: boolean; signal?: AbortSignal } = {}): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, options)
  return response.data.data
}

export async function postData<T, P>(url: string, payload: P): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, payload)
  return response.data.data
}

export async function putData<T, P>(url: string, payload: P): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, payload)
  return response.data.data
}

export async function patchData<T, P>(url: string, payload: P): Promise<T> {
  const response = await http.patch<ApiResponse<T>>(url, payload)
  return response.data.data
}

export async function deleteData<T, P>(url: string, payload: P): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url, { data: payload })
  return response.data.data
}
