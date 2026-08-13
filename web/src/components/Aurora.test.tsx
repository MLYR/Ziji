import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import Aurora from './Aurora'

const oglState = vi.hoisted(() => ({ shouldFail: false }))
let intersectionCallback: IntersectionObserverCallback

vi.mock('ogl', () => {
  class Renderer {
    gl: Record<string, unknown>

    constructor() {
      if (oglState.shouldFail) throw new Error('WebGL unavailable')
      const canvas = document.createElement('canvas')
      this.gl = {
        BLEND: 1,
        ONE: 1,
        ONE_MINUS_SRC_ALPHA: 1,
        blendFunc: vi.fn(),
        canvas,
        clearColor: vi.fn(),
        enable: vi.fn(),
        getExtension: vi.fn(() => ({ loseContext: vi.fn() })),
      }
    }

    render = vi.fn()
    setSize = vi.fn()
  }

  class Program {
    uniforms: Record<string, { value: unknown }>

    constructor(_gl: unknown, options: { uniforms: Record<string, { value: unknown }> }) {
      this.uniforms = options.uniforms
    }
  }

  return {
    Color: class {
      r = 1
      g = 1
      b = 1
    },
    Mesh: class {},
    Program,
    Renderer,
    Triangle: class { attributes = { uv: {} } },
  }
})

class IntersectionObserverMock {
  constructor(callback: IntersectionObserverCallback) {
    intersectionCallback = callback
  }

  disconnect = vi.fn()
  observe = vi.fn()
  unobserve = vi.fn()
}

class ResizeObserverMock {
  disconnect = vi.fn()
  observe = vi.fn()
  unobserve = vi.fn()
}

describe('Aurora', () => {
  beforeEach(() => {
    oglState.shouldFail = false
    vi.stubGlobal('IntersectionObserver', IntersectionObserverMock)
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(7)
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('is decorative and keeps a static fallback when motion is reduced', () => {
    // reduced-motion 时不创建 WebGL canvas，内容层仍由 CSS fallback 提供视觉。
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
      addEventListener: vi.fn(),
      addListener: vi.fn(),
      dispatchEvent: vi.fn(),
      matches: query.includes('reduce'),
      media: query,
      onchange: null,
      removeEventListener: vi.fn(),
      removeListener: vi.fn(),
    }))

    const { container } = render(<Aurora />)
    const background = container.firstElementChild
    expect(background).toHaveAttribute('aria-hidden', 'true')
    expect(background).toHaveClass('pointer-events-none', 'ziji-aurora-fallback')
    expect(container.querySelector('canvas')).not.toBeInTheDocument()
  })

  it('adds an animated canvas and releases its frame on unmount', () => {
    // 正常路径必须保留装饰语义，并在卸载时取消 RAF。
    const { container, unmount } = render(<Aurora />)
    expect(container.querySelector('[data-animated="true"] canvas')).toHaveAttribute('aria-hidden', 'true')
    unmount()
    expect(window.cancelAnimationFrame).toHaveBeenCalledWith(7)
  })

  it('pauses rendering when the background leaves the viewport', () => {
    // IntersectionObserver 是数据密集页面之外仍需遵守的 GPU 资源门禁。
    render(<Aurora />)
    intersectionCallback([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver)
    expect(window.cancelAnimationFrame).toHaveBeenCalledWith(7)
  })

  it('falls back without throwing when WebGL initialization fails', () => {
    // 无 WebGL 的旧设备或浏览器策略失败不能阻断登录页等核心内容。
    oglState.shouldFail = true
    const { container } = render(<Aurora />)
    expect(container.querySelector('[data-animated="false"]')).toHaveClass('ziji-aurora-fallback')
    expect(container.querySelector('canvas')).not.toBeInTheDocument()
  })
})
