import type { ComponentPropsWithoutRef, ReactNode } from 'react'
import { useRef } from 'react'

import { gsap, useGSAP } from './gsap'
import { motionTokens } from './tokens'
import type { MotionDistance, MotionDuration, MotionStagger } from './tokens'

interface MotionGroupProps extends ComponentPropsWithoutRef<'div'> {
  children: ReactNode
  distance?: MotionDistance
  duration?: MotionDuration
  stagger?: MotionStagger
}

export function MotionGroup({
  children,
  className,
  distance = 'standard',
  duration = 'standard',
  stagger = 'standard',
  ...props
}: MotionGroupProps) {
  const scope = useRef<HTMLDivElement>(null)

  useGSAP(() => {
    const media = gsap.matchMedia()

    // 用户允许动画时才创建 tween；reduced-motion 与不支持 matchMedia 时直接保留静态内容。
    media.add('(prefers-reduced-motion: no-preference)', () => {
      gsap.fromTo(
        '[data-motion-item]',
        { autoAlpha: 0, y: motionTokens.distance[distance] },
        {
          autoAlpha: 1,
          y: 0,
          duration: motionTokens.duration[duration],
          ease: motionTokens.ease.enter,
          stagger: motionTokens.stagger[stagger],
          clearProps: 'transform,opacity,visibility',
        },
      )
    })

    return () => media.revert()
  }, { scope, dependencies: [distance, duration, stagger], revertOnUpdate: true })

  return <div ref={scope} className={className} data-motion-scope="entrance" {...props}>{children}</div>
}
