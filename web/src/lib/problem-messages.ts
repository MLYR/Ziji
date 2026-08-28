import { ApiClientError } from '@/lib/api-client'

/** 把服务端 Problem 的错误码映射为可读文案；不回显内部信息。 */
export function describeProblem(error: unknown): string {
  if (error instanceof ApiClientError) {
    const problem = error.problem
    if (problem.code === 'IDEMPOTENCY_KEY_REUSED') {
      return '该幂等键已被其他内容使用，请刷新表单后重试。'
    }
    if (problem.code === 'VERSION_CONFLICT') {
      return '内容已被其他设备修改，请刷新后重试。'
    }
    const detail = problem.detail ?? problem.title ?? '请求失败'
    if (problem.code === 'VALIDATION_ERROR' || problem.code === 'BUSINESS_RULE_VIOLATION') {
      return `${detail}（${problem.code === 'VALIDATION_ERROR' ? '参数校验' : '业务规则'}）`
    }
    return detail
  }
  return '网络或服务暂时不可用，请稍后重试。'
}
