import { access, constants, mkdtemp, rm, stat, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const defaultRevisionFile = join(repositoryRoot, 'openapi/ziji-v1.yaml')
const oasdiffImage =
  'tufin/oasdiff:v1.28.0@sha256:86830f988eaafcf589acb2794ee5ab78e3300ded071d6517bf085469300cbf36'
const usage = `用法：
  pnpm api:breaking -- --base-file <OpenAPI 文件>
  pnpm api:breaking -- --base-ref <Git 提交、分支或相对提交引用>

可选参数：
  --revision-file <OpenAPI 文件>  指定待检查契约，默认是 openapi/ziji-v1.yaml。
`

function fail(message) {
  throw new Error(`${message}\n\n${usage}`)
}

function parseArguments(argumentsList) {
  const options = {
    baseFile: undefined,
    baseRef: undefined,
    revisionFile: defaultRevisionFile,
  }

  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    // pnpm 会把脚本命令分隔符一并传给 Node，忽略这个唯一的裸 `--`。
    if (argument === '--') continue
    if (argument === '--help') {
      console.log(usage)
      process.exit(0)
    }

    if (argument === '--base-file' || argument === '--base-ref' || argument === '--revision-file') {
      const value = argumentsList[index + 1]
      if (!value || value.startsWith('--')) {
        fail(`${argument} 必须提供值。`)
      }
      index += 1
      if (argument === '--base-file') options.baseFile = value
      if (argument === '--base-ref') options.baseRef = value
      if (argument === '--revision-file') options.revisionFile = value
      continue
    }

    fail(`未知参数：${argument}`)
  }

  if (!options.baseFile && !options.baseRef) {
    fail('必须提供 --base-file 或 --base-ref。')
  }

  if (options.baseFile && options.baseRef) {
    fail('--base-file 与 --base-ref 只能二选一。')
  }

  return options
}

async function assertFile(file, label) {
  try {
    const fileStats = await stat(file)
    if (!fileStats.isFile()) throw new Error('不是普通文件')
    await access(file, constants.R_OK)
  } catch (error) {
    throw new Error(`${label}不可读：${file}（${error.message}）`)
  }
}

function resolveCommit(reference) {
  // 先限制为 commit-ish 字符，随后用参数数组调用 Git，避免把分支名拼进 shell。
  if (!/^[A-Za-z0-9._~^\/-]+$/.test(reference) || reference.startsWith('-')) {
    throw new Error(`Git 基线引用包含不允许的字符：${reference}`)
  }

  const result = spawnSync('git', ['rev-parse', '--verify', `${reference}^{commit}`], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  if (result.error) throw new Error(`无法执行 Git：${result.error.message}`)
  if (result.status !== 0) {
    throw new Error(`无法取得 Git 基线提交 ${reference}；请确认提交已存在于本地历史。`)
  }

  const commit = result.stdout.trim()
  if (!/^[0-9a-f]{40}$/.test(commit)) {
    throw new Error(`Git 未返回有效的 40 位基线提交 SHA：${commit}`)
  }
  return commit
}

async function materializeGitBaseline(reference, temporaryDirectory) {
  const commit = resolveCommit(reference)
  const result = spawnSync('git', ['show', `${commit}:openapi/ziji-v1.yaml`], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 20 * 1024 * 1024,
  })
  if (result.error) throw new Error(`无法读取 Git 基线契约：${result.error.message}`)
  if (result.status !== 0 || !result.stdout) {
    throw new Error(`提交 ${commit} 中不存在可读取的 openapi/ziji-v1.yaml。`)
  }

  const baselineFile = join(temporaryDirectory, 'base-openapi.yaml')
  // 从提交内容生成临时只读输入，不修改工作区中的正式契约。
  await writeFile(baselineFile, result.stdout, 'utf8')
  return { baselineFile, commit }
}

function runOasdiff(baselineFile, revisionFile) {
  // 禁用网络并以只读卷挂载，确保检查只比较两个本地契约且不会加载外部引用。
  const result = spawnSync(
    'docker',
    [
      'run',
      '--rm',
      '--pull=missing',
      '--network=none',
      '-v',
      `${baselineFile}:/spec/base-openapi.yaml:ro`,
      '-v',
      `${revisionFile}:/spec/revision-openapi.yaml:ro`,
      oasdiffImage,
      'breaking',
      '/spec/base-openapi.yaml',
      '/spec/revision-openapi.yaml',
      '--allow-external-refs=false',
      '--color=never',
      '--fail-on=WARN',
    ],
    { cwd: repositoryRoot, stdio: 'inherit' },
  )
  if (result.error) throw new Error(`无法执行 Docker：${result.error.message}`)
  if (result.status === null) throw new Error('Docker 进程未返回退出码。')
  return result.status
}

const options = parseArguments(process.argv.slice(2))
let temporaryDirectory

try {
  const revisionFile = resolve(process.cwd(), options.revisionFile)
  await assertFile(revisionFile, '当前契约')

  let baselineFile
  let baselineDescription
  if (options.baseFile) {
    baselineFile = resolve(process.cwd(), options.baseFile)
    await assertFile(baselineFile, '基线契约')
    baselineDescription = baselineFile
  } else {
    // Git 基线只落到系统临时目录，检查结束后无条件清理。
    temporaryDirectory = await mkdtemp(join(tmpdir(), 'ziji-openapi-breaking-'))
    const materialized = await materializeGitBaseline(options.baseRef, temporaryDirectory)
    baselineFile = materialized.baselineFile
    baselineDescription = `${options.baseRef} (${materialized.commit})`
  }

  console.log(`OpenAPI breaking 检查：${baselineDescription} -> ${revisionFile}`)
  console.log(`oasdiff：v1.28.0（${oasdiffImage}）`)
  process.exitCode = runOasdiff(baselineFile, revisionFile)
} catch (error) {
  console.error(`OpenAPI breaking 检查失败：${error.message}`)
  process.exitCode = 2
} finally {
  if (temporaryDirectory) {
    await rm(temporaryDirectory, { recursive: true, force: true })
  }
}
