import type { components } from '@ziji/api-types'

import { apiRequest } from '@/lib/api-client'

export type Category = components['schemas']['Category']
export type Tag = components['schemas']['Tag']
export type CategoryScope = 'PERSONAL' | 'ACCOUNT'
export type CategoryType = Category['categoryType']

interface PageMeta {
  requestId: string
  nextCursor?: string | null
  hasMore?: boolean
}

export async function listCategories(scope: CategoryScope = 'PERSONAL'): Promise<Category[]> {
  const response = await apiRequest<{ data: Category[]; meta: PageMeta }>(`/api/v1/categories?scope=${scope}&limit=100`)
  return response.data
}

export async function createCategory(body: {
  accountId?: string | null
  categoryType: CategoryType
  name: string
  parentId?: string | null
}): Promise<Category> {
  const response = await apiRequest<{ data: Category }>('/api/v1/categories', {
    method: 'POST',
    headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
    body,
  })
  return response.data
}

export function categoryEtag(version: number): string {
  return `"${version}"`
}

export async function patchCategory(
  categoryId: string,
  etag: string,
  body: { name?: string; status?: 'ACTIVE' | 'INACTIVE' },
): Promise<Category> {
  const response = await apiRequest<{ data: Category }>(`/api/v1/categories/${categoryId}`, {
    method: 'PATCH',
    headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
    body,
  })
  return response.data
}

export async function mergeCategory(
  categoryId: string,
  etag: string,
  idempotencyKey: string,
  targetCategoryId: string,
): Promise<Category> {
  const response = await apiRequest<{ data: Category }>(`/api/v1/categories/${categoryId}/merge`, {
    method: 'POST',
    headers: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
    body: { targetCategoryId },
  })
  return response.data
}

export async function listTags(): Promise<Tag[]> {
  const response = await apiRequest<{ data: Tag[]; meta: PageMeta }>('/api/v1/tags?limit=100')
  return response.data
}

export async function createTag(name: string): Promise<Tag> {
  const response = await apiRequest<{ data: Tag }>('/api/v1/tags', {
    method: 'POST',
    headers: { 'Idempotency-Key': globalThis.crypto.randomUUID() },
    body: { name },
  })
  return response.data
}

export async function patchTag(tagId: string, etag: string, body: { name?: string; status?: 'ACTIVE' | 'INACTIVE' }): Promise<Tag> {
  const response = await apiRequest<{ data: Tag }>(`/api/v1/tags/${tagId}`, {
    method: 'PATCH',
    headers: { 'If-Match': etag, 'Content-Type': 'application/merge-patch+json' },
    body,
  })
  return response.data
}
