export const motionTokens = {
  duration: {
    instant: 0,
    fast: 0.16,
    standard: 0.22,
    deliberate: 0.3,
  },
  distance: {
    subtle: 6,
    standard: 12,
    emphasized: 20,
  },
  stagger: {
    compact: 0.04,
    standard: 0.06,
  },
  ease: {
    enter: 'power2.out',
    exit: 'power1.in',
    move: 'power2.inOut',
  },
} as const

export type MotionDuration = keyof typeof motionTokens.duration
export type MotionDistance = keyof typeof motionTokens.distance
export type MotionStagger = keyof typeof motionTokens.stagger
