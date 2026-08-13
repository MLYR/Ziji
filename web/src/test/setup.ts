import '@testing-library/jest-dom/vitest'

// jsdom 不实现 matchMedia；此 mock 只提供 Sidebar 响应式检测所需的标准接口。
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})
