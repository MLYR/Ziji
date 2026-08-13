import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import App from './App'

describe('应用壳', () => {
  it('展示明确的未加载状态而不是伪造财务数据', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<MemoryRouter initialEntries={['/dashboard']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>)
    expect(screen.getByRole('heading', { name: '总览基础设施已就绪' })).toBeInTheDocument()
    expect(screen.getByText('这不是零余额；在账户和账务模块实现前，页面保持明确的未加载状态。')).toBeInTheDocument()
  })
})
