/* 从现有页面与动态映射中提取项目实际使用的 Lucide 图标，并生成独立 SVG 与清单。 */
const fs = require("fs");
const path = require("path");

const lucideBundle = process.argv[2];
if (!lucideBundle) throw new Error("请传入 Lucide 0.468.0 UMD 文件路径。");
const lucide = require(path.resolve(lucideBundle));
const sources = ["index.html", "assets/ziji.js", "assets/mobile.js", "assets/asset-calendar.js"];
const sourceText = Object.fromEntries(sources.map(file => [file, fs.readFileSync(file, "utf8")]));
const names = new Set();

for (const content of Object.values(sourceText)) {
  for (const match of content.matchAll(/data-lucide="([a-z0-9-]+)"/g)) names.add(match[1]);
}

/* 运行时映射需要从函数参数或配置对象中单独识别，不能只依赖静态 data-lucide。 */
for (const match of sourceText["assets/ziji.js"].matchAll(/\b(?:dashboard|accounts|transactions|import|investments|sharing|"asset-calendar"):\s*"([a-z0-9-]+)"/g)) names.add(match[1]);
for (const match of sourceText["assets/ziji.js"].matchAll(/\brow\("[^"]+",\s*"([a-z][a-z0-9-]+)"/g)) names.add(match[1]);
for (const match of sourceText["assets/mobile.js"].matchAll(/\brow\("([a-z0-9-]+)"/g)) names.add(match[1]);
for (const match of sourceText["assets/mobile.js"].matchAll(/\["[^"]+","[^"]+","([a-z0-9-]+)"\]/g)) names.add(match[1]);
for (const match of sourceText["assets/mobile.js"].matchAll(/\b(?:offline|syncing|conflict|rejected):\s*\["([a-z0-9-]+)"/g)) names.add(match[1]);
for (const match of sourceText["assets/asset-calendar.js"].matchAll(/\bicon:\s*"([a-z0-9-]+)"/g)) names.add(match[1]);

/* 主题和条件表达式的分支值无法稳定用单一正则表达式识别，集中登记并由版本校验兜底。 */
["moon", "chart-no-axes-combined", "arrow-left-right", "landmark", "wallet", "triangle-alert", "circle-check"].forEach(name => names.add(name));

const pascal = name => name.split("-").map(part => part.charAt(0).toUpperCase() + part.slice(1)).join("");
const escapeAttribute = value => String(value).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

function serialize(node) {
  const [tag, attrs = {}, children = []] = node;
  const entries = Object.entries(attrs).map(([key, value]) => `${key}="${escapeAttribute(value)}"`).join(" ");
  return `<${tag}${entries ? ` ${entries}` : ""}>${(children || []).map(serialize).join("")}</${tag}>`;
}

function category(name) {
  if (/^(chevron|arrow|search|x$|plus$|columns|layout|list-filter|rotate|undo|log-out)/.test(name)) return "导航与操作";
  if (/(wallet|credit|dollar|landmark|chart|receipt|shopping|utensils|train|archive)/.test(name)) return "金融与账户";
  if (/(alert|check|slash|clock|cloud|wifi|loader|shield|calendar-x|calendar-check|refresh)/.test(name)) return "状态与反馈";
  if (/^(file|copy|download|database|history|book|flask)/.test(name)) return "文件与数据";
  if (/(user|laptop|smartphone|battery|signal|bell|key|settings|sun|moon|message)/.test(name)) return "人员与设备";
  return "通用";
}

const icons = [...names].sort().map(name => {
  const component = pascal(name);
  const definition = lucide.icons[component] || lucide[component];
  if (!definition) throw new Error(`Lucide 0.468.0 中不存在图标：${name} (${component})`);
  return { name, component, category: category(name), definition };
});

const outputDirectory = path.join("assets", "icons");
fs.mkdirSync(outputDirectory, { recursive: true });

for (const icon of icons) {
  const [tag, attrs, children] = icon.definition;
  const root = [tag, { ...attrs, width: 24, height: 24, "stroke-width": 1.7, role: "img" }, children];
  const openEnd = serialize(root).indexOf(">");
  const serialized = serialize(root);
  const svg = `${serialized.slice(0, openEnd + 1)}\n  <title>${icon.name}</title>\n  <!-- 从资迹项目的 Lucide 0.468.0 图标系统提取，保留 24px 视口与 1.7px 圆角描边。 -->${serialized.slice(openEnd + 1)}\n`;
  fs.writeFileSync(path.join(outputDirectory, `${icon.name}.svg`), svg);
}

const manifest = {
  name: "资迹 Ziji 项目图标",
  library: "Lucide",
  version: "0.468.0",
  viewBox: "0 0 24 24",
  strokeWidth: 1.7,
  count: icons.length,
  icons: icons.map(({ name, component, category: group }) => ({ name, component, category: group, file: `assets/icons/${name}.svg` }))
};

fs.writeFileSync(path.join(outputDirectory, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
fs.writeFileSync(path.join(outputDirectory, "manifest.js"), `/* 图标目录运行时清单：与 manifest.json 同源，支持本地文件直接预览。 */\nwindow.ZIJI_ICON_MANIFEST = ${JSON.stringify(manifest, null, 2)};\n`);
console.log(JSON.stringify({ count: icons.length, directory: outputDirectory }));
