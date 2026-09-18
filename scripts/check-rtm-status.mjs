#!/usr/bin/env node
// RTM 与任务台账对账（机械检查，不替代人工验收结论）。
//
// 检查三类失衡并输出清单，任一失衡以非零码退出，供批次门禁调用：
//   1. 需求状态回退：RTM 状态列中「已设计」出现在「已实现/已验收」之后（取最后出现为当前状态）。
//   2. 已实现悬置：当前状态为「已实现」的需求；配合 --stale-days 与 --since 标记超期项。
//      背景：「已实现」到「已验收」需要独立证据链（见 doc/B3验收收口核对.md），悬置清单供批次收口逐条核销。
//   3. 活动任务未登记：doc/status/BOARD.md 活动区出现 IN_PROGRESS/BLOCKED/REVIEW/VERIFYING/READY
//      状态，但执行面板/说明之外的异常；当前实现为统计并回显活动任务数，供人工比对。
//
// 用法：
//   node scripts/check-rtm-status.mjs                 # 对账并输出清单
//   node scripts/check-rtm-status.mjs --stale-days 14 # 已实现悬置超过 14 天视为失衡（需 --since）
//   node scripts/check-rtm-status.mjs --since 2026-09-07
// 退出码：0 = 无失衡；1 = 存在失衡；2 = 输入文件缺失或不可解析（fail closed）。

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const RTM_PATH = join(root, "doc", "需求追踪矩阵.md");
const BOARD_PATH = join(root, "doc", "status", "BOARD.md");

const args = process.argv.slice(2);
const argValue = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const staleDays = argValue("--stale-days") ? Number(argValue("--stale-days")) : null;
const since = argValue("--since"); // 批次基线日期，YYYY-MM-DD
if (staleDays !== null && (!Number.isFinite(staleDays) || staleDays < 0)) {
  console.error(`--stale-days 非法：${argValue("--stale-days")}`);
  process.exit(2);
}
if (staleDays !== null && !since) {
  console.error("--stale-days 需要同时提供 --since <批次基线日期>");
  process.exit(2);
}

const STATUS_ORDER = ["已确认", "已设计", "已实现", "已验收", "已阻塞", "已废弃"];
const REQUIREMENT_ID = /^[A-Z]{2,5}-\d{3}$/;

function failClosed(message) {
  console.error(`check-rtm-status: ${message}`);
  process.exit(2);
}

function readRequired(path) {
  try {
    return readFileSync(path, "utf8");
  } catch {
    failClosed(`无法读取 ${path}`);
  }
}

// 取状态单元格：该行最后一个命中的状态词为当前状态；状态顺序倒置记为回退。
function parseRtm(markdown) {
  const rows = [];
  let inMatrix = false;
  for (const [lineNo, line] of markdown.split("\n").entries()) {
    if (line.startsWith("## 3.")) inMatrix = true;
    if (!inMatrix || !line.trim().startsWith("|")) continue;
    const cells = line.split("|").slice(1, -1).map((c) => c.trim());
    if (cells.length < 2 || !REQUIREMENT_ID.test(cells[0])) continue;
    const statusCell = cells[cells.length - 1];
    const hits = STATUS_ORDER.filter((s) => statusCell.includes(s));
    if (hits.length === 0) continue;
    const current = hits[hits.length - 1];
    const indices = hits.map((s) => STATUS_ORDER.indexOf(s));
    const regressed = indices.some((v, i) => i > 0 && v < indices[i - 1]);
    rows.push({ id: cells[0], line: lineNo + 1, hits, current, regressed });
  }
  if (rows.length === 0) failClosed("RTM 中未解析到任何需求行，格式可能已变化");
  return rows;
}

function parseBoard(markdown) {
  const active = [];
  for (const [lineNo, line] of markdown.split("\n").entries()) {
    if (!line.trim().startsWith("|")) continue;
    const cells = line.split("|").slice(1, -1).map((c) => c.trim().replace(/`/g, ""));
    if (cells.length < 4) continue;
    const status = cells.find((c) =>
      ["IN_PROGRESS", "BLOCKED", "REVIEW", "VERIFYING", "READY"].includes(c),
    );
    if (status && /^[A-Z]{2,4}-[A-Z0-9]{2,4}-\d{3}$/.test(cells[0])) {
      active.push({ id: cells[0], status, line: lineNo + 1 });
    }
  }
  return active;
}

const rtm = parseRtm(readRequired(RTM_PATH));
// 同一任务可能同时出现在「活动区」与「按 EPIC 分组」两处，按 ID+状态去重。
const board = parseBoard(readRequired(BOARD_PATH)).filter(
  (t, i, all) => all.findIndex((o) => o.id === t.id && o.status === t.status) === i,
);

const counts = {};
for (const row of rtm) counts[row.current] = (counts[row.current] ?? 0) + 1;

const problems = [];

const regressed = rtm.filter((r) => r.regressed);
if (regressed.length > 0) {
  problems.push("状态回退（状态列中较早状态出现在较晚状态之后）：");
  for (const r of regressed) problems.push(`  ${r.id}（RTM 第 ${r.line} 行）：${r.hits.join(" → ")}`);
}

const implemented = rtm.filter((r) => r.current === "已实现");
let staleNote = "";
if (staleDays !== null && since) {
  const ageMs = Date.now() - Date.parse(`${since}T00:00:00+08:00`);
  const ageDays = Math.floor(ageMs / 86_400_000);
  staleNote = `（距批次基线 ${since} 已 ${ageDays} 天，阈值 ${staleDays} 天）`;
  if (implemented.length > 0 && ageDays > staleDays) {
    problems.push(`已实现悬置超期${staleNote}：`);
    for (const r of implemented) problems.push(`  ${r.id}（RTM 第 ${r.line} 行）`);
  }
}

console.log("== RTM 状态分布（当前状态） ==");
for (const s of STATUS_ORDER) if (counts[s]) console.log(`  ${s}: ${counts[s]}`);
console.log(`  合计: ${rtm.length}`);

console.log("\n== 已实现待验收清单 ==");
if (implemented.length === 0) console.log("  （无）");
else for (const r of implemented) console.log(`  ${r.id}（RTM 第 ${r.line} 行）`);

console.log("\n== BOARD 活动任务（非 DONE/BACKLOG） ==");
if (board.length === 0) console.log("  （无）");
else for (const t of board) console.log(`  ${t.id} [${t.status}]（BOARD 第 ${t.line} 行）`);

if (problems.length > 0) {
  console.error("\n== 失衡项 ==");
  for (const p of problems) console.error(p);
  process.exit(1);
}
console.log("\n对账通过：无状态回退" + (staleDays !== null ? `，无超期悬置${staleNote}` : "（未启用超期阈值）"));
