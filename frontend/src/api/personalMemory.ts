import { getData, postData } from '@/api/http'

export interface PersonalMemory {
  id: string
  content: string
  createdAtMs: number
  expiresAtMs: number | null
  status: 'ACTIVE' | 'EXPIRED'
}
export interface PersonalMemoryPage { items: PersonalMemory[]; nextCursor: string | null }
export interface PersonalMemoryRequest { requestKey: string; content: string; expiresAtMs: number | null; explicitlyRemember: true }

export function listPersonalMemories(cursor?: string) {
  return getData<PersonalMemoryPage>(`/personal-memories${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`)
}
export function createPersonalMemory(request: PersonalMemoryRequest) {
  return postData<PersonalMemory, PersonalMemoryRequest>('/personal-memories', request)
}
export function revokePersonalMemory(id: string) {
  return postData<{ revoked: boolean }, Record<string, never>>(`/personal-memories/${encodeURIComponent(id)}/revoke`, {})
}
