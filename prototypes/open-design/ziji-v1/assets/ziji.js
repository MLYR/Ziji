/* 资迹共享交互层：原型数据均明确标记为演示数据，不接入真实账户。 */
(function () {
  const html = document.documentElement;
  const page = document.body.dataset.page || "launcher";
  const pageNames = {
    dashboard: ["总览", "更新于 08月12日 22:46"],
    accounts: ["账户", "个人与家庭资金账户"],
    transactions: ["流水", "全部交易与审计记录"],
    import: ["账单导入", "CSV / Excel 批次处理"],
    investments: ["投资", "Tushare Pro 行情数据"],
    sharing: ["共享与设置", "成员、统计与安全"],
    // 总资产日历是总览与统计趋势的下钻入口，沿用同一侧栏层级。
    "asset-calendar": ["总资产日历", "2026 年 7 月 · 人民币演示数据"],
  };
  const icons = {
    dashboard: "layout-dashboard", accounts: "wallet-cards", transactions: "list-filter",
    import: "file-up", investments: "chart-no-axes-combined", sharing: "users", "asset-calendar": "calendar-days",
  };
  // 业务图标语义：主题对照用双栏，审计创建用文件新增，成功反馈统一圆形勾选。

  // 主题持久化确保跨页面切换时不发生闪烁或状态丢失。
  function applyTheme(theme) {
    html.dataset.theme = theme;
    localStorage.setItem("ziji-theme", theme);
    document.querySelectorAll("[data-theme-icon]").forEach(el => el.setAttribute("data-lucide", theme === "dark" ? "sun" : "moon"));
    refreshIcons();
  }
  const savedTheme = localStorage.getItem("ziji-theme") || "dark";
  applyTheme(savedTheme);

  function refreshIcons() {
    if (window.lucide) window.lucide.createIcons({ attrs: { "aria-hidden": "true" } });
  }

  function sidebar() {
    // 日历是 Dashboard/统计的下钻模块，不额外占用既有一级导航位置。
    const nav = Object.entries(pageNames).filter(([key]) => key !== "asset-calendar").map(([key, value]) => `
      <a class="nav-item ${page === key ? "active" : ""}" href="web-${key}.html" data-od-id="nav-${key}" ${page === key ? 'aria-current="page"' : ""}>
        <i data-lucide="${icons[key]}"></i><span>${value[0]}</span>
      </a>`).join("");
    return `<aside class="sidebar" data-od-id="web-sidebar">
      <a class="brand" href="index.html" data-od-id="brand-home">
        <span class="brand-mark">Z</span><span class="brand-name">资迹 <small>ZIJI</small></span>
      </a>
      <div class="nav-group">资金</div><nav class="nav-list" aria-label="主要导航">${nav}</nav>
      <div class="sidebar-foot">
        <a class="nav-item" href="mobile-home.html" data-od-id="nav-mobile-preview"><i data-lucide="smartphone"></i><span>移动端预览</span></a>
        <div class="sync-rail"><strong><span class="status-dot"></span>云端已同步</strong>22:46 完成</div>
      </div>
    </aside>`;
  }

  function topbar() {
    const [title, context] = pageNames[page] || ["资迹", "个人财务管理"];
    return `<header class="topbar" data-od-id="web-topbar">
      <div class="page-identity"><h1 class="page-title">${title}</h1><div class="page-context">${context}</div></div>
      <div class="top-actions">
        <button class="search-trigger" data-open="command" data-od-id="global-search"><i data-lucide="search"></i><span>搜索账户、流水或成员</span><kbd class="keycap">⌘ K</kbd></button>
        <div class="top-sync" data-tooltip="行情 20:30，汇率 22:00"><span class="status-dot"></span>数据可用</div>
        <button class="icon-btn" data-action="theme" data-tooltip="切换深浅主题" aria-label="切换深浅主题" data-od-id="theme-toggle"><i data-theme-icon data-lucide="sun"></i></button>
        <button class="icon-btn" data-action="notify" data-tooltip="查看通知" aria-label="查看通知" data-od-id="notification-button"><i data-lucide="bell"></i></button>
        <button class="icon-btn avatar-btn" data-dropdown="user-menu" aria-label="打开用户菜单" data-od-id="user-menu-button">林</button>
      </div>
    </header>`;
  }

  function webShell(content) {
    return `<a class="skip-link" href="#main">跳到主要内容</a><div class="web-shell">${sidebar()}${topbar()}<main id="main" class="content" tabindex="-1">${content}</main></div>${globalLayers()}`;
  }

  function chart() {
    return `<div class="line-chart" role="img" aria-label="近30天净资产从78.4万元上升至82.6万元，中间在7月29日因还款短暂下降">
      <span class="axis-y"><span>84万</span><span>81万</span><span>78万</span><span>75万</span></span>
      <svg viewBox="0 0 720 170" preserveAspectRatio="none" aria-hidden="true">
        <defs><linearGradient id="area" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".26"/><stop offset="1" stop-color="var(--accent)" stop-opacity="0"/></linearGradient></defs>
        <path d="M0,126 C45,122 63,112 103,116 S165,91 207,99 S268,68 315,79 S381,106 416,90 S478,66 520,70 S590,42 630,51 S690,29 720,32 L720,170 L0,170 Z" fill="url(#area)" stroke="none"/>
        <path d="M0,126 C45,122 63,112 103,116 S165,91 207,99 S268,68 315,79 S381,106 416,90 S478,66 520,70 S590,42 630,51 S690,29 720,32" fill="none" stroke="var(--accent)" stroke-width="2.2" vector-effect="non-scaling-stroke"/>
        <circle cx="720" cy="32" r="4" fill="var(--accent)" stroke="var(--surface)" stroke-width="3"/>
      </svg><span class="axis-x"><span>07/14</span><span>07/22</span><span>07/30</span><span>08/05</span><span>08/12</span></span>
    </div>`;
  }

  function transactionRows(limit = 6) {
    const rows = [
      ["今天 19:26", "盒马鲜生", "餐饮", "招商银行 8286", "-286.40", "已入账"],
      ["今天 10:18", "华夏基金分红", "投资收益", "中信证券", "+1,248.62", "已入账"],
      ["昨天 21:02", "微信支付退款", "退款", "微信零钱", "+68.00", "关联原交易"],
      ["08月10日", "信用卡还款", "负债还款", "招商银行 8286", "-8,420.36", "含利息 36.20"],
      ["08月09日", "工资", "工资收入", "中国银行 2119", "+28,460.00", "已入账"],
      ["08月08日", "跨币种转账", "转账", "美元现金", "-$500.00", "汇率 7.1882"],
      ["08月07日", "滴滴出行", "交通", "支付宝", "-47.86", "已入账"],
      ["08月06日", "物业费", "居住", "招商银行 8286", "-860.00", "已入账"],
    ].slice(0, limit);
    return rows.map((r, i) => `<tr data-transaction="${i}" data-search="${r.join(" ")}" tabindex="0" data-od-id="transaction-row-${i + 1}">
      <td>${r[0]}</td><td><span class="table-main">${r[1]}</span><span class="table-sub">${r[2]}</span></td><td>${r[3]}</td>
      <td class="number ${r[4].startsWith("+") ? "positive" : ""}">${r[4]}</td><td><span class="badge">${r[5]}</span></td><td><i data-lucide="chevron-right"></i></td>
    </tr>`).join("");
  }

  function dashboardPage() {
    // 总资产卡片与趋势区分别提供“Dashboard → 总资产”和“统计 → 资产趋势”两条日历入口。
    return webShell(`<div class="content-head"><div><h2 data-od-id="dashboard-heading">晚上好，林骁</h2><p>演示数据：你的净资产本月增加 4.2 万元，主要来自工资与投资上涨。</p></div>
      <div class="head-actions"><button class="btn" data-open="theme-compare" data-od-id="theme-compare-button"><i data-lucide="columns-2"></i>主题对照</button><button class="btn btn-primary" data-open="record" data-od-id="record-primary"><i data-lucide="plus"></i>记一笔</button></div></div>
      <section class="metric-strip" aria-label="核心资金指标" data-od-id="dashboard-metrics">
        <div class="metric"><div class="metric-label">净资产 <i data-lucide="info" data-tooltip="总资产减去总负债"></i></div><div class="metric-value accent">¥826,432.18</div><div class="metric-delta positive">↗ 本月 +5.36%（+¥42,116.20）</div></div>
        <div class="metric" id="total-assets"><div class="metric-label">总资产</div><div class="metric-value">¥982,760.44</div><div class="metric-delta"><a class="btn-link" href="web-asset-calendar.html">打开总资产日历</a></div></div>
        <div class="metric"><div class="metric-label">可用资金</div><div class="metric-value">¥184,920.66</div><div class="metric-delta">18.8% 占比</div></div>
        <div class="metric"><div class="metric-label">投资资产</div><div class="metric-value">¥641,511.30</div><div class="metric-delta positive">↗ 今日 +¥2,814.20</div></div>
        <div class="metric"><div class="metric-label">总负债</div><div class="metric-value">¥156,328.26</div><div class="metric-delta negative">本月待还 ¥8,420.36</div></div>
      </section>
      <div class="dashboard-grid"><div class="stack">
        <section class="panel" data-od-id="net-worth-trend"><div class="panel-head"><div><div class="panel-title">30 天净资产趋势</div><div class="panel-sub">统计 · 资产趋势</div></div><div style="display:flex;align-items:center;gap:12px"><div class="segmented"><button class="segment">7天</button><button class="segment active">30天</button><button class="segment">1年</button></div><a class="btn-link" href="web-asset-calendar.html">日历视图</a></div></div><div class="panel-body"><div class="chart-meta"><strong>¥826,432.18</strong><span class="positive">较期初 +¥38,940.73</span></div>${chart()}</div><p class="chart-summary">文字摘要：过去 30 天净资产整体上升 4.9%。7月29日因信用卡还款短暂下降，8月9日工资到账后回升。</p></section>
        <section class="panel" data-od-id="recent-transactions"><div class="panel-head"><div class="panel-title">近期流水</div><a class="btn-link" href="web-transactions.html">查看全部</a></div><div class="data-table-wrap" style="border:0;border-radius:0"><table class="data-table" aria-label="近期流水"><thead><tr><th>日期</th><th>交易</th><th>账户</th><th style="text-align:right">金额</th><th>状态</th><th></th></tr></thead><tbody>${transactionRows(5)}</tbody></table></div></section>
      </div><div class="stack">
        <section class="panel" data-od-id="asset-structure"><div class="panel-head"><div class="panel-title">资产结构</div><div class="panel-sub">¥982,760.44</div></div><div class="panel-body donut-wrap"><div class="donut" role="img" aria-label="投资 41%，现金 26%，基金 15%，其他 18%"></div><div class="legend"><div class="legend-row"><span class="legend-key"></span><span>股票与 ETF</span><strong>41%</strong></div><div class="legend-row"><span class="legend-key"></span><span>基金</span><strong>26%</strong></div><div class="legend-row"><span class="legend-key"></span><span>现金账户</span><strong>15%</strong></div><div class="legend-row"><span class="legend-key"></span><span>其他</span><strong>18%</strong></div></div></div></section>
        <section class="panel" data-od-id="account-distribution"><div class="panel-head"><div class="panel-title">账户分布</div><a class="btn-link" href="web-accounts.html">管理账户</a></div><div class="panel-body attribution"><div class="attribution-row"><span>中信证券</span><div class="mini-bar" style="width:92%"></div><strong>44.6%</strong></div><div class="attribution-row"><span>天天基金</span><div class="mini-bar" style="width:48%"></div><strong>20.6%</strong></div><div class="attribution-row"><span>招商银行</span><div class="mini-bar" style="width:30%"></div><strong>8.5%</strong></div><div class="attribution-row"><span>其他账户</span><div class="mini-bar" style="width:58%"></div><strong>26.3%</strong></div></div></section>
        <section class="panel" data-od-id="asset-attribution"><div class="panel-head"><div class="panel-title">本月变化归因</div><div class="panel-sub positive">+¥42,116.20</div></div><div class="panel-body attribution"><div class="attribution-row"><span>工资收入</span><div class="mini-bar" style="width:88%"></div><strong>+28,460</strong></div><div class="attribution-row"><span>投资涨跌</span><div class="mini-bar" style="width:44%"></div><strong>+14,238</strong></div><div class="attribution-row"><span>日常收支</span><div class="mini-bar" style="width:22%"></div><strong>-7,904</strong></div><div class="attribution-row"><span>汇率折算</span><div class="mini-bar" style="width:16%"></div><strong>+7,322</strong></div></div></section>
        <section class="panel" data-od-id="investment-overview"><div class="panel-head"><div class="panel-title">投资概览</div><a class="btn-link" href="web-investments.html">查看持仓</a></div><div class="detail-grid"><div class="detail-cell"><span>今日盈亏</span><strong class="positive">+¥2,814.20</strong></div><div class="detail-cell"><span>累计收益率</span><strong class="positive">+7.94%</strong></div><div class="detail-cell"><span>行情更新</span><strong>08月12日 20:30</strong></div><div class="detail-cell"><span>汇率更新</span><strong>08月12日 22:00</strong></div></div></section>
        <section class="panel" data-od-id="data-quality"><div class="panel-head"><div class="panel-title">数据质量</div><span class="badge badge-warning">2 项需处理</span></div><div class="quality-list"><div class="quality-item"><span class="quality-icon"><i data-lucide="clock-3"></i></span><div><strong>2 只基金净值已过期</strong><p>最新净值日期为 08月09日</p></div><button class="btn-link">查看</button></div><div class="quality-item"><span class="quality-icon danger"><i data-lucide="triangle-alert"></i></span><div><strong>发现 1 项同步冲突</strong><p>iPhone 离线记录与云端版本不同</p></div><button class="btn-link" data-open="conflict">处理</button></div></div></section>
        <section class="panel" data-od-id="month-cashflow"><div class="panel-head"><div class="panel-title">本月收支</div><div class="panel-sub">截至 08月12日</div></div><div class="detail-grid"><div class="detail-cell"><span>收入</span><strong class="positive">¥31,582.40</strong></div><div class="detail-cell"><span>支出</span><strong class="negative">¥17,304.82</strong></div></div></section>
      </div></div>`);
  }

  function accountsPage() {
    const group = (title, total, rows) => `<section class="section-block"><div class="section-title"><h3>${title}</h3><span>${total}</span></div><div class="panel account-list">${rows}</div></section>`;
    const row = (id, icon, name, note, balance, available, frozen, transit, badge = "已计入") => `<div class="account-row" data-account="${id}" tabindex="0" data-od-id="account-${id}"><span class="account-logo"><i data-lucide="${icon}"></i></span><div class="account-name">${name}<small>${note}</small></div><div class="account-cell"><label>账面余额</label><strong>${balance}</strong></div><div class="account-cell"><label>可用余额</label><strong>${available}</strong></div><div class="account-cell hide-tablet"><label>冻结</label><strong>${frozen}</strong></div><div class="account-cell hide-tablet"><label>在途</label><strong>${transit}</strong></div><span class="badge">${badge}</span><i data-lucide="chevron-right"></i></div>`;
    return webShell(`<div class="content-head"><div><h2 data-od-id="accounts-heading">账户与余额</h2><p>演示数据：共 8 个活跃账户，1 个归档账户。</p></div><div class="head-actions"><button class="btn"><i data-lucide="archive"></i>归档账户</button><button class="btn btn-primary" data-open="add-account"><i data-lucide="plus"></i>添加账户</button></div></div>
      <div class="notice"><i data-lucide="shield-check"></i><div><strong>统计口径已应用个人计入比例</strong><p>共享账户“家庭日常”按 60% 计入本人资产，本次修改不会重算历史数据。</p></div></div>
      ${group("现金账户", "可用 ¥184,920.66", row("cmb", "landmark", "招商银行 8286", "储蓄卡 / 本人", "¥83,426.90", "¥82,816.90", "¥610.00", "¥0.00") + row("wechat", "message-circle", "微信零钱", "电子钱包 / 本人", "¥6,218.42", "¥6,218.42", "¥0.00", "¥0.00") + row("family", "users", "家庭日常", "共享账户 / OWNER / 计入 60%", "¥28,426.70", "¥26,020.30", "¥0.00", "¥2,406.40", "共享"))}
      ${group("投资账户", "市值 ¥641,511.30", row("citic", "chart-candlestick", "中信证券", "股票与 ETF / 行情 20:30", "¥438,621.10", "¥31,820.00", "¥0.00", "¥4,280.00") + row("fund", "chart-pie", "天天基金", "基金 / 净值 08月09日", "¥202,890.20", "¥2,418.62", "¥0.00", "¥1,624.00", "净值过期"))}
      ${group("负债账户", "待还 ¥156,328.26", row("credit", "credit-card", "招商信用卡 6621", "账单日 05日 / OWNER", "¥8,420.36", "额度 ¥41,579.64", "¥1,680.00", "¥286.40", "08月18日到期") + row("loan", "badge-dollar-sign", "消费贷款", "剩余 21 期 / 本人", "¥147,907.90", "下期 ¥7,320.18", "¥0.00", "¥0.00", "正常"))}`);
  }

  function transactionsPage() {
    return webShell(`<div class="content-head"><div><h2 data-od-id="transactions-heading">全部流水</h2><p>演示数据：修改或作废会保留历史记录并生成冲正记录。</p></div><div class="head-actions"><a class="btn" href="web-import.html"><i data-lucide="file-up"></i>导入账单</a><button class="btn btn-primary" data-open="record"><i data-lucide="plus"></i>记一笔</button></div></div>
      <div class="notice"><i data-lucide="history"></i><div><strong>所有更改均可追溯</strong><p>编辑交易会记录操作人、时间和修改前后的字段值，作废不会直接删除原记录。</p></div></div>
      <div class="filterbar" data-od-id="transaction-filters"><div class="filter-search"><i data-lucide="search"></i><input class="input" id="transaction-search" aria-label="搜索流水" placeholder="搜索交易、备注或标签"></div><button class="filter-chip active" data-filter="date">本月 <i data-lucide="chevron-down"></i></button><button class="filter-chip" data-filter="account">全部账户</button><button class="filter-chip" data-filter="category">全部分类</button><button class="filter-chip" data-filter="tag">全部标签</button><button class="filter-chip" data-filter="type">交易类型</button><button class="filter-chip" data-filter="amount">金额范围</button><button class="icon-btn" data-action="clear-filters" aria-label="清除筛选"><i data-lucide="rotate-ccw"></i></button></div>
      <div class="data-table-wrap"><table class="data-table" aria-label="全部流水"><thead><tr><th>日期</th><th>交易与分类</th><th>账户</th><th style="text-align:right">金额</th><th>状态</th><th></th></tr></thead><tbody id="transaction-body">${transactionRows(8)}</tbody></table></div>
      <div class="section-block"><div class="section-title"><h3>操作状态预览</h3><span>关键状态组件</span></div><div class="detail-grid panel"><div class="detail-cell"><span>加载状态</span><strong class="skeleton">¥18,642.20</strong></div><div class="detail-cell"><span>空状态</span><strong>当前筛选无结果</strong></div><div class="detail-cell"><span>无权限</span><strong class="negative">仅 OWNER 可作废共享流水</strong></div><div class="detail-cell"><span>部分可用</span><strong class="accent">2 条汇率记录待补充</strong></div></div></div>`);
  }

  function importPage() {
    return webShell(`<div class="content-head"><div><h2 data-od-id="import-heading">导入账单</h2><p>一次最多 20 MB，支持 CSV、XLS 与 XLSX。</p></div><div class="head-actions"><button class="btn" data-action="import-history"><i data-lucide="history"></i>导入记录</button></div></div>
      <div class="stepper" data-od-id="import-stepper"><div class="step active" data-import-step="0"><small>01</small><strong>上传文件</strong></div><div class="step" data-import-step="1"><small>02</small><strong>字段映射</strong></div><div class="step" data-import-step="2"><small>03</small><strong>重复检测</strong></div><div class="step" data-import-step="3"><small>04</small><strong>用户预览</strong></div><div class="step" data-import-step="4"><small>05</small><strong>批次确认</strong></div><div class="step" data-import-step="5"><small>06</small><strong>导入结果</strong></div></div>
      <section class="panel" id="import-stage" data-od-id="import-stage">${importStage(0)}</section>`);
  }

  function importStage(step) {
    if (step === 0) return `<div class="panel-body"><label class="upload-zone" id="upload-zone"><input id="import-file" type="file" accept=".csv,.xls,.xlsx" hidden><span><span class="upload-icon"><i data-lucide="file-spreadsheet"></i></span><h3>拖入账单文件，或点击选择</h3><p>文件会在本地预检，再上传到当前家庭空间。</p><button class="btn" type="button" style="margin-top:16px">选择文件</button></span></label><div class="notice" style="margin:14px 0 0"><i data-lucide="info"></i><div><strong>请勿上传加密文件</strong><p>首行应包含字段名，推荐包含日期、金额、交易对方和备注。</p></div></div></div>`;
    if (step === 1) return `<div class="panel-head"><div><div class="panel-title">字段映射</div><div class="panel-sub">cmb_2026-08.csv，共 428 行</div></div><span class="badge badge-success">解析完成</span></div><div class="panel-body"><div class="mapping-grid"><strong>交易日期</strong><span class="mapping-arrow">→</span><select class="select"><option>交易时间</option></select></div><div class="mapping-grid"><strong>交易金额</strong><span class="mapping-arrow">→</span><select class="select"><option>金额(元)</option></select></div><div class="mapping-grid"><strong>交易对方</strong><span class="mapping-arrow">→</span><select class="select"><option>摘要</option></select></div><div class="mapping-grid"><strong>交易类型</strong><span class="mapping-arrow">→</span><select class="select"><option>收支类型</option></select></div><div class="mapping-grid"><strong>备注</strong><span class="mapping-arrow">→</span><select class="select"><option>附言</option></select></div></div><div class="dialog-foot"><button class="btn" data-import-back>上一步</button><button class="btn btn-primary" data-import-next>检测重复</button></div>`;
    if (step === 2) return `<div class="panel-head"><div><div class="panel-title">重复检测</div><div class="panel-sub">已比较现有 12,406 条流水</div></div><span class="badge badge-warning">需确认 7 条</span></div><div class="panel-body"><div class="detail-grid"><div class="detail-cell"><span>精确重复</span><strong class="negative">3 条</strong></div><div class="detail-cell"><span>疑似重复</span><strong class="accent">4 条</strong></div></div><div class="notice notice-danger" style="margin-top:14px"><i data-lucide="copy-x"></i><div><strong>精确重复将默认跳过</strong><p>金额、时间和交易对方完全相同。你仍可在预览中逐行恢复。</p></div></div><div class="data-table-wrap"><table class="data-table"><thead><tr><th>判定</th><th>日期</th><th>交易</th><th style="text-align:right">金额</th><th>处理</th></tr></thead><tbody><tr><td><span class="badge badge-danger">精确重复</span></td><td>08月10日</td><td>信用卡还款</td><td class="number">-8,420.36</td><td>跳过</td></tr><tr><td><span class="badge badge-warning">疑似重复</span></td><td>08月07日</td><td>滴滴出行</td><td class="number">-47.86</td><td>待确认</td></tr></tbody></table></div></div><div class="dialog-foot"><button class="btn" data-import-back>上一步</button><button class="btn btn-primary" data-import-next>查看预览</button></div>`;
    if (step === 3) return `<div class="panel-head"><div><div class="panel-title">导入预览</div><div class="panel-sub">418 条将导入，3 条跳过，7 条待确认</div></div><span class="badge">批次草稿</span></div><div class="panel-body"><div class="notice"><i data-lucide="triangle-alert"></i><div><strong>第 219 行缺少交易日期</strong><p>该行不会导入。你可以返回修改文件，或继续处理其余记录。</p></div></div><div class="data-table-wrap"><table class="data-table"><thead><tr><th>行</th><th>日期</th><th>交易</th><th>分类</th><th style="text-align:right">金额</th><th>状态</th></tr></thead><tbody><tr><td>1</td><td>08月12日</td><td>盒马鲜生</td><td>餐饮</td><td class="number">-286.40</td><td><span class="badge badge-success">可导入</span></td></tr><tr><td>219</td><td class="negative">缺失</td><td>便利店</td><td>日用</td><td class="number">-28.50</td><td><span class="badge badge-danger">行级错误</span></td></tr></tbody></table></div></div><div class="dialog-foot"><button class="btn" data-import-back>上一步</button><button class="btn btn-primary" data-import-next>确认批次</button></div>`;
    if (step === 4) return `<div class="panel-body"><div class="empty-state"><i data-lucide="file-check-2"></i><h3>确认导入 418 条流水</h3><p>目标账户为“招商银行 8286”。确认后将创建可整批撤销的批次记录。</p><label style="display:flex;gap:8px;align-items:center;margin-bottom:16px"><input type="checkbox" id="confirm-import">我已检查字段映射与重复记录</label><button class="btn btn-primary" data-import-next disabled id="confirm-import-button">开始导入</button></div></div>`;
    return `<div class="panel-body"><div class="empty-state"><i data-lucide="circle-check" class="positive"></i><h3>导入完成，部分记录未处理</h3><p>成功 418 条，失败 1 条，跳过 3 条。批次编号 IMP-20260812-042。</p><div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:center"><button class="btn" data-action="undo-import">整批撤销</button><a class="btn btn-primary" href="web-transactions.html">查看流水</a></div></div><div class="detail-grid"><div class="detail-cell"><span>批次状态</span><strong class="positive">部分成功</strong></div><div class="detail-cell"><span>操作人</span><strong>林骁</strong></div></div></div>`;
  }

  function investmentsPage() {
    const row = (id, code, name, type, market, cost, profit, rate) => `<div class="holding-row" data-holding="${id}" tabindex="0" data-od-id="holding-${id}"><span class="asset-logo">${code.slice(0, 2)}</span><div class="holding-name">${name}<small>${code} / ${type}</small></div><div class="holding-cell"><label>市值</label><strong>${market}</strong></div><div class="holding-cell"><label>持仓成本</label><strong>${cost}</strong></div><div class="holding-cell hide-tablet"><label>浮动盈亏</label><strong class="${profit.startsWith("+") ? "positive" : "negative"}">${profit}</strong></div><div class="holding-cell hide-tablet"><label>收益率</label><strong class="${rate.startsWith("+") ? "positive" : "negative"}">${rate}</strong></div><i data-lucide="chevron-right"></i></div>`;
    return webShell(`<div class="content-head"><div><h2 data-od-id="investments-heading">投资持仓</h2><p>演示数据：行情来自 Tushare Pro，非盘中实时行情。</p></div><div class="head-actions"><a class="btn" href="web-investment-return-calendar.html"><i data-lucide="calendar-days"></i>收益日历</a><button class="btn"><i data-lucide="download"></i>导出持仓</button><button class="btn btn-primary" data-open="trade"><i data-lucide="plus"></i>记录交易</button></div></div>
      <div class="notice"><i data-lucide="clock-3"></i><div><strong>部分基金净值已过期</strong><p>股票与 ETF 行情日期 08月12日，基金最新净值日期 08月09日。缺失数据可使用手工价格临时兜底。</p></div><button class="btn btn-ghost">补录价格</button></div>
      <section class="metric-strip"><div class="metric"><div class="metric-label">投资市值</div><div class="metric-value accent">¥641,511.30</div><div class="metric-delta positive">今日 +¥2,814.20</div></div><div class="metric"><div class="metric-label">持仓成本</div><div class="metric-value">¥594,302.18</div><div class="metric-delta">累计投入</div></div><div class="metric"><div class="metric-label">未实现收益</div><div class="metric-value positive">+¥47,209.12</div><div class="metric-delta">+7.94%</div></div><div class="metric"><div class="metric-label">已实现收益</div><div class="metric-value">+¥18,406.80</div><div class="metric-delta">近 12 个月</div></div><div class="metric"><div class="metric-label">现金余额</div><div class="metric-value">¥34,238.62</div><div class="metric-delta">5.3% 占比</div></div></section>
      <div class="dashboard-grid"><section class="panel"><div class="panel-head"><div class="panel-title">持仓趋势</div><div class="segmented"><button class="segment active">市值</button><button class="segment">盈亏</button></div></div><div class="panel-body">${chart()}</div><p class="chart-summary">文字摘要：近 30 天持仓市值上涨 6.2%，主要由沪深300ETF和贵州茅台贡献。</p></section><section class="panel"><div class="panel-head"><div class="panel-title">资产配置</div><div class="panel-sub">按市值</div></div><div class="panel-body donut-wrap"><div class="donut" role="img" aria-label="股票41%，ETF33%，基金26%"></div><div class="legend"><div class="legend-row"><span class="legend-key"></span><span>股票</span><strong>41%</strong></div><div class="legend-row"><span class="legend-key"></span><span>ETF</span><strong>33%</strong></div><div class="legend-row"><span class="legend-key"></span><span>基金</span><strong>26%</strong></div></div></div></section></div>
      <section class="section-block"><div class="section-title"><h3>全部持仓</h3><span>5 项资产</span></div><div class="panel holding-list">${row("hs300", "51", "沪深300ETF", "ETF", "¥184,620.00", "¥165,402.18", "+¥19,217.82", "+11.62%")}${row("moutai", "60", "贵州茅台", "股票", "¥167,528.00", "¥159,880.00", "+¥7,648.00", "+4.78%")}${row("nasdaq", "51", "纳指ETF", "ETF", "¥126,473.10", "¥110,620.00", "+¥15,853.10", "+14.33%")}${row("fund-a", "00", "华夏回报混合A", "基金 / 净值过期", "¥92,890.20", "¥88,400.00", "+¥4,490.20", "+5.08%")}${row("bond", "00", "易方达稳健收益A", "债券基金", "¥70,000.00", "¥70,000.00", "+¥0.00", "+0.00%")}</div></section>`);
  }

  function sharingPage() {
    // 计入比例从下一快照生效，使用日历时钟表达“未来时间点”而不是泛化历史记录。
    return webShell(`<div class="content-head"><div><h2 data-od-id="sharing-heading">共享与设置</h2><p>管理成员权限、个人统计口径、主题、基准币种和设备会话。</p></div><div class="head-actions"><button class="btn btn-primary" data-open="invite" data-od-id="invite-member"><i data-lucide="user-plus"></i>邀请成员</button></div></div>
      <div class="tabs" role="tablist"><button class="tab active" data-tab="members">成员与权限</button><button class="tab" data-tab="preferences">偏好设置</button><button class="tab" data-tab="security">安全与设备</button></div>
      <section class="tab-panel active" data-panel="members"><div class="split-layout"><div class="panel"><div class="panel-head"><div><div class="panel-title">家庭空间成员</div><div class="panel-sub">3 位成员，1 个待接受邀请</div></div></div><div class="quality-list"><div class="quality-item"><span class="account-logo">林</span><div><strong>林骁（你）</strong><p>linxiao@example.com</p></div><span class="badge">OWNER</span></div><div class="quality-item"><span class="account-logo">顾</span><div><strong>顾宜宁</strong><p>yining@example.com</p></div><span class="badge">EDITOR</span></div><div class="quality-item"><span class="account-logo">林</span><div><strong>林明远</strong><p>已邀请，7 天后过期</p></div><span class="badge badge-warning">待接受</span></div></div></div><aside class="panel detail-aside"><div class="panel-head"><div class="panel-title">权限说明</div></div><div class="panel-body"><p><strong>OWNER</strong><br><span class="panel-sub">管理所有数据、成员与所有权。</span></p><p><strong>EDITOR</strong><br><span class="panel-sub">新增和修改交易，不能管理成员。</span></p><p><strong>VIEWER</strong><br><span class="panel-sub">只读访问已授权的账户。</span></p></div></aside></div>
      <section class="section-block"><div class="section-title"><h3>个人计入比例</h3><span>只影响本人后续统计</span></div><div class="panel"><div class="quality-item"><span class="quality-icon"><i data-lucide="users"></i></span><div><strong>家庭日常</strong><p>共享现金账户，目前按 60% 计入林骁的总资产。</p></div><button class="btn">修改 60%</button></div></div><div class="notice" style="margin-top:12px"><i data-lucide="calendar-clock"></i><div><strong>修改比例不会重算历史数据</strong><p>新比例从保存后的下一笔余额快照开始生效。</p></div></div></section></section>
      <section class="tab-panel" data-panel="preferences"><div class="panel"><div class="quality-item"><span class="quality-icon"><i data-lucide="sun-moon"></i></span><div><strong>界面主题</strong><p>深色、浅色或跟随系统</p></div><button class="btn" data-action="theme">切换主题</button></div><div class="quality-item"><span class="quality-icon"><i data-lucide="clock"></i></span><div><strong>时区</strong><p>Asia/Shanghai (UTC+8)</p></div><button class="btn">修改</button></div><div class="quality-item"><span class="quality-icon"><i data-lucide="circle-dollar-sign"></i></span><div><strong>基准币种</strong><p>人民币 CNY，汇率更新于 22:00</p></div><button class="btn">修改</button></div></div></section>
      <section class="tab-panel" data-panel="security"><div class="panel"><div class="quality-item"><span class="quality-icon"><i data-lucide="key-round"></i></span><div><strong>登录密码</strong><p>上次修改于 2026年06月18日</p></div><button class="btn">修改</button></div><div class="quality-item"><span class="quality-icon"><i data-lucide="laptop"></i></span><div><strong>MacBook Pro</strong><p>当前设备，上海，刚刚活跃</p></div><span class="badge badge-success">当前</span></div><div class="quality-item"><span class="quality-icon"><i data-lucide="smartphone"></i></span><div><strong>iPhone 15 Pro</strong><p>上海，2 小时前活跃</p></div><button class="btn btn-danger">退出会话</button></div></div></section>`);
  }

  function authPage() {
    return `<main class="auth-page" data-od-id="auth-page"><section class="auth-brand"><a class="brand" href="index.html"><span class="brand-mark">Z</span><span class="brand-name">资迹 <small>ZIJI</small></span></a><div class="auth-statement"><h1>看清每一笔钱<br>现在在哪里。</h1><p>统一管理现金、负债、投资和家庭共享账户，让资产变化有迹可循。</p></div><div class="auth-proof"><div><strong>本地优先</strong><span>移动端离线记账</span></div><div><strong>可追溯</strong><span>修改保留审计历史</span></div><div><strong>无自动同步</strong><span>V1 不接入银行权限</span></div></div></section><section class="auth-main"><div class="auth-card"><div class="tabs" role="tablist"><button class="tab active" data-auth-tab="login">登录</button><button class="tab" data-auth-tab="register">邮箱注册</button></div><div id="auth-content">${authForm("login")}</div></div></section></main>${globalLayers()}`;
  }

  function authForm(mode) {
    if (mode === "register") return `<h2 style="margin-top:24px">创建资迹账户</h2><p>使用邮箱验证码注册，不需要手机号。</p><form class="auth-form" data-form="register"><div class="field"><label for="reg-email">邮箱地址</label><input class="input" id="reg-email" type="email" autocomplete="email" value="linxiao@example.com"></div><button class="btn btn-primary" type="submit">发送验证码</button><div class="auth-links"><span>已有账户</span><button type="button" data-auth-switch="login">返回登录</button></div></form>`;
    if (mode === "otp") return `<h2 style="margin-top:24px">输入邮箱验证码</h2><p>验证码已发送至 linxiao@example.com，10 分钟内有效。</p><form class="auth-form" data-form="otp"><div class="otp" aria-label="六位验证码">${[0,1,2,3,4,5].map(i => `<input inputmode="numeric" maxlength="1" aria-label="第 ${i+1} 位验证码">`).join("")}</div><div class="field-error" id="otp-error" hidden role="alert">验证码已过期，请重新发送。</div><button class="btn btn-primary" type="submit">验证并创建账户</button><div class="auth-links"><button type="button" data-auth-switch="register">修改邮箱</button><button type="button" data-action="resend-otp">重新发送 52s</button></div></form>`;
    if (mode === "forgot") return `<h2 style="margin-top:24px">重置密码</h2><p>输入注册邮箱，我们会发送密码重置验证码。</p><form class="auth-form" data-form="forgot"><div class="field"><label for="forgot-email">邮箱地址</label><input class="input" id="forgot-email" type="email" autocomplete="email" placeholder="name@example.com"></div><button class="btn btn-primary" type="submit">发送验证码</button><div class="auth-links"><button type="button" data-auth-switch="login">返回登录</button></div></form>`;
    return `<h2 style="margin-top:24px">欢迎回来</h2><p>登录后继续查看你的资金全貌。</p><form class="auth-form" data-form="login"><div class="field"><label for="login-email">邮箱地址</label><input class="input" id="login-email" type="email" autocomplete="email" value="linxiao@example.com"></div><div class="field"><label for="login-password">密码</label><input class="input" id="login-password" type="password" autocomplete="current-password" value="12345678"><span class="field-error" id="login-error" hidden role="alert">邮箱或密码不正确，请检查后重试。</span></div><button class="btn btn-primary" type="submit" data-od-id="login-submit">登录</button><div class="auth-links"><span>仅支持邮箱登录</span><button type="button" data-auth-switch="forgot">忘记密码</button></div></form>`;
  }

  function globalLayers() {
    return `<div class="overlay" id="command" role="dialog" aria-modal="true" aria-label="全局搜索"><div class="dialog command"><div class="dialog-body"><div class="filter-search"><i data-lucide="search"></i><input class="input" autofocus placeholder="输入账户、流水或成员"></div></div><div class="command-list"><a class="command-item" href="web-accounts.html"><i data-lucide="wallet-cards"></i>招商银行 8286<span class="keycap">账户</span></a><a class="command-item" href="web-transactions.html"><i data-lucide="receipt-text"></i>信用卡还款 ¥8,420.36<span class="keycap">流水</span></a><a class="command-item" href="web-sharing.html"><i data-lucide="user"></i>顾宜宁<span class="keycap">成员</span></a></div></div></div>
      <div class="dropdown" id="user-menu" role="menu"><button class="dropdown-item"><i data-lucide="user-round"></i>个人资料</button><a class="dropdown-item" href="web-sharing.html"><i data-lucide="settings"></i>设置</a><div class="dropdown-separator"></div><button class="dropdown-item"><i data-lucide="log-out"></i>退出登录</button></div>
      <div class="toast-region" aria-live="polite" aria-atomic="true" data-od-id="toast-region"></div>`;
  }

  function recordDialog(isMobile = false) {
    const wrap = isMobile ? "mobile-sheet" : "dialog dialog-wide";
    return `<div class="${wrap}" role="dialog" aria-modal="true" aria-labelledby="record-title">${isMobile ? '<div class="sheet-handle"></div>' : ""}<div class="dialog-head"><div><h2 id="record-title">记一笔</h2><p>选择符合实际资金动作的类型，无需理解会计分录。</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="segmented" style="width:100%;overflow:auto" data-transaction-types>${["支出","收入","退款","转账","负债还款","余额调整"].map((x,i) => `<button class="segment ${i===0?'active':''}" data-type="${x}">${x}</button>`).join("")}</div><form class="form-grid" id="record-form" style="margin-top:16px">${recordFields("支出")}</form><div id="type-extra"></div></div><div class="dialog-foot"><button class="btn" data-close>取消</button><button class="btn btn-primary" data-submit-record>保存交易</button></div></div>`;
  }

  // 每种资金动作重新组织字段语义，不把内部会计术语暴露给用户。
  function recordFields(type) {
    const note = `<div class="field full"><label for="record-note">备注</label><textarea class="textarea" id="record-note" placeholder="可选"></textarea></div>`;
    if (type === "转账") return `<div class="field"><label for="record-amount">转出金额</label><input class="input" id="record-amount" inputmode="decimal" value="500.00"><span class="field-help">美元 USD</span></div><div class="field"><label for="record-in-amount">转入金额</label><input class="input" id="record-in-amount" inputmode="decimal" value="3594.10"><span class="field-help">人民币 CNY</span></div><div class="field"><label for="record-account">转出账户</label><select class="select" id="record-account"><option>美元现金</option></select></div><div class="field"><label for="record-in-account">转入账户</label><select class="select" id="record-in-account"><option>招商银行 8286</option></select></div>${note}`;
    if (type === "负债还款") return `<div class="field full"><label for="record-amount">还款总额</label><input class="input" id="record-amount" inputmode="decimal" value="8,036.20"></div><div class="field"><label for="record-account">还款账户</label><select class="select" id="record-account"><option>招商银行 8286</option></select></div><div class="field"><label for="record-debt">负债账户</label><select class="select" id="record-debt"><option>招商信用卡 6621</option></select></div>${note}`;
    if (type === "退款") return `<div class="field full"><label for="record-amount">退款金额</label><input class="input" id="record-amount" inputmode="decimal" value="68.00"></div><div class="field"><label for="record-account">退款到账账户</label><select class="select" id="record-account"><option>微信零钱</option></select></div><div class="field"><label for="record-original">关联原交易</label><select class="select" id="record-original"><option>盒马鲜生 -¥68.00</option></select></div>${note}`;
    if (type === "余额调整") return `<div class="field"><label for="record-amount">调整后余额</label><input class="input" id="record-amount" inputmode="decimal" value="83,426.90"></div><div class="field"><label for="record-account">调整账户</label><select class="select" id="record-account"><option>招商银行 8286</option></select></div><div class="field full"><label for="record-reason">调整原因</label><input class="input" id="record-reason" value="对账修正"></div>${note}`;
    const income = type === "收入";
    return `<div class="field full"><label for="record-amount">${income ? "收入金额" : "支出金额"}</label><input class="input" id="record-amount" inputmode="decimal" value="${income ? "28,460.00" : "286.40"}"><span class="field-help">人民币 CNY</span></div><div class="field"><label for="record-account">${income ? "收款账户" : "付款账户"}</label><select class="select" id="record-account"><option>招商银行 8286</option><option>微信零钱</option><option>支付宝</option></select></div><div class="field"><label for="record-category">分类</label><select class="select" id="record-category"><option>${income ? "工资收入" : "餐饮"}</option><option>${income ? "投资收益" : "交通"}</option></select></div><div class="field full"><label for="record-counterparty">${income ? "付款方" : "交易对方"}</label><input class="input" id="record-counterparty" value="${income ? "知行科技有限公司" : "盒马鲜生"}"></div>${note}`;
  }

  function detailDialog(kind, id) {
    if (kind === "account") return `<div class="drawer" role="dialog" aria-modal="true" aria-labelledby="detail-title"><div class="dialog-head"><div><h2 id="detail-title">招商银行 8286</h2><p>储蓄卡 / 人民币 / 计入个人统计</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="metric" style="padding:0 0 18px;border:0"><div class="metric-label">账面余额</div><div class="metric-value accent">¥83,426.90</div><div class="metric-delta">可用 ¥82,816.90 / 冻结 ¥610.00</div></div>${chart()}<div class="section-block"><div class="section-title"><h3>账户设置</h3></div><div class="panel"><div class="quality-item"><div><strong>计入个人统计</strong><p>100% 计入林骁的后续统计</p></div><button class="switch" aria-checked="true" aria-label="计入个人统计"></button></div><div class="quality-item"><div><strong>共享成员</strong><p>林骁 OWNER，顾宜宁 VIEWER</p></div><button class="btn">管理</button></div></div></div><button class="btn btn-danger" style="margin-top:18px" data-action="archive-account">归档账户</button></div></div>`;
    if (kind === "holding") return `<div class="drawer" role="dialog" aria-modal="true" aria-labelledby="holding-title"><div class="dialog-head"><div><h2 id="holding-title">沪深300ETF</h2><p>510300 / ETF / 上交所</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="metric" style="padding:0 0 16px;border:0"><div class="metric-label">持仓市值</div><div class="metric-value">¥184,620.00</div><div class="metric-delta positive">未实现 +¥19,217.82（+11.62%）</div></div><div class="notice"><i data-lucide="database"></i><div><strong>Tushare Pro</strong><p>行情日期 2026年08月12日，收盘价 ¥4.386。</p></div></div>${chart()}<div class="tabs"><button class="tab active">交易记录</button><button class="tab">分红记录</button></div><div class="mobile-list" style="margin:16px 0 0"><div class="mobile-row"><span class="mobile-row-icon"><i data-lucide="arrow-down-left"></i></span><span class="mobile-row-main"><strong>买入 12,000 份</strong><span>2026年05月18日</span></span><span class="mobile-row-value">¥49,320.00<span>成交价 4.110</span></span><i data-lucide="chevron-right"></i></div><div class="mobile-row"><span class="mobile-row-icon"><i data-lucide="arrow-up-right"></i></span><span class="mobile-row-main"><strong>卖出 3,000 份</strong><span>2026年07月02日</span></span><span class="mobile-row-value">¥12,996.00<span>已实现 +¥666</span></span><i data-lucide="chevron-right"></i></div></div></div></div>`;
    return `<div class="drawer" role="dialog" aria-modal="true" aria-labelledby="transaction-title"><div class="dialog-head"><div><h2 id="transaction-title">盒马鲜生</h2><p>支出 / 已入账 / TXN-20260812-1846</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="metric" style="padding:0 0 18px;border:0"><div class="metric-label">交易金额</div><div class="metric-value negative">-¥286.40</div><div class="metric-delta">今天 19:26</div></div><div class="detail-grid panel"><div class="detail-cell"><span>付款账户</span><strong>招商银行 8286</strong></div><div class="detail-cell"><span>分类</span><strong>餐饮</strong></div><div class="detail-cell"><span>标签</span><strong>家庭日常</strong></div><div class="detail-cell"><span>操作人</span><strong>林骁</strong></div></div><div class="section-block"><div class="section-title"><h3>审计记录</h3></div><div class="panel"><div class="quality-item"><span class="quality-icon"><i data-lucide="file-plus-2"></i></span><div><strong>创建交易</strong><p>林骁，今天 19:26</p></div><span class="badge">原始</span></div></div></div><div style="display:flex;gap:8px;margin-top:18px"><button class="btn">修改交易</button><button class="btn btn-danger" data-action="void-transaction">作废交易</button></div><p class="field-help">修改或作废会保留历史记录并生成冲正记录。</p></div></div>`;
  }

  function themeCompareDialog() {
    return `<div class="dialog dialog-wide" role="dialog" aria-modal="true" aria-labelledby="compare-title"><div class="dialog-head"><div><h2 id="compare-title">Dashboard 主题对照</h2><p>两套主题分别校准表面、边框、文字与图表，不做简单反色。</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body" style="display:grid;grid-template-columns:1fr 1fr;gap:12px"><div data-theme="dark" style="background:var(--bg);color:var(--fg);border:1px solid var(--border);border-radius:10px;padding:14px"><span class="panel-sub">深色主题</span><div class="metric-value" style="font-size:22px">¥826,432.18</div><div class="metric-delta positive">↗ 本月 +5.36%</div><div class="progress"><span style="width:72%"></span></div><div class="detail-grid" style="margin-top:12px"><div class="detail-cell"><span>可用资金</span><strong>¥184,920</strong></div><div class="detail-cell"><span>总负债</span><strong>¥156,328</strong></div></div></div><div data-theme="light" style="background:var(--bg);color:var(--fg);border:1px solid var(--border);border-radius:10px;padding:14px"><span class="panel-sub">浅色主题</span><div class="metric-value" style="font-size:22px">¥826,432.18</div><div class="metric-delta positive">↗ 本月 +5.36%</div><div class="progress"><span style="width:72%"></span></div><div class="detail-grid" style="margin-top:12px"><div class="detail-cell"><span>可用资金</span><strong>¥184,920</strong></div><div class="detail-cell"><span>总负债</span><strong>¥156,328</strong></div></div></div></div></div>`;
  }

  function inviteDialog() {
    return `<div class="dialog" role="dialog" aria-modal="true" aria-labelledby="invite-title"><div class="dialog-head"><div><h2 id="invite-title">邀请家庭成员</h2><p>邀请邮件将在 7 天后过期。</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><form id="invite-form"><div class="dialog-body form-grid"><div class="field full"><label for="invite-email">邮箱地址</label><input class="input" id="invite-email" type="email" required placeholder="name@example.com"></div><div class="field full"><label for="invite-role">成员权限</label><select class="select" id="invite-role"><option>EDITOR - 可新增和修改交易</option><option>VIEWER - 只读访问</option></select><span class="field-help">只有 OWNER 可以管理成员或转让所有权。</span></div></div><div class="dialog-foot"><button class="btn" type="button" data-close>取消</button><button class="btn btn-primary" type="submit">发送邀请</button></div></form></div>`;
  }

  function calendarDialog() {
    const days = [27,28,29,30,31,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30];
    return `<div class="dialog" role="dialog" aria-modal="true" aria-labelledby="calendar-title" style="width:min(360px,100%)"><div class="dialog-head"><div><h2 id="calendar-title">选择日期范围</h2><p>当前筛选 2026年08月01日 至 08月12日</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="calendar"><div class="calendar-head"><button class="icon-btn" aria-label="上个月"><i data-lucide="chevron-left"></i></button><strong>2026年 8月</strong><button class="icon-btn" aria-label="下个月"><i data-lucide="chevron-right"></i></button></div><div class="calendar-grid">${["一","二","三","四","五","六","日"].map(x => `<span class="calendar-weekday">${x}</span>`).join("")}${days.map((d, i) => `<button class="calendar-day ${i < 5 || i > 34 ? "muted" : ""} ${i === 5 || i === 16 ? "selected" : ""} ${i === 16 ? "today" : ""}" data-calendar-day="${d}">${d}</button>`).join("")}</div></div><div class="dialog-foot"><button class="btn" data-close>取消</button><button class="btn btn-primary" data-action="apply-date">应用范围</button></div></div>`;
  }

  // 页面渲染器只选择对应屏幕，避免不同文件重复维护结构。
  function render() {
    const root = document.getElementById("app");
    // 启动器没有动态根节点，但仍需绑定主题与通用控件交互。
    if (!root) { refreshIcons(); bindInteractions(); return; }
    const renders = { dashboard: dashboardPage, accounts: accountsPage, transactions: transactionsPage, import: importPage, investments: investmentsPage, sharing: sharingPage, auth: authPage };
    if (renders[page]) root.innerHTML = renders[page]();
    refreshIcons();
    bindInteractions();
  }

  function makeOverlay(id, content, drawer = false, mobile = false) {
    let el = document.getElementById(id);
    if (!el) {
      el = document.createElement("div");
      el.id = id;
      el.className = `overlay ${drawer ? "drawer-overlay" : ""} ${mobile ? "mobile-sheet-overlay" : ""}`;
      document.body.appendChild(el);
    }
    el.innerHTML = content;
    el.classList.add("open");
    el.querySelector("input,button,select")?.focus();
    refreshIcons();
    return el;
  }

  function closeOverlay(el) { el?.classList.remove("open"); }
  function toast(title, message, type = "success") {
    // 成功反馈与日历完成状态共用圆形勾选，避免同一状态出现三套轮廓。
    const region = document.querySelector(".toast-region") || (() => { const n = document.createElement("div"); n.className = "toast-region"; n.setAttribute("aria-live", "polite"); document.body.appendChild(n); return n; })();
    const item = document.createElement("div"); item.className = `toast ${type === "error" ? "error" : ""}`;
    item.innerHTML = `<span class="toast-icon"><i data-lucide="${type === "error" ? "triangle-alert" : "circle-check"}"></i></span><div><strong>${title}</strong><p>${message}</p></div><button aria-label="关闭通知"><i data-lucide="x"></i></button>`;
    region.appendChild(item); refreshIcons();
    item.querySelector("button").onclick = () => item.remove();
    setTimeout(() => item.remove(), 4800);
  }

  function transactionExtra(type) {
    const el = document.getElementById("type-extra"); if (!el) return;
    const form = document.getElementById("record-form"); if (form) form.innerHTML = recordFields(type);
    const blocks = {
      "转账": `<div class="form-grid" style="margin-top:14px"><div class="field"><label for="record-rate">使用汇率</label><input class="input" id="record-rate" inputmode="decimal" value="7.1882"></div><div class="field"><label for="record-fee">手续费</label><input class="input" id="record-fee" inputmode="decimal" value="12.00"><span class="field-help">人民币 CNY</span></div></div><div class="notice" style="margin-top:14px"><i data-lucide="arrow-left-right"></i><div><strong>汇率换算说明</strong><p>USD 500.00 × 7.1882 = CNY 3,594.10，手续费另计 CNY 12.00。</p></div></div>`,
      "负债还款": `<div class="form-grid" style="margin-top:14px"><div class="field"><label>归还本金</label><input class="input" inputmode="decimal" value="8,000.00"></div><div class="field"><label>利息</label><input class="input" inputmode="decimal" value="36.20"></div><div class="field"><label>手续费</label><input class="input" inputmode="decimal" value="0.00"></div></div>`,
      "退款": `<div class="notice" style="margin-top:14px"><i data-lucide="undo-2"></i><div><strong>关联原交易</strong><p>选择原支出后，退款会继承账户与分类并保留关联关系。</p></div></div>`,
      "余额调整": `<div class="notice notice-danger" style="margin-top:14px"><i data-lucide="triangle-alert"></i><div><strong>调整不会创建收入或支出</strong><p>用于修正账面余额，保存后将记录调整原因与操作人。</p></div></div>`,
    };
    el.innerHTML = blocks[type] || ""; refreshIcons();
  }

  function bindInteractions() {
    document.addEventListener("click", event => {
      const theme = event.target.closest('[data-action="theme"]');
      if (theme) applyTheme(html.dataset.theme === "dark" ? "light" : "dark");

      const open = event.target.closest("[data-open]");
      if (open) {
        const id = open.dataset.open;
        if (id === "record") makeOverlay("record-overlay", recordDialog(false));
        else if (id === "theme-compare") makeOverlay("theme-compare-overlay", themeCompareDialog());
        else if (id === "invite") makeOverlay("invite-overlay", inviteDialog());
        else if (id === "command") document.getElementById("command")?.classList.add("open");
        // 冲突弹窗关闭按钮提供可访问名称，避免图标成为唯一未命名控件。
        else if (id === "conflict") makeOverlay("conflict-overlay", `<div class="dialog"><div class="dialog-head"><div><h2>解决同步冲突</h2><p>请选择要保留的交易版本。</p></div><button class="icon-btn" data-close aria-label="关闭同步冲突弹窗"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="notice notice-danger"><i data-lucide="cloud-off"></i><div><strong>金额字段存在差异</strong><p>设备记录为 ¥286.40，云端记录为 ¥268.40。</p></div></div><div class="detail-grid"><button class="detail-cell btn" data-action="choose-local"><span>iPhone 离线版本</span><strong>¥286.40</strong></button><button class="detail-cell btn" data-action="choose-cloud"><span>云端版本</span><strong>¥268.40</strong></button></div></div></div>`);
      }

      const close = event.target.closest("[data-close]");
      if (close) closeOverlay(close.closest(".overlay"));
      if (event.target.classList.contains("overlay")) closeOverlay(event.target);

      const dropdownTrigger = event.target.closest("[data-dropdown]");
      if (dropdownTrigger) {
        const menu = document.getElementById(dropdownTrigger.dataset.dropdown);
        const rect = dropdownTrigger.getBoundingClientRect();
        menu.style.top = `${rect.bottom + 8}px`; menu.style.right = `${window.innerWidth - rect.right}px`; menu.classList.toggle("open");
      }

      const type = event.target.closest("[data-type]");
      if (type) {
        type.parentElement.querySelectorAll(".segment").forEach(x => x.classList.remove("active")); type.classList.add("active"); transactionExtra(type.dataset.type);
      }
      if (event.target.closest("[data-submit-record]")) { closeOverlay(event.target.closest(".overlay")); toast("交易已保存", "盒马鲜生支出 ¥286.40 已计入招商银行 8286。"); }

      const account = event.target.closest("[data-account]");
      if (account) makeOverlay("detail-overlay", detailDialog("account", account.dataset.account), true);
      const transaction = event.target.closest("[data-transaction]");
      if (transaction) makeOverlay("detail-overlay", detailDialog("transaction", transaction.dataset.transaction), true);
      const holding = event.target.closest("[data-holding]");
      if (holding) makeOverlay("detail-overlay", detailDialog("holding", holding.dataset.holding), true);

      const filter = event.target.closest("[data-filter]");
      if (filter) {
        if (filter.dataset.filter === "date") makeOverlay("calendar-overlay", calendarDialog());
        else { filter.classList.toggle("active"); applyTransactionFilters(); toast("筛选已更新", `${filter.textContent.trim()}条件已应用。`); }
      }
      if (event.target.closest('[data-action="apply-date"]')) { closeOverlay(event.target.closest(".overlay")); toast("日期范围已应用", "正在显示 08月01日 至 08月12日的流水。"); }
      if (event.target.closest('[data-action="clear-filters"]')) { document.querySelectorAll("[data-filter]").forEach(x => x.classList.remove("active")); const date = document.querySelector('[data-filter="date"]'); if (date) date.classList.add("active"); const search = document.getElementById("transaction-search"); if (search) search.value = ""; applyTransactionFilters(); toast("筛选已清除", "正在显示全部流水。"); }

      const tab = event.target.closest("[data-tab]");
      if (tab) { document.querySelectorAll("[data-tab]").forEach(x => x.classList.toggle("active", x === tab)); document.querySelectorAll("[data-panel]").forEach(x => x.classList.toggle("active", x.dataset.panel === tab.dataset.tab)); }
      const authTab = event.target.closest("[data-auth-tab]");
      if (authTab) { document.querySelectorAll("[data-auth-tab]").forEach(x => x.classList.toggle("active", x === authTab)); document.getElementById("auth-content").innerHTML = authForm(authTab.dataset.authTab); }
      const authSwitch = event.target.closest("[data-auth-switch]"); if (authSwitch) document.getElementById("auth-content").innerHTML = authForm(authSwitch.dataset.authSwitch);

      const next = event.target.closest("[data-import-next]"); if (next) setImportStep(Math.min(5, Number(document.getElementById("import-stage").dataset.step || 0) + 1));
      const back = event.target.closest("[data-import-back]"); if (back) setImportStep(Math.max(0, Number(document.getElementById("import-stage").dataset.step || 0) - 1));

      const switchEl = event.target.closest(".switch"); if (switchEl) switchEl.setAttribute("aria-checked", switchEl.getAttribute("aria-checked") !== "true");
      if (event.target.closest('[data-action="void-transaction"]')) { closeOverlay(event.target.closest(".overlay")); toast("交易已作废", "原交易已保留，并创建金额相反的冲正记录。"); }
      if (event.target.closest('[data-action="undo-import"]')) toast("批次撤销待确认", "整批撤销将生成 418 条冲正记录。", "error");
      if (event.target.closest('[data-action="import-history"]')) makeOverlay("import-history-overlay", `<div class="dialog dialog-wide"><div class="dialog-head"><div><h2>导入批次记录</h2><p>成功、部分失败与整批失败均保留原文件摘要和错误信息。</p></div><button class="icon-btn" data-close aria-label="关闭"><i data-lucide="x"></i></button></div><div class="dialog-body"><div class="data-table-wrap"><table class="data-table"><thead><tr><th>批次</th><th>文件</th><th>结果</th><th>操作人</th><th></th></tr></thead><tbody><tr><td>IMP-042</td><td>cmb_2026-08.csv</td><td><span class="badge badge-warning">部分失败 1 条</span></td><td>林骁</td><td><button class="btn">整批撤销</button></td></tr><tr><td>IMP-038</td><td>alipay_2026-07.xlsx</td><td><span class="badge badge-success">成功 628 条</span></td><td>林骁</td><td><button class="btn">查看</button></td></tr><tr><td>IMP-031</td><td>wechat_2026-06.csv</td><td><span class="badge badge-danger">整批失败</span></td><td>顾宜宁</td><td><button class="btn">查看错误</button></td></tr></tbody></table></div></div></div>`);
      if (event.target.closest('[data-action^="choose-"]')) { closeOverlay(event.target.closest(".overlay")); toast("冲突已解决", "保留版本和另一版本均已写入审计记录。"); }
    });

    document.addEventListener("submit", event => {
      event.preventDefault();
      if (event.target.id === "invite-form") { closeOverlay(event.target.closest(".overlay")); toast("邀请已发送", "邀请邮件已发送，有效期 7 天。"); }
      if (event.target.dataset.form === "register" || event.target.dataset.form === "forgot") document.getElementById("auth-content").innerHTML = authForm("otp");
      if (event.target.dataset.form === "otp") { const error = document.getElementById("otp-error"); error.hidden = false; refreshIcons(); }
      if (event.target.dataset.form === "login") { const btn = event.target.querySelector("button[type=submit]"); btn.disabled = true; btn.innerHTML = '<i data-lucide="loader-circle"></i>登录中'; refreshIcons(); setTimeout(() => { btn.disabled = false; btn.textContent = "登录"; document.getElementById("login-error").hidden = false; }, 800); }
    });

    document.addEventListener("change", event => {
      if (event.target.id === "confirm-import") document.getElementById("confirm-import-button").disabled = !event.target.checked;
      if (event.target.id === "import-file") simulateUpload(event.target.files?.[0]);
    });
    document.addEventListener("input", event => { if (event.target.id === "transaction-search") applyTransactionFilters(); });
    document.getElementById("upload-zone")?.addEventListener("drop", event => { event.preventDefault(); simulateUpload(event.dataTransfer.files?.[0]); });
    document.getElementById("upload-zone")?.addEventListener("dragover", event => event.preventDefault());

    document.querySelectorAll("[data-tooltip]").forEach(el => {
      el.addEventListener("mouseenter", () => { const tip = document.createElement("div"); tip.className = "tooltip"; tip.textContent = el.dataset.tooltip; tip.id = "active-tooltip"; document.body.appendChild(tip); const r = el.getBoundingClientRect(); tip.style.top = `${r.bottom + 7}px`; tip.style.left = `${Math.min(r.left, window.innerWidth - tip.offsetWidth - 10)}px`; });
      el.addEventListener("mouseleave", () => document.getElementById("active-tooltip")?.remove());
    });
    document.addEventListener("keydown", event => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") { event.preventDefault(); document.getElementById("command")?.classList.add("open"); }
      if (event.key === "Escape") document.querySelectorAll(".overlay.open").forEach(closeOverlay);
      if ((event.key === "Enter" || event.key === " ") && event.target.matches("[data-account],[data-transaction],[data-holding]")) event.target.click();
    });
  }

  // 表格筛选真正改变可见结果，并保留键盘和读屏可读取的原始行。
  function applyTransactionFilters() {
    const query = (document.getElementById("transaction-search")?.value || "").trim().toLowerCase();
    const active = new Set(Array.from(document.querySelectorAll("[data-filter].active")).map(x => x.dataset.filter));
    document.querySelectorAll("#transaction-body tr").forEach(row => {
      const text = row.dataset.search.toLowerCase();
      const passSearch = !query || text.includes(query);
      const passAccount = !active.has("account") || text.includes("招商银行");
      const passCategory = !active.has("category") || text.includes("餐饮");
      const passTag = !active.has("tag") || text.includes("盒马") || text.includes("退款");
      const passType = !active.has("type") || text.includes("退款") || text.includes("还款") || text.includes("转账");
      const amount = Number((text.match(/[+-]?[¥$]?([\d,]+\.\d{2})/)?.[1] || "0").replaceAll(",", ""));
      const passAmount = !active.has("amount") || amount >= 100;
      row.hidden = !(passSearch && passAccount && passCategory && passTag && passType && passAmount);
    });
  }

  function setImportStep(step) {
    const stage = document.getElementById("import-stage"); if (!stage) return;
    stage.dataset.step = step; stage.innerHTML = importStage(step);
    document.querySelectorAll("[data-import-step]").forEach(el => { const n = Number(el.dataset.importStep); el.classList.toggle("active", n === step); el.classList.toggle("done", n < step); });
    refreshIcons();
  }
  function simulateUpload(file) {
    const stage = document.getElementById("import-stage"); if (!stage) return;
    stage.innerHTML = `<div class="panel-body"><div class="empty-state"><i data-lucide="loader-circle"></i><h3>正在解析 ${file?.name || "cmb_2026-08.csv"}</h3><p>已读取 286 / 428 行，正在识别日期和金额格式。</p><div class="progress" style="width:min(380px,100%)"><span style="width:67%"></span></div></div></div>`; refreshIcons();
    setTimeout(() => setImportStep(1), 900);
  }

  window.Ziji = { webShell, chart, recordDialog, makeOverlay, toast, applyTheme, refreshIcons };
  render();
  // Lucide 使用 defer 加载，DOMContentLoaded 时再替换动态生成的图标节点。
  window.addEventListener("DOMContentLoaded", refreshIcons, { once: true });
})();
