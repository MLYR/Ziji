import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetWebSessionForTests, setWebSession, setWebUser } from '@/auth/auth-session'
import { CategoriesPage } from '@/categories/CategoriesPage'

vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: vi.fn(),
  }
})

import { apiRequest } from '@/lib/api-client'

const apiRequestMock = vi.mocked(apiRequest)

const categories = [
  { id: 'cat-food', categoryType: 'EXPENSE', name: '餐饮', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
  { id: 'cat-food-china', categoryType: 'EXPENSE', name: '中餐', parentId: 'cat-food', status: 'ACTIVE', mergedIntoId: null, version: 1 },
  { id: 'cat-salary', categoryType: 'INCOME', name: '工资', parentId: null, status: 'INACTIVE', mergedIntoId: null, version: 1 },
]

const tags = [
  { id: 'tag-work', name: '工作', status: 'ACTIVE', version: 1 },
]

function mockBaseline() {
  apiRequestMock.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/categories')) {
      return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
    }
    if (path.startsWith('/api/v1/tags')) {
      return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
    }
    throw new Error(`unexpected path ${path}`)
  })
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/categories']}>
        <QueryClientProvider client={client}>
          <CategoriesPage />
        </QueryClientProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

const user = {
  id: 'user-1',
  email: 'demo@example.com',
  nickname: '演示用户',
  timezone: 'Asia/Shanghai',
  baseCurrency: 'CNY',
  locale: 'zh-CN',
  amountFormat: 'STANDARD',
  status: 'ACTIVE',
  version: 1,
}

describe('分类与标签管理页', () => {
  beforeEach(() => {
    resetWebSessionForTests()
    setWebSession({
      expiresIn: 600,
      accessToken: 'token-1',
      session: { id: 'session-1', deviceName: 'Web 浏览器', deviceId: null, createdAt: '2026-08-26T00:00:00Z', lastSeenAt: '2026-08-26T00:00:00Z', status: 'ACTIVE' },
    })
    setWebUser(user)
    mockBaseline()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    resetWebSessionForTests()
  })

  it('渲染分类列表并展示层级与状态', async () => {
    renderPage()
    expect(await screen.findAllByText('餐饮').then((nodes) => nodes.length >= 2)).toBe(true)
    expect(await screen.findByText('餐饮 / 中餐')).toBeTruthy()
    expect(screen.getByText('工资')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: '停用' }).length).toBeGreaterThanOrEqual(2)
  })

  it('创建支出分类提交 POST /categories', async () => {
    apiRequestMock.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path.startsWith('/api/v1/categories') && init?.method === 'POST') {
        return { data: { id: 'cat-new', categoryType: 'EXPENSE', name: '零食', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 } }
      }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      throw new Error(`unexpected path ${path}`)
    })
    renderPage()
    await screen.findByText('餐饮 / 中餐')
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '零食' } })
    fireEvent.click(screen.getByRole('button', { name: '创建分类' }))
    await waitFor(() => expect(apiRequestMock.mock.calls.some(([path, init]) => path === '/api/v1/categories' && init?.method === 'POST')).toBe(true))
    const postCall = apiRequestMock.mock.calls.find(([path, init]) => path === '/api/v1/categories' && init?.method === 'POST')!
    expect(postCall[1]?.body).toMatchObject({ categoryType: 'EXPENSE', name: '零食', parentId: null, accountId: null })
  })

  it('停用分类提交 PATCH 且携带 If-Match', async () => {
    apiRequestMock.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/categories/cat-salary' && init?.method === 'PATCH') {
        return { data: { ...categories[2], status: 'ACTIVE' } }
      }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      throw new Error(`unexpected path ${path}`)
    })
    renderPage()
    await screen.findByText('工资')
    fireEvent.click(screen.getByRole('button', { name: '启用' }))
    await waitFor(() => expect(apiRequestMock.mock.calls.some(([path, init]) => path === '/api/v1/categories/cat-salary' && init?.method === 'PATCH')).toBe(true))
    const patchCall = apiRequestMock.mock.calls.find(([path, init]) => path === '/api/v1/categories/cat-salary')!
    expect((patchCall[1] as RequestInit).headers).toMatchObject({ 'If-Match': '"1"' })
  })

  it('标签页创建标签并展示列表', async () => {
    apiRequestMock.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/tags' && init?.method === 'POST') {
        return { data: { id: 'tag-home', name: '居家', status: 'ACTIVE', version: 1 } }
      }
      if (path.startsWith('/api/v1/tags')) return { data: tags, meta: { requestId: 'req-tags', nextCursor: null, hasMore: false } }
      if (path.startsWith('/api/v1/categories')) return { data: categories, meta: { requestId: 'req-categories', nextCursor: null, hasMore: false } }
      throw new Error(`unexpected path ${path}`)
    })
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: '标签' }))
    expect(await screen.findByText('工作')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('标签名称'), { target: { value: '居家' } })
    fireEvent.click(screen.getByRole('button', { name: '创建标签' }))
    await waitFor(() => expect(apiRequestMock.mock.calls.some(([path, init]) => path === '/api/v1/tags' && init?.method === 'POST')).toBe(true))
  })
})
