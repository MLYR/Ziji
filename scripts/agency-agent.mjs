#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const REPO_ROOT = resolve(new URL('..', import.meta.url).pathname)
const INSTALLED_AGENT_DIR = process.env.CODEX_AGENTS_DIR || resolve(process.env.CODEX_HOME || `${process.env.HOME}/.codex`, 'agents')

const ROUTES = [
  {
    role: 'UI Designer',
    keywords: ['ui', '视觉', '界面', '设计 token', 'design token', '颜色', '字体', '间距', '版式', '组件样式', '视觉层级'],
    reviewer: 'UX Architect',
  },
  {
    role: 'UX Architect',
    keywords: ['ux', '用户流程', '信息架构', '导航', '交互模型', '状态机', '可用性', '用户体验'],
    reviewer: 'UI Designer',
  },
  {
    role: 'API Tester',
    keywords: ['openapi', 'api', '契约', 'operationid', 'etag', '幂等', 'problem details'],
    reviewer: 'Code Reviewer',
  },
  {
    role: 'Application Security Engineer',
    keywords: ['安全', '权限', '认证', '越权', '敏感数据', '信息泄漏', 'csrf', 'token', 'secret'],
    reviewer: 'Code Reviewer',
  },
  {
    role: 'Test Automation Engineer',
    keywords: ['测试', '回归', '并发', 'test', 'e2e', 'playwright', 'jest', 'vitest', 'testcontainers'],
    reviewer: 'Code Reviewer',
  },
  {
    role: 'Backend Architect',
    keywords: ['架构', '事务', '模块边界', '数据库访问', '迁移', 'flyway', 'jooq', 'schema', 'seam', 'java', 'sql', '后端', 'application', 'domain', 'adapter', 'controller', 'service'],
    reviewer: 'Code Reviewer',
  },
  {
    role: 'Code Reviewer',
    keywords: ['审查', 'review', '回归风险', '代码质量', '范围检查'],
    reviewer: 'Test Automation Engineer',
  },
  {
    role: 'Project Shepherd',
    keywords: ['项目状态', '任务状态', '依赖', '阻塞', '下一步', '批次', '台账', 'project shepherd'],
    reviewer: null,
  },
]

const ROLE_AGENT_FILES = new Map([
  ['UI Designer', 'ui-designer.toml'],
  ['UX Architect', 'ux-architect.toml'],
  ['API Tester', 'api-tester.toml'],
  ['Application Security Engineer', 'application-security-engineer.toml'],
  ['Test Automation Engineer', 'test-automation-engineer.toml'],
  ['Backend Architect', 'backend-architect.toml'],
  ['Code Reviewer', 'code-reviewer.toml'],
  ['Project Shepherd', 'project-shepherd.toml'],
])

function parseArgs(argv) {
  const options = { run: false, review: false, json: false, model: null, risk: null, task: null }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--') continue
    if (argument === '--run') options.run = true
    else if (argument === '--review') options.review = true
    else if (argument === '--json') options.json = true
    else if (argument === '--task') options.task = argv[++index]
    else if (argument === '--model') options.model = argv[++index]
    else if (argument === '--risk') options.risk = argv[++index]
    else if (argument === '--help' || argument === '-h') options.help = true
    else throw new Error(`未知参数：${argument}`)
  }
  return options
}

function classifyTask(task) {
  const normalized = task.toLowerCase()
  const matches = ROUTES
    .map(route => ({
      ...route,
      score: route.keywords.reduce((score, keyword) => score + (normalized.includes(keyword.toLowerCase()) ? 1 : 0), 0),
    }))
    .filter(route => route.score > 0)
    .sort((left, right) => right.score - left.score)

  if (matches.length === 0) {
    return { primaryRole: 'Project Shepherd', reviewerRole: null, confidence: 'low', matchedKeywords: [] }
  }

  const primary = matches[0]
  const secondary = matches.find(route => route.role !== primary.role && route.role === primary.reviewer)
  return {
    primaryRole: primary.role,
    reviewerRole: secondary?.role || primary.reviewer,
    confidence: primary.score >= 2 ? 'high' : 'medium',
    matchedKeywords: matches.filter(route => route.score === primary.score).flatMap(route => route.keywords.filter(keyword => normalized.includes(keyword.toLowerCase()))),
  }
}

function assertInstalled(role) {
  if (!role) return
  const file = ROLE_AGENT_FILES.get(role)
  if (!file) throw new Error(`角色未配置：${role}`)
  try {
    readFileSync(resolve(INSTALLED_AGENT_DIR, file), 'utf8')
  } catch {
    throw new Error(`角色未安装：${role}（期望文件 ${resolve(INSTALLED_AGENT_DIR, file)}）`)
  }
}

function buildPrompt(task, route, review = false) {
  const role = review ? route.reviewerRole : route.primaryRole
  const purpose = review ? '独立审查下面的任务结果，不能代替实施者修改代码。' : '在授权范围内实施下面的任务，并回传变更范围、验证结果和剩余风险。'
  return [
    `Use the ${role} agent.`,
    '',
    `你是本任务的${review ? '独立验收/审查' : '实施'}角色。${purpose}`,
    '',
    '任务描述：',
    task,
    '',
    '项目约束：',
    '- 先读取项目级 AGENTS.md、任务台账和与改动范围对应的基线。',
    '- 不虚构负责人、任务状态、测试结果或验收证据。',
    '- 不执行 git add、git commit、git push、创建分支或创建 PR。',
    '- 完成后回传实际修改文件、执行命令及结果、未执行验证、剩余风险和未决问题。',
  ].join('\n')
}

function assertCompleteModelId(model) {
  // --run 必须显式携带可传给 Codex 的模型标识，避免把本地默认配置冒充为验收证据。
  if (!model || !/^(?:gpt|o\d)(?:[-.][A-Za-z0-9]+)+$/.test(model) || model.toLowerCase().includes('default')) {
    throw new Error('--run 必须通过 --model 提供完整模型 ID（不能使用默认占位值）。')
  }
}

function runCodex(prompt, model) {
  assertCompleteModelId(model)
  const args = ['exec', '--cd', REPO_ROOT]
  args.push('--model', model)
  args.push(prompt)
  const result = spawnSync('codex', args, { cwd: REPO_ROOT, stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exitCode = result.status || 1
}

function printHelp() {
  console.log(`用法：\n  pnpm agency:route -- --task "任务描述" [--json]\n  pnpm agency:route -- --task "任务描述" --run [--review] --model MODEL\n\n选项：\n  --task TEXT       任务描述；省略时从 stdin 读取\n  --run             调用 codex exec 执行主角色；必须同时提供完整 --model\n  --review          主角色完成后调用建议的独立审查角色\n  --model MODEL    传给 codex exec 的完整模型 ID（--run 必填）\n  --risk LEVEL      仅作为输出标签：high/medium/low\n  --json            输出机器可读 JSON\n`)
}

export function routeTask(task) {
  const classification = classifyTask(task)
  assertInstalled(classification.primaryRole)
  assertInstalled(classification.reviewerRole)
  return {
    task,
    ...classification,
    primaryPrompt: buildPrompt(task, classification),
    reviewerPrompt: classification.reviewerRole ? buildPrompt(task, classification, true) : null,
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  if (options.help) return printHelp()
  const task = options.task || readFileSync(0, 'utf8').trim()
  if (!task) throw new Error('缺少任务描述：使用 --task 或通过 stdin 传入。')
  if (options.run) assertCompleteModelId(options.model)

  const route = routeTask(task)
  const output = {
    ...route,
    risk: options.risk || 'unspecified',
    model: options.model || null,
    run: options.run,
    review: options.review,
  }
  if (options.json) console.log(JSON.stringify(output, null, 2))
  else {
    console.log(`主角色：${route.primaryRole}`)
    console.log(`验收角色：${route.reviewerRole || '无'}`)
    console.log(`置信度：${route.confidence}`)
    console.log(`命中关键词：${route.matchedKeywords.join('、') || '无，需 Project Shepherd 决定'}`)
    console.log('\n--- 主角色 Prompt ---\n')
    console.log(route.primaryPrompt)
    if (route.reviewerPrompt) console.log('\n--- 验收角色 Prompt ---\n\n' + route.reviewerPrompt)
  }

  if (options.run) {
    runCodex(route.primaryPrompt, options.model)
    if (options.review && route.reviewerPrompt && !process.exitCode) runCodex(route.reviewerPrompt, options.model)
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(error => {
    console.error(`agency-agent: ${error.message}`)
    process.exitCode = 2
  })
}
