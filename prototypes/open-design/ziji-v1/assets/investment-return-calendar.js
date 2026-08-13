/* 投资收益日历：同一份演示状态驱动 Web、Mobile、全部投资和单一标的口径。 */
(function () {
  const root = document.getElementById("app");
  if (!root) return;

  const variant = document.body.dataset.returnCalendarVariant || "web-dark";
  const isMobile = variant.startsWith("mobile");
  const initialTheme = document.body.dataset.initialTheme || (variant.includes("light") ? "light" : "dark");
  const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
  const statusMeta = {
    CALCULATED: { label: "已计算", short: "已计算", icon: "circle-check", tone: "" },
    NON_TRADING_DAY: { label: "非交易日", short: "非交易日", icon: "calendar-off", tone: "status-empty" },
    NO_POSITION: { label: "当日无持仓", short: "无持仓", icon: "circle-slash-2", tone: "status-empty" },
    PENDING_DATA: { label: "待数据", short: "等待数据", icon: "clock-3", tone: "status-warning" },
    PARTIAL: { label: "部分标的缺估值", short: "部分估值", icon: "circle-alert", tone: "status-warning" },
    UNPRICED: { label: "无法估值", short: "缺估值", icon: "triangle-alert", tone: "status-error" },
  };

  const state = {
    scope: variant.includes("light") ? "instrument" : "portfolio",
    instrument: "hs300",
    mode: variant.includes("light") ? "rate" : "amount",
    selected: variant.includes("light") ? 14 : 15,
    monthOffset: 0,
  };

  // 演示值仅用于验证 UI 语义；生产值必须完全来自服务端收益日历 API。
  const rows = [
    [1, 842.30, 0.13, "CALCULATED", "基金净值与汇率变化"], [2, -286.40, -0.04, "CALCULATED", "价格下跌与费用"],
    [3, 1260.20, 0.20, "CALCULATED", "ETF 收盘上涨"], [4, null, null, "NON_TRADING_DAY", "周末休市"],
    [5, null, null, "NON_TRADING_DAY", "周末休市"], [6, -2254.20, -0.35, "CALCULATED", "股票与 ETF 下跌"],
    [7, -132.00, -0.02, "CALCULATED", "基金净值小幅回撤"], [8, 0, 0, "CALCULATED", "真实零收益"],
    [9, 486.50, 0.08, "CALCULATED", "基金净值变化"], [10, 1518.80, 0.23, "CALCULATED", "组合上涨"],
    [11, -330.00, -0.05, "CALCULATED", "价格回撤"], [12, null, null, "NON_TRADING_DAY", "周末休市"],
    [13, null, null, "NON_TRADING_DAY", "周末休市"], [14, 118.20, 0.02, "CALCULATED", "外币持仓汇率影响"],
    [15, 6842.60, 1.08, "CALCULATED", "股票与 ETF 上涨"], [16, -465.80, -0.07, "CALCULATED", "收盘价格回撤"],
    [17, 1288.40, 0.20, "CALCULATED", "基金净值变化"], [18, null, null, "NON_TRADING_DAY", "周末休市"],
    [19, null, null, "NON_TRADING_DAY", "周末休市"], [20, 936.22, 0.14, "CALCULATED", "外币资产汇率变化"],
    [21, -412.00, -0.06, "CALCULATED", "持仓价格回撤"], [22, 1200.00, 0.18, "CALCULATED", "ETF 收盘上涨"],
    [23, 256.00, 0.04, "CALCULATED", "基金净值变化"], [24, -4986.35, -0.76, "CALCULATED", "股票与 ETF 下跌"],
    [25, null, null, "NON_TRADING_DAY", "周末休市"], [26, null, null, "NON_TRADING_DAY", "周末休市"],
    [27, null, null, "PARTIAL", "贵州茅台缺少收盘行情"], [28, null, null, "PENDING_DATA", "等待华夏回报基金净值"],
    [29, null, null, "UNPRICED", "USD/CNY 历史汇率缺失"], [30, null, null, "NO_POSITION", "所选标的当日未持有"],
    [31, 82.16, 0.01, "CALCULATED", "券商现金与尾差"],
  ].map(([day, profit, rate, status, note]) => ({ day, profit, rate, status, note }));

  const portfolioContributions = {
    15: [
      ["沪深300ETF", "INSTRUMENT", 4682.20, 0.76, "510300 · 收盘估值"],
      ["贵州茅台", "INSTRUMENT", 1944.60, 0.29, "600519 · 收盘估值"],
      ["华夏回报混合A", "INSTRUMENT", 215.80, 0.03, "基金净值 07月15日"],
      ["券商现金", "BROKER_CASH", 0, 0, "组合内部现金不重复计收益"],
    ],
    14: [["纳指ETF", "INSTRUMENT", 118.20, 0.09, "USD/CNY 汇率影响"]],
  };

  function selectedRow() { return rows.find(item => item.day === state.selected) || rows[0]; }
  function refreshIcons() { window.Ziji?.refreshIcons(); }
  function tone(value) { return value > 0 ? "asset-up" : value < 0 ? "asset-down" : "asset-zero"; }
  function money(value) {
    if (value === null || value === undefined) return "—";
    if (value === 0) return "±¥0.00";
    return `${value > 0 ? "+" : "-"}¥${Math.abs(value).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }
  function rate(value) {
    if (value === null || value === undefined) return "—";
    if (value === 0) return "±0.00%";
    return `${value > 0 ? "+" : "-"}${Math.abs(value).toFixed(2)}%`;
  }
  function valueFor(day) { return state.mode === "amount" ? money(day.profit) : rate(day.rate); }
  function monthLabel() {
    const date = new Date(2026, 6 + state.monthOffset, 1);
    return `${date.getFullYear()}年 ${date.getMonth() + 1}月`;
  }
  function summary() {
    const valid = rows.filter(day => day.status === "CALCULATED");
    const profit = valid.reduce((sum, day) => sum + day.profit, 0);
    const linkedRate = valid.reduce((factor, day) => factor * (1 + day.rate / 100), 1) - 1;
    const complete = !rows.some(day => ["PENDING_DATA", "PARTIAL", "UNPRICED"].includes(day.status));
    return { profit, rate: linkedRate * 100, complete, up: valid.filter(x => x.profit > 0).length, down: valid.filter(x => x.profit < 0).length, zero: valid.filter(x => x.profit === 0).length };
  }
  function scopeName() { return state.scope === "portfolio" ? "全部投资" : "沪深300ETF"; }

  function dayButton(day, mobile) {
    const meta = statusMeta[day.status];
    const selected = day.day === state.selected;
    let shown = day.status === "CALCULATED" ? valueFor(day) : "—";
    if (mobile && day.status === "CALCULATED" && state.mode === "amount") {
      const sign = day.profit === 0 ? "±" : day.profit > 0 ? "+" : "-";
      const absolute = Math.abs(day.profit);
      shown = absolute >= 1000 ? `${sign}¥${(absolute / 1000).toFixed(1)}千` : `${sign}¥${Math.round(absolute)}`;
    }
    const quality = day.status === "CALCULATED" ? (day.profit === 0 ? "完整数据，真实零收益" : "完整数据") : meta.label;
    return `<button class="asset-day ${selected ? "is-selected" : ""}" data-return-day="${day.day}" role="gridcell" tabindex="${selected ? "0" : "-1"}" aria-pressed="${selected}" aria-label="2026年7月${day.day}日，${scopeName()}，${meta.label}，${state.mode === "amount" ? "收益金额" : "Modified Dietz收益率"}${shown}，数据质量${quality}">
      <span class="asset-day-top"><span class="asset-day-number">${day.day}</span><span class="asset-day-markers">${day.status === "CALCULATED" ? "" : `<i data-lucide="${meta.icon}" title="${meta.label}"></i>`}</span></span>
      <strong class="asset-day-value ${day.status === "CALCULATED" ? tone(day.profit) : ""}">${shown}</strong>
      <span class="asset-day-status ${meta.tone}"><span class="asset-day-status-line">${day.status === "CALCULATED" ? "" : `<i data-lucide="${meta.icon}"></i>`}${day.status === "CALCULATED" ? (day.profit === 0 ? "真实零收益" : "已计算") : meta.short}</span></span>
      <span class="asset-day-state">${day.status}</span></button>`;
  }

  function legend() {
    return `<div class="calendar-legend"><span><i class="up-key"></i>收益（+）</span><span><i class="down-key"></i>亏损（-）</span><span><i></i>真实零（±）</span><span class="return-status-key"><i data-lucide="calendar-off"></i>非交易日</span><span class="return-status-key"><i data-lucide="clock-3"></i>待数据</span><span class="return-status-key"><i data-lucide="circle-alert"></i>估值问题</span></div>`;
  }

  function calendar(mobile) {
    if (state.monthOffset !== 0) return `<section class="asset-calendar-shell ${mobile ? "mobile-asset-calendar" : ""}"><div class="calendar-empty-month"><div><i data-lucide="calendar-x"></i><h3>${monthLabel()}没有演示数据</h3><p>返回 2026 年 7 月继续查看收益状态与贡献。</p><button class="btn" data-return-action="today">回到本月</button></div></div></section>`;
    const leading = '<span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">29</span></span><span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">30</span></span>';
    const trailing = '<span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">1</span></span><span class="asset-day is-outside" aria-hidden="true"><span class="asset-day-number">2</span></span>';
    return `<section class="asset-calendar-shell ${mobile ? "mobile-asset-calendar" : ""}" aria-label="2026年7月${scopeName()}收益日历"><div class="asset-calendar-head" aria-hidden="true">${weekdays.map(x => `<span>周${x}</span>`).join("")}</div><div class="asset-calendar-grid" role="grid" aria-label="2026年7月投资收益">${leading}${rows.map(day => dayButton(day, mobile)).join("")}${trailing}</div>${legend()}</section>`;
  }

  function scopeControls(mobile) {
    const toggle = `<div class="calendar-toggle"><button data-return-scope="portfolio" aria-pressed="${state.scope === "portfolio"}">全部投资</button><button data-return-scope="instrument" aria-pressed="${state.scope === "instrument"}">单一标的</button></div>`;
    const instrument = state.scope === "instrument" ? `<div class="return-instrument-select"><label for="return-instrument">选择股票、基金或 ETF</label><select class="select" id="return-instrument" data-return-instrument><option value="hs300">沪深300ETF · 510300</option><option value="moutai">贵州茅台 · 600519</option><option value="fund">华夏回报混合A</option></select></div>` : "";
    if (mobile) return `<div class="return-mobile-scope"><div class="mobile-calendar-tabs" role="group" aria-label="统计范围"><button data-return-scope="portfolio" aria-pressed="${state.scope === "portfolio"}">全部投资</button><button data-return-scope="instrument" aria-pressed="${state.scope === "instrument"}">单一标的</button></div>${instrument}<div class="mobile-calendar-mode-row"><span class="panel-sub">显示收益率</span><div class="mobile-mode-toggle"><button class="mobile-toggle-control" data-return-action="toggle-mode" aria-label="切换收益金额和收益率" aria-pressed="${state.mode === "rate"}"><span class="switch" aria-checked="${state.mode === "rate"}" aria-hidden="true"></span></button><span>${state.mode === "amount" ? "收益金额" : "收益率"}</span></div></div></div>`;
    return `<section class="calendar-toolbar" aria-label="投资收益日历筛选"><div class="return-scope-row"><fieldset><legend>统计范围</legend>${toggle}</fieldset>${instrument}</div><fieldset><legend>展示模式</legend><div class="calendar-toggle"><button data-return-mode="amount" aria-pressed="${state.mode === "amount"}">收益金额</button><button data-return-mode="rate" aria-pressed="${state.mode === "rate"}">收益率</button></div></fieldset><div class="month-navigation"><button class="icon-btn" data-return-action="prev" aria-label="上个月"><i data-lucide="chevron-left"></i></button><strong class="month-label">${monthLabel()}</strong><button class="icon-btn" data-return-action="next" aria-label="下个月"><i data-lucide="chevron-right"></i></button><button class="btn" data-return-action="today">回到本月</button></div><p class="return-help">收益率采用 Modified Dietz；转入转出与买卖本金不计收益，手续费和税费只扣减一次。</p></section>`;
  }

  function summaryBand(mobile) {
    const data = summary();
    // 存在待数据、部分估值或无法估值的应计算日时，月度结果保持为空，不能展示完整日小计冒充整月结果。
    if (mobile) return `<section class="mobile-calendar-summary return-mobile-summary" aria-label="月度收益汇总不完整"><div><span>月度收益</span><strong>—</strong></div><div><span>完整性</span><strong class="status-warning">待处理</strong></div><div><span>异常日期</span><strong>3 天</strong></div></section>`;
    const items = [["月度收益金额", data.complete ? money(data.profit) : "—", data.complete ? tone(data.profit) : "", data.complete ? "完整计算" : "存在未完成估值日期"], ["月度收益率", data.complete ? rate(data.rate) : "—", data.complete ? tone(data.rate) : "", data.complete ? "完整日收益率几何链接" : "不展示不完整月份结果"], ["收益天数", `${data.up} 天`], ["亏损天数", `${data.down} 天`], ["真实零收益", `${data.zero} 天`], ["数据质量", "3 天待处理", "status-warning", "不伪装为完整月份"]];
    return `<section class="calendar-summary return-summary" aria-label="2026年7月投资收益汇总">${items.map((item, index) => `<div class="calendar-summary-item ${index === 0 ? "emphasis" : ""}"><span>${item[0]}</span><strong class="${item[2] || ""}">${item[1]}</strong>${item[3] ? `<small>${item[3]}</small>` : ""}</div>`).join("")}</section>`;
  }

  function contributionRows(day, mobile) {
    const fallback = [[scopeName(), "INSTRUMENT", day.profit, day.rate, day.note]];
    const sources = portfolioContributions[day.day] || fallback;
    if (mobile) return `<div class="mobile-source-list">${sources.map((source, index) => `<button class="mobile-source-row" data-return-drill="holding"><span class="mobile-source-icon"><i data-lucide="chart-no-axes-combined"></i></span><span class="mobile-source-main"><strong>${source[0]}</strong><span>${source[1]} · ${source[4]}</span></span><span class="mobile-source-value ${tone(source[2])}">${money(source[2])}<span>${rate(source[3])}</span></span><i data-lucide="chevron-right"></i></button>`).join("")}</div>`;
    return `<div class="data-table-wrap"><table class="data-table" aria-label="投资收益贡献"><thead><tr><th>贡献来源</th><th>类型</th><th style="text-align:right">收益金额</th><th style="text-align:right">收益率</th><th></th></tr></thead><tbody>${sources.map(source => `<tr data-return-drill="holding" tabindex="0"><td><span class="table-main">${source[0]}</span><span class="table-sub">${source[4]}</span></td><td><span class="source-kind">${source[1]}</span></td><td class="number ${tone(source[2])}">${money(source[2])}</td><td class="number ${tone(source[3])}">${rate(source[3])}</td><td><i data-lucide="chevron-right"></i></td></tr>`).join("")}</tbody></table></div>`;
  }

  function instrumentBreakdown(day) {
    const values = [
      ["日初市值", "¥176,420.00"], ["日终市值", "¥184,620.00"], ["净现金流", "+¥3,517.80"], ["收益金额", money(day.profit)],
      ["价格影响", "+¥4,512.20"], ["汇率影响", "+¥170.00"], ["分红", "±¥0.00"], ["手续费/税费", "-¥0.00"],
    ];
    return `<div class="return-detail-grid">${values.map(item => `<div class="return-detail-cell"><span>${item[0]}</span><strong>${item[1]}</strong></div>`).join("")}</div><p class="return-cashflow-note">买入本金属于标的净现金流，不计入收益；Modified Dietz 分母使用日内现金流剩余权重。</p>`;
  }

  function detail(day, mobile) {
    const meta = statusMeta[day.status];
    const valid = day.status === "CALCULATED";
    const titleId = mobile ? "mobile-return-detail-title" : "return-detail-title";
    return `<section class="${mobile ? "mobile-sheet return-mobile-detail" : "drawer asset-detail-sheet"}" role="dialog" aria-modal="true" aria-labelledby="${titleId}"><div class="dialog-head"><div><h2 id="${titleId}">7月${day.day}日收益明细</h2><p>${scopeName()} · 人民币 CNY · 演示数据</p></div><button class="icon-btn" data-return-close aria-label="关闭收益明细"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="asset-detail-hero"><div class="asset-detail-date">${state.mode === "amount" ? "当日收益" : "Modified Dietz 日收益率"}</div><div class="asset-detail-change ${valid ? tone(day.profit) : ""}">${valid ? valueFor(day) : "暂不提供完整收益"}</div><div class="asset-detail-quality"><i data-lucide="${meta.icon}"></i><span>${meta.label} · ${day.note}</span></div></div>${valid ? (state.scope === "portfolio" ? `<section class="asset-detail-section"><h3>收益贡献 · 合计等于组合日收益</h3>${contributionRows(day, mobile)}</section>` : instrumentBreakdown(day)) : `<div class="notice ${day.status === "UNPRICED" ? "notice-danger" : ""}" style="margin-top:14px"><i data-lucide="${meta.icon}"></i><div><strong>${meta.label}</strong><p>${day.note}。收益金额和收益率均保持为空，不以部分估值或 0 冒充完整结果。</p></div></div>`}<footer class="asset-detail-footer"><span>数据截至 2026年7月${String(day.day).padStart(2, "0")}日 23:59</span><span>估值修订版本 v3 · 最近重算 08月01日 09:12</span></footer></div></section>`;
  }

  function openDetail(dayNumber, focus = true) {
    const day = rows.find(item => item.day === Number(dayNumber));
    if (!day || state.monthOffset !== 0) return;
    state.selected = day.day;
    document.querySelectorAll("[data-return-day]").forEach(button => { const selected = Number(button.dataset.returnDay) === day.day; button.classList.toggle("is-selected", selected); button.setAttribute("aria-pressed", String(selected)); button.tabIndex = selected ? 0 : -1; });
    let overlay = document.getElementById("investment-return-detail-overlay");
    if (!overlay) { overlay = document.createElement("div"); overlay.id = "investment-return-detail-overlay"; document.body.appendChild(overlay); }
    overlay.className = `overlay open ${isMobile ? "mobile-sheet-overlay" : "drawer-overlay"}`;
    overlay.innerHTML = detail(day, isMobile);
    refreshIcons();
    if (focus) overlay.querySelector("[data-return-close]")?.focus();
  }

  function metaStrip() {
    return `<div class="calendar-meta-strip"><span><i data-lucide="circle-dollar-sign"></i>基准币种：人民币 CNY</span><span><i data-lucide="calendar-clock"></i>数据截至：2026年7月31日 23:59</span><span><i data-lucide="refresh-cw"></i>最近重算：2026年8月1日 09:12</span><span><i data-lucide="shield-alert"></i>估值版本 v3 · 3 天待处理</span></div>`;
  }

  function renderWeb() {
    const content = `<div class="asset-calendar-page return-calendar-page"><nav class="calendar-breadcrumbs" aria-label="面包屑"><a href="web-investments.html">投资</a><i data-lucide="chevron-right"></i><span aria-current="page">收益日历</span></nav><header class="calendar-title-row"><div><h2>投资收益日历</h2><p>按自然月查看全部投资或单一股票、基金和 ETF 的每日收益；买卖本金和组合转入转出不计收益。</p></div><div class="calendar-actions"><span class="badge demo-badge">Modified Dietz</span><a class="btn" href="web-investments.html"><i data-lucide="chart-no-axes-combined"></i>返回持仓</a></div></header>${scopeControls(false)}${summaryBand(false)}${metaStrip()}${calendar(false)}</div>`;
    root.innerHTML = window.Ziji?.webShell ? window.Ziji.webShell(content) : content;
    refreshIcons();
  }

  function mobileStatusBar() { return '<div class="mobile-status" aria-label="设备状态"><span>9:41</span><span class="mobile-status-icons"><i data-lucide="signal"></i><i data-lucide="wifi"></i><i data-lucide="battery-full"></i></span></div>'; }
  function renderMobile() {
    root.innerHTML = `<div class="mobile-stage"><main class="phone"><div class="mobile-app mobile-calendar-app">${mobileStatusBar()}<header class="mobile-calendar-header"><a class="icon-btn" href="mobile-investments.html" aria-label="返回投资"><i data-lucide="chevron-left"></i></a><h1>投资收益日历</h1><button class="icon-btn" data-action="theme" aria-label="切换深浅主题"><i data-theme-icon data-lucide="sun"></i></button></header><div class="mobile-calendar-content"><div class="calendar-meta-strip mobile-data-note"><span><i data-lucide="flask-conical"></i>2026 年 7 月 · Modified Dietz</span></div>${scopeControls(true)}<div class="mobile-month-nav"><button class="icon-btn" data-return-action="prev" aria-label="上个月"><i data-lucide="chevron-left"></i></button><strong>${monthLabel()}</strong><button class="icon-btn" data-return-action="next" aria-label="下个月"><i data-lucide="chevron-right"></i></button></div><button class="mobile-today-link" data-return-action="today">回到本月</button>${summaryBand(true)}<p class="mobile-data-note">买卖本金和组合转入转出不计收益；缺估值日期不展示伪造结果。</p>${calendar(true)}<div class="mobile-calendar-meta"><span>数据截至 07月31日 23:59</span><span>最近重算 08月01日 09:12 · 估值版本 v3</span></div></div></div></main></div>`;
    refreshIcons();
  }

  function rerender(reopen = false) {
    if (isMobile) renderMobile(); else renderWeb();
    if (reopen && state.monthOffset === 0) openDetail(state.selected, false);
  }

  document.addEventListener("click", event => {
    const scope = event.target.closest("[data-return-scope]");
    const mode = event.target.closest("[data-return-mode]");
    const day = event.target.closest("[data-return-day]");
    const action = event.target.closest("[data-return-action]")?.dataset.returnAction;
    if (scope) { state.scope = scope.dataset.returnScope; state.selected = state.scope === "portfolio" ? 15 : 14; rerender(true); }
    else if (mode) { state.mode = mode.dataset.returnMode; rerender(true); }
    else if (day) openDetail(day.dataset.returnDay);
    else if (action === "toggle-mode") { state.mode = state.mode === "amount" ? "rate" : "amount"; rerender(true); }
    else if (action === "prev" || action === "next") { state.monthOffset += action === "prev" ? -1 : 1; rerender(false); document.getElementById("investment-return-detail-overlay")?.classList.remove("open"); }
    else if (action === "today") { state.monthOffset = 0; rerender(false); }
    if (event.target.closest("[data-return-close]") || event.target.id === "investment-return-detail-overlay") { document.getElementById("investment-return-detail-overlay")?.classList.remove("open"); document.querySelector(`[data-return-day="${state.selected}"]`)?.focus(); }
    if (event.target.closest("[data-return-drill]")) location.assign(isMobile ? "mobile-investments.html" : "web-investments.html");
  });

  document.addEventListener("keydown", event => {
    const overlay = document.getElementById("investment-return-detail-overlay");
    if (event.key === "Escape" && overlay?.classList.contains("open")) { overlay.classList.remove("open"); document.querySelector(`[data-return-day="${state.selected}"]`)?.focus(); }
    const drill = event.target.closest("[data-return-drill]");
    if (drill && (event.key === "Enter" || event.key === " ")) { event.preventDefault(); drill.click(); return; }
    const current = event.target.closest("[data-return-day]");
    if (!current) return;
    const currentDay = Number(current.dataset.returnDay);
    let nextDay = currentDay;
    if (event.key === "ArrowRight") nextDay += 1;
    else if (event.key === "ArrowLeft") nextDay -= 1;
    else if (event.key === "ArrowDown") nextDay += 7;
    else if (event.key === "ArrowUp") nextDay -= 7;
    else if (event.key === "Home") nextDay -= (currentDay + 1) % 7;
    else if (event.key === "End") nextDay += 6 - ((currentDay + 1) % 7);
    else return;
    const target = document.querySelector(`[data-return-day="${Math.max(1, Math.min(31, nextDay))}"]`);
    if (target) { event.preventDefault(); document.querySelectorAll("[data-return-day]").forEach(button => button.tabIndex = -1); target.tabIndex = 0; target.focus(); }
  });

  window.Ziji?.applyTheme(initialTheme);
  rerender(false);
  openDetail(state.selected, false);
  window.addEventListener("DOMContentLoaded", refreshIcons, { once: true });
})();
