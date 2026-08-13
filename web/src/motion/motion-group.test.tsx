import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MotionGroup } from './motion-group'
import { motionTokens } from './tokens'

describe('Motion system', () => {
  it('keeps content readable when animation is unavailable or reduced', () => {
    // 全局测试 matchMedia 不匹配 no-preference，等价验证静态降级路径不会隐藏内容。
    render(<MotionGroup><p data-motion-item>财务数据</p></MotionGroup>)
    expect(screen.getByText('财务数据')).toBeVisible()
    expect(screen.getByText('财务数据')).not.toHaveAttribute('style')
  })

  it('keeps motion durations within the frozen UI range', () => {
    // 统一 Token 防止业务页面自行引入冗长或眩晕的过渡时间。
    expect(motionTokens.duration.fast).toBeGreaterThanOrEqual(0.15)
    expect(motionTokens.duration.deliberate).toBeLessThanOrEqual(0.3)
  })
})
