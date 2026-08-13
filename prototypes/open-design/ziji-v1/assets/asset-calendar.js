/* 总资产日历共享原型：一份演示数据驱动 Web、Mobile 与完整状态画廊。 */
(function () {
  const root = document.getElementById("app");
  if (!root) return;

  const body = document.body;
  const variant = body.dataset.calendarVariant || "web-dark";
  const isMobile = variant.startsWith("mobile");
  const isStates = variant === "states";
  const initialTheme = body.dataset.initialTheme || (variant.includes("light") ? "light" : "dark");
  const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
  const statusMeta = {
    CALCULATED: { label: "已计算", short: "完整", icon: "circle-check", tone: "" },
    NO_ASSETS: { label: "当前指标范围为空", short: "无计入资产", icon: "circle-slash-2", tone: "status-empty" },
    PENDING_DATA: { label: "待数据", short: "等待数据", icon: "clock-3", tone: "status-warning" },
    PARTIAL: { label: "部分估值", short: "部分估值", icon: "circle-alert", tone: "status-warning" },
    UNAVAILABLE: { label: "不可用", short: "不可用", icon: "triangle-alert", tone: "status-error" },
  };

  // 所有数值均为 2026 年 7 月人民币演示数据；资产与负债分开记录，净资产由两者相减。
  const rawDays = [
    [1, 842.30, 0, "CALCULATED", "基金净值变化"], [2, -286.40, 0, "CALCULATED", "日常消费"],
    [3, 28460, 0, "CALCULATED", "工资收入"], [4, 0, 0, "CALCULATED", "周末真实零变化"],
    [5, 0, 0, "CALCULATED", "周末真实零变化"], [6, -2254.20, 0, "CALCULATED", "股票下跌"],
    [7, -132, 0, "CALCULATED", "日常消费"], [8, 0, 0, "CALCULATED", "内部账户转账"],
    [9, 486.50, 0, "CALCULATED", "基金净值变化"], [10, 50000, 50000, "CALCULATED", "借款到账"],
    [11, -330, 0, "CALCULATED", "日常消费"], [12, 0, 0, "CALCULATED", "周末真实零变化"],
    [13, -7100, -7100, "CALCULATED", "偿还借款本金"], [14, 118.20, 0, "CALCULATED", "汇率变化"],
    [15, 6842.60, 0, "CALCULATED", "股票上涨"], [16, -465.80, 0, "CALCULATED", "日常消费"],
    [17, 1288.40, 0, "CALCULATED", "基金净值变化"], [18, 0, 0, "CALCULATED", "周末真实零变化"],
    [19, 0, 0, "CALCULATED", "周末真实零变化"], [20, 936.22, 0, "CALCULATED", "外币资产汇率变化"],
    [21, -412, 0, "CALCULATED", "日常消费"], [22, 1200, 0, "CALCULATED", "余额调整"],
    [23, 256, 0, "CALCULATED", "基金净值变化"], [24, -4986.35, 0, "CALCULATED", "股票下跌"],
    [25, 0, 0, "CALCULATED", "周末真实零变化"], [26, 0, 0, "CALCULATED", "周末真实零变化"],
    [27, null, null, "PARTIAL", "贵州茅台缺少收盘行情"], [28, null, null, "PENDING_DATA", "等待华夏回报基金净值"],
    [29, null, null, "UNAVAILABLE", "日初快照无法可靠形成"], [30, 0, 0, "CALCULATED", "计入比例将于 8 月生效"],
    [31, 82.16, 0, "CALCULATED", "其他变化"],
  ];

  const sourceMap = {
    3: [{ name: "知行科技有限公司", type: "收入", asset: 28460, debt: 0, drill: "transaction", note: "工资收入" }],
    6: [{ name: "贵州茅台", type: "投资价格变化", asset: -1967.80, debt: 0, drill: "holding", note: "600519 · 收盘估值" }, { name: "盒马鲜生", type: "支出", asset: -286.40, debt: 0, drill: "transaction", note: "日常消费" }],
    8: [{ name: "招商银行 → 家庭日常", type: "账户转账", asset: 0, debt: 0, drill: "account", note: "统计范围内账户转账" }],
    10: [{ name: "微众银行借款", type: "借款本金", asset: 50000, debt: 50000, drill: "account", note: "本金到账，不计收入" }],
    13: [{ name: "微众银行借款", type: "还款本金", asset: -7100, debt: -7100, drill: "account", note: "本金偿还，不计支出" }],
    15: [{ name: "沪深300ETF", type: "投资价格变化", asset: 4682.20, debt: 0, drill: "holding", note: "510300 · 收盘估值" }, { name: "贵州茅台", type: "投资价格变化", asset: 1944.60, debt: 0, drill: "holding", note: "600519 · 收盘估值" }, { name: "华夏回报混合A", type: "基金净值变化", asset: 215.80, debt: 0, drill: "holding", note: "净值日期 07月15日" }],
    17: [{ name: "华夏回报混合A", type: "基金净值变化", asset: 1288.40, debt: 0, drill: "holding", note: "净值日期 07月17日" }],
    20: [{ name: "美元现金", type: "汇率变化", asset: 936.22, debt: 0, drill: "account", note: "USD/CNY 日终汇率" }],
    22: [{ name: "招商银行 8286", type: "余额调整", asset: 1200, debt: 0, drill: "account", note: "对账修正 · 可审计" }],
    24: [{ name: "贵州茅台", type: "投资价格变化", asset: -3520.15, debt: 0, drill: "holding", note: "600519 · 收盘估值" }, { name: "沪深300ETF", type: "投资价格变化", asset: -1466.20, debt: 0, drill: "holding", note: "510300 · 收盘估值" }],
    30: [{ name: "家庭日常", type: "账户计入范围或比例变化", asset: 0, debt: 0, drill: "account", note: "计入比例 60% → 80%，08月01日生效" }],
    31: [{ name: "现金尾差", type: "其他变化", asset: 82.16, debt: 0, drill: null, note: "期末折算尾差" }],
  };

  const state = {
    metric: variant.includes("light") ? "net" : "asset",
    mode: variant === "web-light" ? "rate" : "amount",
    selected: variant === "web-light" ? 10 : variant === "mobile-light" ? 8 : 15,
    monthOffset: 0,
  };

  let runningAsset = 905234.18;
  let runningDebt = 150327.74;
  const days = rawDays.map(([day, asset, debt, status, note]) => {
    const startAsset = runningAsset;
    const startDebt = runningDebt;
    if (status === "CALCULATED") { runningAsset += asset; runningDebt += debt; }
    return { day, asset, debt, net: asset === null ? null : asset - debt, status, note, startAsset, endAsset: status === "CALCULATED" ? runningAsset : null, startDebt, endDebt: status === "CALCULATED" ? runningDebt : null };
  });

  function refreshIcons() { window.Ziji?.refreshIcons(); }
  function money(value, zeroSymbol = true) {
    if (value === null || value === undefined) return "—";
    if (value === 0) return `${zeroSymbol ? "±" : ""}¥0.00`;
    return `${value > 0 ? "+" : "-"}¥${Math.abs(value).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }
  function balance(value) { return `¥${value.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`; }
  function dayValue(day) { return state.metric === "asset" ? day.asset : day.net; }
  function rateValue(day) {
    const value = dayValue(day);
    if (value === null) return null;
    const base = state.metric === "asset" ? day.startAsset : day.startAsset - day.startDebt;
    return value / base * 100;
  }
  function formatDayValue(day) {
    if (day.status !== "CALCULATED") return null;
    if (state.mode === "amount") return money(dayValue(day));
    const value = rateValue(day);
    if (value === 0) return "±0.00%";
    return `${value > 0 ? "+" : "-"}${Math.abs(value).toFixed(2)}%`;
  }
  function tone(value) { return value > 0 ? "asset-up" : value < 0 ? "asset-down" : "asset-zero"; }
  function monthLabel() {
    const date = new Date(2026, 6 + state.monthOffset, 1);
    return `${date.getFullYear()}年 ${date.getMonth() + 1}月`;
  }
  function summary() {
    const valid = days.filter(day => day.status === "CALCULATED");
    const values = valid.map(dayValue);
    const change = values.reduce((sum, value) => sum + value, 0);
    const opening = state.metric === "asset" ? days[0].startAsset : days[0].startAsset - days[0].startDebt;
    return { opening, closing: opening + change, change, rate: change / opening * 100, up: values.filter(x => x > 0).length, down: values.filter(x => x < 0).length, zero: values.filter(x => x === 0).length };
  }

  // 每个日期按钮同时提供可见文字、状态图标与完整读屏描述，不能只依赖颜色。
  function dayButton(day, mobile = false) {
    const meta = statusMeta[day.status];
    const value = formatDayValue(day);
    const raw = dayValue(day);
    // 390px 日历格使用紧凑数值，精确金额仍保留在读屏文本与 Bottom Sheet 中。
    let cellValue = value;
    if (mobile && value && state.mode === "amount") {
      const sign = raw === 0 ? "±" : raw > 0 ? "+" : "-";
      const absolute = Math.abs(raw);
      cellValue = absolute >= 10000 ? `${sign}¥${(absolute / 10000).toFixed(1)}万` : absolute >= 1000 ? `${sign}¥${(absolute / 1000).toFixed(1)}千` : `${sign}¥${Math.round(absolute)}`;
    }
    const validValues = days.filter(item => item.status === "CALCULATED").map(dayValue);
    const maxUp = raw === Math.max(...validValues);
    const maxDown = raw === Math.min(...validValues);
    const selected = day.day === state.selected;
    const isToday = day.day === 31;
    const quality = day.status === "CALCULATED" ? (day.note.includes("周末") ? "完整数据，周末真实零变化" : "完整数据") : meta.label;
    const ariaValue = value || "不展示金额";
    const label = `2026年7月${day.day}日${isToday ? "，演示环境今日" : ""}，${state.metric === "asset" ? "总资产" : "净资产"}变化状态${meta.label}，${state.mode === "amount" ? "变化金额" : "变化率"}${ariaValue}，数据质量${quality}`;
    const classes = ["asset-day", selected ? "is-selected" : "", isToday ? "is-today" : "", maxUp ? "is-max-up" : "", maxDown ? "is-max-down" : ""].filter(Boolean).join(" ");
    const marker = day.status !== "CALCULATED" ? `<i data-lucide="${meta.icon}" title="${meta.label}"></i>` : day.day === 25 ? '<i data-lucide="calendar-check" title="周末真实零变化"></i>' : "";
    return `<button class="${classes}" data-day="${day.day}" data-od-id="calendar-day-${day.day}" role="gridcell" tabindex="${selected ? "0" : "-1"}" aria-label="${label}" aria-pressed="${selected}" ${mobile ? 'data-mobile-day="true"' : ""}>
      <span class="asset-day-top"><span class="asset-day-number">${day.day}</span><span class="asset-day-markers">${marker}</span></span>
      ${cellValue ? `<strong class="asset-day-value ${tone(raw)}">${cellValue}</strong>` : '<strong class="asset-day-value">—</strong>'}
      <span class="asset-day-status ${meta.tone}"><span class="asset-day-status-line">${day.status === "CALCULATED" ? "" : `<i data-lucide="${meta.icon}"></i>`}${day.status === "CALCULATED" ? (raw === 0 ? "真实零变化" : "已计算") : meta.short}</span></span>
      <span class="asset-day-state">${day.status}</span>
    </button>`;
  }

  function legend() {
    return `<div class="calendar-legend" data-od-id="calendar-legend"><span><i class="up-key"></i>资产增加（+）</span><span><i class="down-key"></i>资产减少（-）</span><span><i></i>真实零（±）</span><span><i data-lucide="clock-3"></i>待数据</span><span><i data-lucide="circle-alert"></i>质量问题</span></div>`;
  }

  function calendar(mobile = false) {
    if (state.monthOffset !== 0) return `<section class="asset-calendar-shell ${mobile ? "mobile-asset-calendar" : ""}" data-od-id="asset-calendar-empty"><div class="calendar-empty-month"><div><i data-lucide="calendar-x"></i><h3>${monthLabel()}没有演示数据</h3><p>本原型只提供 2026 年 7 月数据。返回本月可继续查看每日变化与归因。</p><button class="btn" data-action="today">回到本月</button></div></div></section>`;
    const leading = Array.from({ length: 2 }, (_, index) => `<span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">${29 + index}</span></span>`).join("");
    const trailing = Array.from({ length: 2 }, (_, index) => `<span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">${index + 1}</span></span>`).join("");
    return `<section class="asset-calendar-shell ${mobile ? "mobile-asset-calendar" : ""}" aria-label="2026年7月总资产变化日历" data-od-id="asset-calendar-grid">
      <div class="asset-calendar-head" aria-hidden="true">${weekdays.map(x => `<span>周${x}</span>`).join("")}</div>
      <div class="asset-calendar-grid" role="grid" aria-label="2026年7月">${leading}${days.map(day => dayButton(day, mobile)).join("")}${trailing}</div>${legend()}
    </section>`;
  }

  function controls(mobile = false) {
    if (mobile) return `<div class="mobile-calendar-tabs" role="group" aria-label="统计指标" data-od-id="mobile-metric-toggle"><button data-metric="asset" aria-pressed="${state.metric === "asset"}">总资产</button><button data-metric="net" aria-pressed="${state.metric === "net"}">净资产</button></div>
      <div class="mobile-calendar-mode-row"><span class="panel-sub">显示变化率</span><div class="mobile-mode-toggle"><button class="mobile-toggle-control" data-action="toggle-mode" aria-label="切换变化金额和变化率" aria-pressed="${state.mode === "rate"}"><span class="switch" aria-checked="${state.mode === "rate"}" aria-hidden="true"></span></button><span>${state.mode === "amount" ? "变化金额" : "变化率"}</span></div></div>`;
    return `<section class="calendar-toolbar" aria-label="日历筛选" data-od-id="calendar-toolbar">
      <fieldset><legend>统计指标</legend><div class="calendar-toggle">${["asset", "net"].map(key => `<button data-metric="${key}" aria-pressed="${state.metric === key}" ${key === "asset" ? 'data-primary="true"' : ""}>${key === "asset" ? "总资产" : "净资产"}</button>`).join("")}</div></fieldset>
      <fieldset><legend>展示模式</legend><div class="calendar-toggle">${["amount", "rate"].map(key => `<button data-mode="${key}" aria-pressed="${state.mode === key}">${key === "amount" ? "变化金额" : "变化率"}</button>`).join("")}</div></fieldset>
      <div class="month-navigation"><button class="icon-btn" data-action="prev-month" aria-label="上个月"><i data-lucide="chevron-left"></i></button><strong class="month-label">${monthLabel()}</strong><button class="icon-btn" data-action="next-month" aria-label="下个月"><i data-lucide="chevron-right"></i></button><button class="btn" data-action="today">回到本月</button></div>
      <p class="calendar-mode-help">变化率 = 相对当日日初资产规模的变化<br>不是投资回报率</p>
    </section>`;
  }

  function summaryBand(mobile = false) {
    // 无演示数据的相邻月份不沿用 7 月汇总，避免月份切换后产生错误归属。
    if (state.monthOffset !== 0) {
      if (mobile) return `<section class="mobile-calendar-summary" aria-label="${monthLabel()}无演示数据"><div><span>月度变化</span><strong>—</strong></div><div><span>期初值</span><strong>—</strong></div><div><span>期末值</span><strong>—</strong></div></section>`;
      return `<section class="calendar-summary" aria-label="${monthLabel()}无演示数据">${["月初值", "月末值", "月度变化金额", "月度变化率", "增加天数", "减少天数", "零变化天数"].map(label => `<div class="calendar-summary-item"><span>${label}</span><strong>—</strong></div>`).join("")}</section>`;
    }
    const data = summary();
    if (mobile) return `<section class="mobile-calendar-summary" aria-label="月度汇总" data-od-id="mobile-month-summary"><div><span>月度变化</span><strong class="${tone(data.change)}">${state.mode === "amount" ? money(data.change) : `${data.rate >= 0 ? "+" : ""}${data.rate.toFixed(2)}%`}</strong></div><div><span>期初值</span><strong>${balance(data.opening)}</strong></div><div><span>期末值</span><strong>${balance(data.closing)}</strong></div></section>`;
    const items = [["月初值", balance(data.opening)], ["月末值", balance(data.closing)], ["月度变化金额", money(data.change), tone(data.change), "不含未完成估值日期"], ["月度变化率", `${data.rate >= 0 ? "+" : ""}${data.rate.toFixed(2)}%`, tone(data.rate), "相对月初资产规模"], ["增加天数", `${data.up} 天`], ["减少天数", `${data.down} 天`], ["零变化天数", `${data.zero} 天`]];
    return `<section class="calendar-summary" aria-label="2026年7月月度汇总" data-od-id="month-summary">${items.map((item, index) => `<div class="calendar-summary-item ${index === 2 ? "emphasis" : ""}"><span>${item[0]}</span><strong class="${item[2] || ""}">${item[1]}</strong>${item[3] ? `<small>${item[3]}</small>` : ""}</div>`).join("")}</section>`;
  }

  function matrix(day) {
    const rows = [
      ["总资产", day.startAsset, day.endAsset, day.asset],
      ["总负债", day.startDebt, day.endDebt, day.debt],
      ["净资产", day.startAsset - day.startDebt, day.endAsset === null ? null : day.endAsset - day.endDebt, day.net],
    ];
    return `<div class="asset-balance-matrix" data-od-id="daily-balance-matrix"><div class="asset-matrix-cell header">指标</div><div class="asset-matrix-cell header">日初</div><div class="asset-matrix-cell header">日终</div><div class="asset-matrix-cell header">变化</div>${rows.map(row => `<div class="asset-matrix-cell label">${row[0]}</div><div class="asset-matrix-cell"><strong>${row[1] === null ? "—" : balance(row[1])}</strong></div><div class="asset-matrix-cell"><strong>${row[2] === null ? "—" : balance(row[2])}</strong></div><div class="asset-matrix-cell"><strong class="${row[3] === null ? "" : tone(row[3])}">${row[3] === null ? "—" : money(row[3])}</strong></div>`).join("")}</div>`;
  }

  function sourceRows(day, mobile = false) {
    // 每个可计算日期都提供可读归因，避免用户点开普通日期后得到空白面板。
    const fallbackType = day.note.includes("消费") ? "支出" : day.note.includes("股票") ? "投资价格变化" : day.note.includes("基金") ? "基金净值变化" : day.note.includes("汇率") ? "汇率变化" : day.note.includes("转账") ? "账户转账" : "其他变化";
    const fallback = day.status === "CALCULATED" ? [{ name: day.note, type: fallbackType, asset: day.asset, debt: day.debt, drill: dayValue(day) === 0 ? null : fallbackType === "投资价格变化" || fallbackType === "基金净值变化" ? "holding" : fallbackType === "支出" ? "transaction" : "account", note: dayValue(day) === 0 ? "完整计算，无需额外归因" : "当日主要变化来源" }] : [];
    const sources = [...(sourceMap[day.day] || fallback)].sort((a, b) => Math.abs(b.asset - b.debt) - Math.abs(a.asset - a.debt));
    if (mobile) return `<div class="mobile-source-list" data-od-id="mobile-attribution-list">${sources.map((source, index) => `<button class="mobile-source-row" data-drill="${source.drill || ""}" ${source.drill ? "" : "disabled"} data-od-id="mobile-source-${day.day}-${index + 1}"><span class="mobile-source-icon"><i data-lucide="${source.type.includes("投资") || source.type.includes("基金") ? "chart-no-axes-combined" : source.type.includes("转账") ? "arrow-left-right" : source.type.includes("借款") || source.type.includes("还款") ? "landmark" : "wallet"}"></i></span><span class="mobile-source-main"><strong>${source.name}</strong><span>${source.type} · ${source.note}</span></span><span class="mobile-source-value ${tone(source.asset - source.debt)}">${money(source.asset - source.debt)}<span>净资产变化</span></span>${source.drill ? '<i data-lucide="chevron-right"></i>' : ""}</button>`).join("")}</div>`;
    return `<div class="data-table-wrap"><table class="data-table asset-detail-table" aria-label="日期变化归因"><thead><tr><th>来源</th><th>类型</th><th style="text-align:right">资产变化</th><th style="text-align:right">负债变化</th><th style="text-align:right">净资产变化</th><th></th></tr></thead><tbody>${sources.map((source, index) => `<tr data-drill="${source.drill || ""}" tabindex="${source.drill ? "0" : "-1"}" data-od-id="source-${day.day}-${index + 1}"><td><span class="table-main">${source.name}</span><span class="table-sub">${source.note}</span></td><td><span class="source-kind">${source.type}</span></td><td class="number ${tone(source.asset)}">${money(source.asset)}</td><td class="number ${tone(source.debt)}">${money(source.debt)}</td><td class="number ${tone(source.asset - source.debt)}">${money(source.asset - source.debt)}</td><td>${source.drill ? '<i data-lucide="chevron-right"></i>' : ""}</td></tr>`).join("")}</tbody></table></div>`;
  }

  // 无法完整计算的日期只解释原因，不展示已估值小计冒充完整结果。
  function detailContent(day, mobile = false) {
    const meta = statusMeta[day.status];
    const invalid = day.status !== "CALCULATED";
    const value = formatDayValue(day);
    const titleId = mobile ? "mobile-detail-title" : "asset-detail-title";
    const shellClass = mobile ? "mobile-sheet mobile-asset-detail" : "drawer asset-detail-sheet";
    const zeroTransfer = day.day === 8;
    return `<section class="${shellClass}" role="dialog" aria-modal="true" aria-labelledby="${titleId}" data-od-id="date-detail-sheet">
      <div class="dialog-head"><div><h2 id="${titleId}">7月${day.day}日明细</h2><p>2026年 · 人民币 CNY · 演示数据</p></div><button class="icon-btn" data-close-calendar aria-label="关闭日期明细"><i data-lucide="x"></i></button></div>
      <div class="dialog-body"><div class="asset-detail-hero"><div class="asset-detail-date">${state.metric === "asset" ? "总资产" : "净资产"}${state.mode === "amount" ? "变化" : "变化率"}</div><div class="asset-detail-change ${invalid ? "" : tone(dayValue(day))}">${invalid ? "暂不提供完整数值" : value}</div><div class="asset-detail-quality"><i data-lucide="${meta.icon}"></i><span>${meta.label} · ${day.note}</span></div></div>
      ${invalid ? `<div class="notice ${day.status === "UNAVAILABLE" ? "notice-danger" : ""}" style="margin-top:14px"><i data-lucide="${meta.icon}"></i><div><strong>${meta.label}</strong><p>${day.note}。在数据完整前不会显示小计或零值。</p></div></div>` : `${matrix(day)}<section class="asset-detail-section"><h3 data-od-id="attribution-heading">变化归因 · 按影响绝对值排序</h3>${sourceRows(day, mobile)}${zeroTransfer ? '<div class="source-zero-note"><i data-lucide="arrow-left-right"></i><span>统计范围内账户转账，不改变总资产。</span></div>' : ""}</section>`}
      <footer class="asset-detail-footer"><span>数据截至 2026年7月${String(day.day).padStart(2, "0")}日 23:59</span><span>估值修订版本 v3 · 最近重算 08月01日 09:12</span></footer></div>
    </section>`;
  }

  function openDetail(dayNumber, keepFocus = false) {
    const day = days.find(item => item.day === Number(dayNumber));
    if (!day || state.monthOffset !== 0) return;
    state.selected = day.day;
    document.querySelectorAll("[data-day]").forEach(button => { const selected = Number(button.dataset.day) === day.day; button.classList.toggle("is-selected", selected); button.setAttribute("aria-pressed", String(selected)); button.tabIndex = selected ? 0 : -1; });
    let overlay = document.getElementById("asset-calendar-detail-overlay");
    if (!overlay) { overlay = document.createElement("div"); overlay.id = "asset-calendar-detail-overlay"; document.body.appendChild(overlay); }
    overlay.className = `overlay open ${isMobile ? "mobile-sheet-overlay" : "drawer-overlay"}`;
    overlay.innerHTML = detailContent(day, isMobile);
    refreshIcons();
    if (!keepFocus) overlay.querySelector("[data-close-calendar]")?.focus();
  }

  function metaStrip() {
    // 币种采用通用货币符号，数据截止时间使用日历时钟，避免日元徽章与数据库语义误导。
    return `<div class="calendar-meta-strip" data-od-id="calendar-metadata"><span><i data-lucide="circle-dollar-sign"></i>基准币种：人民币 CNY</span>${state.monthOffset === 0 ? '<span><i data-lucide="calendar-clock"></i>数据截至：2026年7月31日 23:59</span><span><i data-lucide="refresh-cw"></i>最近重新计算：2026年8月1日 09:12</span><span><i data-lucide="shield-alert"></i>3 天数据质量待处理</span>' : `<span><i data-lucide="database"></i>${monthLabel()}没有演示数据</span>`}</div>`;
  }

  function renderWeb() {
    const content = `<div class="asset-calendar-page" data-od-id="asset-calendar-page"><nav class="calendar-breadcrumbs" aria-label="面包屑"><a href="web-dashboard.html">Dashboard</a><i data-lucide="chevron-right"></i><a href="web-dashboard.html#total-assets">总资产</a><i data-lucide="chevron-right"></i><span aria-current="page">总资产日历</span></nav>
      <header class="calendar-title-row"><div><h2 data-od-id="calendar-title">总资产日历</h2><p>按自然月查看每天的总资产或净资产变化，并下钻到来源。也可从“统计 → 资产趋势 → 日历视图”进入；所有内容均为 2026 年 7 月演示数据。</p></div><div class="calendar-actions"><span class="badge demo-badge">演示数据</span><a class="btn" href="asset-calendar-states.html"><i data-lucide="layout-grid"></i>查看完整状态</a></div></header>
      ${controls(false)}${summaryBand(false)}${metaStrip()}${calendar(false)}</div>`;
    root.innerHTML = window.Ziji?.webShell ? window.Ziji.webShell(content) : content;
    refreshIcons();
  }

  function mobileStatusBar() { return '<div class="mobile-status" aria-label="设备状态"><span>9:41</span><span class="mobile-status-icons"><i data-lucide="signal"></i><i data-lucide="wifi"></i><i data-lucide="battery-full"></i></span></div>'; }
  function renderMobile() {
    const data = summary();
    root.innerHTML = `<div class="mobile-stage"><main class="phone" data-od-id="mobile-asset-calendar"><div class="mobile-app mobile-calendar-app">${mobileStatusBar()}<header class="mobile-calendar-header"><a class="icon-btn" href="mobile-home.html" aria-label="返回总览"><i data-lucide="chevron-left"></i></a><h1 data-od-id="mobile-calendar-title">总资产日历</h1><button class="icon-btn" data-action="theme" aria-label="切换深浅主题"><i data-theme-icon data-lucide="sun"></i></button></header><div class="mobile-calendar-content">
      <div class="calendar-meta-strip mobile-data-note"><span><i data-lucide="flask-conical"></i>2026 年 7 月 · 人民币演示数据</span></div>${controls(true)}
      <div class="mobile-month-nav"><button class="icon-btn" data-action="prev-month" aria-label="上个月"><i data-lucide="chevron-left"></i></button><strong>${monthLabel()}</strong><button class="icon-btn" data-action="next-month" aria-label="下个月"><i data-lucide="chevron-right"></i></button></div><button class="mobile-today-link" data-action="today">回到本月</button>
      ${summaryBand(true)}<p class="mobile-data-note">${state.mode === "rate" ? "变化率相对当日日初资产规模，不是投资回报率。" : `已计算：增加 ${data.up} 天 · 减少 ${data.down} 天 · 真实零 ${data.zero} 天`}</p>${calendar(true)}<div class="mobile-calendar-meta"><span>数据截至 07月31日 23:59</span><span>最近重算 08月01日 09:12 · 估值版本 v3</span></div></div></div></main></div>`;
    refreshIcons();
  }

  function previewDay(status, day, note) {
    const meta = statusMeta[status];
    if (status === "SKELETON") return '<button class="asset-day is-skeleton" aria-label="日期正在加载"><span class="asset-day-number">00</span><strong class="asset-day-value">加载中</strong><span class="asset-day-status">正在加载</span></button>';
    if (status === "ERROR") return '<div class="error-state-plate"><i data-lucide="cloud-off"></i><strong>接口请求失败</strong><p>无法读取 2026 年 7 月数据</p><button class="btn" data-action="retry">重新加载</button></div>';
    if (status === "INCOMPLETE") return '<div class="error-state-plate"><i data-lucide="calendar-clock"></i><strong>月份数据不完整</strong><p>27—29 日存在待数据或不可用状态</p><a class="btn" href="web-asset-calendar.html">查看日历</a></div>';
    const value = status === "CALCULATED" ? "±¥0.00" : "—";
    return `<button class="asset-day" aria-label="2026年7月${day}日，${meta.label}，${note}"><span class="asset-day-top"><span class="asset-day-number">${day}</span><span class="asset-day-markers"><i data-lucide="${meta.icon}"></i></span></span><strong class="asset-day-value ${status === "CALCULATED" ? "asset-zero" : ""}">${value}</strong><span class="asset-day-status ${meta.tone}"><span class="asset-day-status-line"><i data-lucide="${meta.icon}"></i>${status === "CALCULATED" ? "真实零变化" : meta.short}</span></span><span class="asset-day-state">${status}</span></button>`;
  }

  function renderStates() {
    const cards = [
      ["周末真实零变化", "CALCULATED", 25, "完整计算，变化确实为零。", "周末不会被自动视为无数据。"],
      ["NO_ASSETS 空状态", "NO_ASSETS", 31, "指标范围内没有计入资产。", "净资产仅在资产和负债均未计入时使用。"],
      ["PENDING_DATA 待数据", "PENDING_DATA", 28, "等待基金净值。", "不显示零或临时小计。"],
      ["PARTIAL 部分估值", "PARTIAL", 27, "一项股票缺少行情。", "不以已估值部分冒充完整总资产。"],
      ["UNAVAILABLE 不可用", "UNAVAILABLE", 29, "缺少可靠日初值。", "无法形成可靠变化时隐藏金额。"],
      ["Skeleton 加载", "SKELETON", 0, "正在读取自然月数据。", "保留网格结构，避免布局跳动。"],
      ["接口错误", "ERROR", 0, "月历接口请求失败。", "提供明确原因和重试入口。"],
      ["月份数据不完整", "INCOMPLETE", 0, "部分日期尚未完整计算。", "月度汇总明确排除未完成日期。"],
    ];
    const content = `<div class="asset-calendar-page" data-od-id="calendar-states-page"><nav class="calendar-breadcrumbs"><a href="web-asset-calendar.html">总资产日历</a><i data-lucide="chevron-right"></i><span>完整状态</span></nav><header class="calendar-title-row"><div><h2 data-od-id="states-title">日期与月份状态</h2><p>同尺寸对照所有必需状态。颜色只做辅助，图标、文字和读屏描述共同表达数据质量。</p></div><span class="badge demo-badge">2026 年 7 月演示数据</span></header><section class="calendar-state-grid" data-od-id="state-gallery">${cards.map((card, index) => `<article class="calendar-state-card" data-od-id="state-card-${index + 1}"><header><h3>${card[0]}</h3><p>${card[3]}</p></header><div class="calendar-state-preview">${previewDay(card[1], card[2], card[3])}</div><div class="calendar-state-content">${card[4]}</div></article>`).join("")}</section></div>`;
    root.innerHTML = window.Ziji?.webShell ? window.Ziji.webShell(content) : content;
    refreshIcons();
  }

  function rerender(openSelected = false) {
    if (isStates) renderStates(); else if (isMobile) renderMobile(); else renderWeb();
    if (openSelected && !isStates && state.monthOffset === 0) openDetail(state.selected, false);
  }

  // 事件委托覆盖动态重绘；方向键按自然月网格移动，Home/End 跳到周首/周末。
  document.addEventListener("click", event => {
    const metric = event.target.closest("[data-metric]");
    const mode = event.target.closest("[data-mode]");
    const day = event.target.closest("[data-day]");
    const action = event.target.closest("[data-action]")?.dataset.action;
    if (metric) { state.metric = metric.dataset.metric; rerender(true); }
    else if (mode) { state.mode = mode.dataset.mode; rerender(true); }
    else if (day) openDetail(day.dataset.day);
    else if (action === "toggle-mode") { state.mode = state.mode === "amount" ? "rate" : "amount"; rerender(true); }
    else if (action === "prev-month" || action === "next-month") { state.monthOffset += action === "prev-month" ? -1 : 1; rerender(false); document.getElementById("asset-calendar-detail-overlay")?.classList.remove("open"); }
    else if (action === "today") { state.monthOffset = 0; rerender(false); }
    else if (action === "retry") window.Ziji?.toast("已重新加载", "2026 年 7 月演示数据已恢复。", "success");
    if (event.target.closest("[data-close-calendar]") || event.target.id === "asset-calendar-detail-overlay") { document.getElementById("asset-calendar-detail-overlay")?.classList.remove("open"); document.querySelector(`[data-day="${state.selected}"]`)?.focus(); }
    const drill = event.target.closest("[data-drill]")?.dataset.drill;
    if (drill) location.assign(drill === "holding" ? (isMobile ? "mobile-investments.html" : "web-investments.html") : drill === "transaction" ? (isMobile ? "mobile-transaction-detail.html" : "web-transactions.html") : (isMobile ? "mobile-account-detail.html" : "web-accounts.html"));
  });

  document.addEventListener("keydown", event => {
    const overlay = document.getElementById("asset-calendar-detail-overlay");
    if (event.key === "Escape" && overlay?.classList.contains("open")) { overlay.classList.remove("open"); document.querySelector(`[data-day="${state.selected}"]`)?.focus(); }
    if (event.key === "Tab" && overlay?.classList.contains("open")) {
      const focusable = Array.from(overlay.querySelectorAll('button:not([disabled]),a[href],[tabindex="0"]'));
      if (focusable.length) { const first = focusable[0]; const last = focusable[focusable.length - 1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); } }
    }
    const drillRow = event.target.closest("[data-drill]");
    if (drillRow && (event.key === "Enter" || event.key === " ")) { event.preventDefault(); drillRow.click(); return; }
    const current = event.target.closest("[data-day]");
    if (!current) return;
    const currentDay = Number(current.dataset.day);
    let nextDay = currentDay;
    if (event.key === "ArrowRight") nextDay += 1;
    else if (event.key === "ArrowLeft") nextDay -= 1;
    else if (event.key === "ArrowDown") nextDay += 7;
    else if (event.key === "ArrowUp") nextDay -= 7;
    else if (event.key === "Home") nextDay -= (currentDay + 1) % 7;
    else if (event.key === "End") nextDay += 6 - ((currentDay + 1) % 7);
    else return;
    const target = document.querySelector(`[data-day="${Math.max(1, Math.min(31, nextDay))}"]`);
    if (target) { event.preventDefault(); document.querySelectorAll("[data-day]").forEach(button => button.tabIndex = -1); target.tabIndex = 0; target.focus(); }
  });

  window.Ziji?.applyTheme(initialTheme);
  rerender(false);
  if (!isStates) openDetail(state.selected, false);
  window.addEventListener("DOMContentLoaded", refreshIcons, { once: true });
})();
