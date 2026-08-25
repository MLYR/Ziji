import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";

const eventName = process.env.GITHUB_EVENT_NAME ?? "local";
const outputPath = process.env.GITHUB_OUTPUT;
const baseSha = process.env.GITHUB_BASE_SHA;
const headSha = process.env.GITHUB_SHA ?? "HEAD";

let files;
if (eventName === "workflow_dispatch" || !baseSha) {
  files = ["__manual_run__"];
} else {
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
  file.startsWith("backend/src/main/java/app/ziji/accountmember/") ||
  file.startsWith("backend/src/main/java/app/ziji/sync/") ||
  file.startsWith("backend/src/main/java/app/ziji/audit/") ||
  file.startsWith("backend/src/main/java/app/ziji/statistics/") ||
  file.startsWith("backend/src/main/java/app/ziji/investment/")
);
const values = {
  backend: backend || eventName === "workflow_dispatch",
  backend_deep: backendDeep || eventName === "workflow_dispatch",
  web: web || sharedTypes || eventName === "workflow_dispatch",
  mobile: mobile || sharedTypes || eventName === "workflow_dispatch",
  api: api || eventName === "workflow_dispatch",
};

for (const [key, value] of Object.entries(values)) {
  const line = `${key}=${value ? "true" : "false"}`;
  if (outputPath) appendFileSync(outputPath, `${line}\n`);
  console.log(line);
}
