import { readFile } from 'node:fs/promises'

const contractUrl = new URL('../openapi/ziji-v1.yaml', import.meta.url)
const contract = await readFile(contractUrl, 'utf8')

// 轻量检查覆盖项目幂等作用域依赖的 operationId，不替代 Redocly 的结构校验。
const operationIds = [...contract.matchAll(/^\s+operationId:\s*([A-Za-z][A-Za-z0-9]*)\s*$/gm)].map(
  ([, operationId]) => operationId,
)
const uniqueOperationIds = new Set(operationIds)

if (operationIds.length === 0) {
  throw new Error('OpenAPI 未声明 operationId。')
}

if (uniqueOperationIds.size !== operationIds.length) {
  const duplicates = operationIds.filter(
    (operationId, index) => operationIds.indexOf(operationId) !== index,
  )
  throw new Error(`OpenAPI 存在重复 operationId：${[...new Set(duplicates)].join(', ')}`)
}

if (!/^openapi:\s*3\.1\./m.test(contract)) {
  throw new Error('OpenAPI 契约必须保持在 3.1.x。')
}

console.log(`OpenAPI 基础检查通过：${operationIds.length} 个唯一 operationId。`)
