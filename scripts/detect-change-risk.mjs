import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";

const eventName = process.env.GITHUB_EVENT_NAME ?? "local";
const outputPath = process.env.GITHUB_OUTPUT;
const baseSha = process.env.GITHUB_BASE_SHA;
const headSha = process.env.GITHUB_SHA;
const manualRun = eventName === "workflow_dispatch";
const commitShaPattern = /^[0-9a-f]{40}$/;

function requireCommit(label, revision) {
  try {
    execFileSync("git", ["cat-file", "-e", `${revision}^{commit}`], { stdio: "ignore" });
  } catch {
    throw new Error(`${label} 无法解析为 Git commit：${revision}`);
  }
}

let files;
if (manualRun) {
  files = ["__manual_run__"];
} else {
  // 非手动 PR 必须以可解析的 base/head 计算差异；缺失或伪造 ref 不能降级成低风险。
  if (eventName !== "pull_request") {
    throw new Error(`不支持的 GitHub 事件：${eventName}`);
  }
  if (!commitShaPattern.test(baseSha ?? "")) {
    throw new Error(`GITHUB_BASE_SHA 不是完整 commit SHA：${baseSha ?? "<missing>"}`);
  }
  if (!commitShaPattern.test(headSha ?? "")) {
    throw new Error(`GITHUB_SHA 不是完整 commit SHA：${headSha ?? "<missing>"}`);
  }
  requireCommit("GITHUB_BASE_SHA", baseSha);
  requireCommit("GITHUB_SHA", headSha);
  const diff = execFileSync("git", ["diff", "--name-only", `${baseSha}...${headSha}`], {
    encoding: "utf8",
  });
  files = diff.split("\n").filter(Boolean);
}

const has = (predicate) => files.some(predicate);
const backend = has((file) => file.startsWith("backend/"));
const web = has((file) => file.startsWith("web/"));
const mobile = has((file) => file.startsWith("mobile/"));
const sharedTypes = has((file) => file.startsWith("packages/api-types/") || file === "pnpm-lock.yaml");
const api = has((file) =>
  file.startsWith("openapi/") ||
  file.startsWith("packages/api-types/") ||
  file.includes("/interfaces/") ||
  file.includes("Controller") ||
  file.includes("Dto") ||
  file.includes("DTO") ||
  file.startsWith("scripts/check-openapi")
);
const migration = has((file) =>
  file.startsWith("backend/src/main/resources/db/migration/") ||
  file === "doc/数据库设计.md"
);
const backendDeep = migration || has((file) =>
  file.startsWith("backend/src/main/java/app/ziji/ledger/") ||
  file.startsWith("backend/src/main/java/app/ziji/auth/") ||
  file.startsWith("backend/src/main/java/app/ziji/account/") ||
  file.startsWith("backend/src/main/java/app/ziji/accountmember/") ||
  file.startsWith("backend/src/main/java/app/ziji/sync/") ||
  file.startsWith("backend/src/main/java/app/ziji/audit/") ||
  file.startsWith("backend/src/main/java/app/ziji/statistics/") ||
  file.startsWith("backend/src/main/java/app/ziji/investment/") ||
  file.startsWith("backend/src/test/")
);
// 供应链输入变化必须进入 all 扫描；普通业务源码仍可只做 secrets 快检。
const securityDeep = manualRun || has((file) =>
  file === "package.json" ||
  file.endsWith("/package.json") ||
  file === "pnpm-lock.yaml" ||
  file === "pnpm-workspace.yaml" ||
  file === ".npmrc" ||
  file === ".pnpmfile.cjs" ||
  file === "pnpmfile.cjs" ||
  file === "backend/pom.xml" ||
  file === "backend/mvnw" ||
  file === "backend/mvnw.cmd" ||
  file.startsWith("backend/.mvn/") ||
  file.startsWith("security/") ||
  file === "scripts/detect-change-risk.mjs" ||
  file === "scripts/security-scan.sh" ||
  file === "scripts/check-licenses.mjs" ||
  file === "trivy.yaml" ||
  file === "trivy-secret.yaml" ||
  file === ".trivyignore" ||
  file === ".trivyignore.yaml" ||
  file.startsWith(".github/workflows/")
);
// 迁移及数据库设计基线属于后端变更，不能只产生 deep 标记而跳过 Backend job。
const values = {
  backend: backend || migration || manualRun,
  backend_deep: backendDeep || manualRun,
  web: web || sharedTypes || manualRun,
  mobile: mobile || sharedTypes || manualRun,
  api: api || manualRun,
  migration,
  security_deep: securityDeep,
};

for (const [key, value] of Object.entries(values)) {
  const line = `${key}=${value ? "true" : "false"}`;
  if (outputPath) appendFileSync(outputPath, `${line}\n`);
  console.log(line);
}
