import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/domain'

export const http = axios.create({
  baseURL: '/api',
  timeout: 12000,
})

http.interceptors.response.use(
  response => response,
  error => {
    const message = error?.response?.data?.message || error?.message || '请求失败，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export async function getData<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
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
