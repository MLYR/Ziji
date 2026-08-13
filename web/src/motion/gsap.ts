import { useGSAP } from '@gsap/react'
import gsap from 'gsap'

// 插件只在统一入口注册，业务组件不得各自配置全局 GSAP 状态。
gsap.registerPlugin(useGSAP)

export { gsap, useGSAP }
