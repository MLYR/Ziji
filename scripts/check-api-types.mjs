import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'ziji-api-types-'))
const generatedPath = join(temporaryDirectory, 'ziji-v1.d.ts')
const committedPath = new URL('../packages/api-types/generated/ziji-v1.d.ts', import.meta.url)

try {
  // 生成到临时目录后逐字比较，首个提交前也能检测生成文件漂移。
  const result = spawnSync(
    'pnpm',
    ['exec', 'openapi-typescript', '../../openapi/ziji-v1.yaml', '-o', generatedPath],
    { cwd: new URL('../packages/api-types/', import.meta.url), encoding: 'utf8' },
  )
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || 'OpenAPI 类型生成失败。')
  }

  const [expected, actual] = await Promise.all([
    readFile(committedPath, 'utf8'),
    readFile(generatedPath, 'utf8'),
  ])
  if (expected !== actual) {
    throw new Error('OpenAPI 生成类型已漂移，请运行 pnpm api:generate 并提交结果。')
  }
  console.log('OpenAPI 生成类型与契约一致。')
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true })
}
