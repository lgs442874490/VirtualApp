(function () {
  "use strict";
  const store = window.ProtocolStore;
  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => Array.from(document.querySelectorAll(selector));
  const categoryColors = { "奖励": "#7c6ee6", "城镇": "#3b8dc4", "活动": "#d68642", "野外": "#38a07d", "联盟": "#5475d8", "商店": "#c4719d", "系统": "#728096", "独立工具": "#9b7b54" };
  const icons = {
    library: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v16H6.5A2.5 2.5 0 0 0 4 21.5z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v16h4.5a2.5 2.5 0 0 1 2.5 2.5z"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    layers: '<path d="m12 3 9 5-9 5-9-5z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>', reset: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>', search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    filter: '<path d="M4 5h16M7 12h10M10 19h4"/>', close: '<path d="m6 6 12 12M18 6 6 18"/>',
    chevron: '<path d="m9 18 6-6-6-6"/>', edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 15H6L5 6M10 11v5M14 11v5"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    send: '<path d="m22 2-7 20-4-9-9-4z"/><path d="M22 2 11 13"/>', download: '<path d="M12 3v12m-5-5 5 5 5-5M5 21h14"/>',
    inbox: '<path d="M4 4h16v16H4z"/><path d="M4 14h4l2 3h4l2-3h4"/>', code: '<path d="m8 9-4 3 4 3M16 9l4 3-4 3M14 5l-4 14"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/>',
    database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>',
    check: '<path d="M20 6 9 17l-5-5"/>', terminal: '<path d="m4 17 6-5-6-5M12 19h8"/>', book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z"/>',
    arrowDown: '<path d="M12 5v14m-6-6 6 6 6-6"/>', arrowUp: '<path d="M12 19V5m-6 6 6-6 6 6"/>', link: '<path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1.1 1.1M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1.1-1.1"/>'
  };
  function icon(name) { return `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.code}</svg>`; }
  function esc(value) { return String(value == null ? "" : value).replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" }[char])); }
  function toast(message) {
    const node = document.createElement("div"); node.className = "toast"; node.textContent = message;
    $("#toastRegion").appendChild(node); setTimeout(() => node.remove(), 2800);
  }
  function hydrateIcons(root) { (root || document).querySelectorAll("[data-icon]").forEach((node) => { node.innerHTML = icon(node.dataset.icon); }); }
  function filteredRecords(state) {
    const f = state.filters; const query = f.query.trim().toLowerCase();
    const list = state.records.filter((item) => {
      const haystack = [item.name, item.summary, item.feature, item.parameters, item.dataScope, item.evidence, ...(item.tags || [])].join(" ").toLowerCase();
      return (!query || haystack.includes(query)) && (!f.category || item.category === f.category) && (!f.direction || item.direction === f.direction) && (!f.source || item.source === f.source) && (!f.status || item.status === f.status);
    });
    return list.sort((a, b) => f.sort === "name" ? a.name.localeCompare(b.name) : f.sort === "category" ? a.category.localeCompare(b.category, "zh-CN") || a.name.localeCompare(b.name) : (b.verifiedAt || b.updatedAt).localeCompare(a.verifiedAt || a.updatedAt));
  }
  function renderStats(state) {
    const verified = state.records.filter((x) => x.status === "已实证").length;
    const features = new Set(state.records.map((x) => x.feature)).size;
    const requests = state.records.filter((x) => x.direction === "请求").length;
    const stats = [
      { n: state.records.length, label: "已收录协议", note: "集中管理", icon: "database", color: "#5364e8", tint: "#eef0ff" },
      { n: verified, label: "完成实证", note: `${state.records.length ? Math.round(verified / state.records.length * 100) : 0}% 覆盖率`, icon: "shield", color: "#16866b", tint: "#e9f7f2" },
      { n: features, label: "可支撑功能", note: "直接复用", icon: "grid", color: "#b47529", tint: "#fff5e6" },
      { n: requests, label: "主动请求协议", note: `${state.records.length - requests} 条推送 / 响应`, icon: "send", color: "#397fae", tint: "#eaf5fb" }
    ];
    $("#statsGrid").innerHTML = stats.map((s) => `<article class="stat-card" style="--color:${s.color};--tint:${s.tint}"><div class="stat-icon">${icon(s.icon)}</div><div class="stat-info"><strong>${s.n}</strong><span>${s.label}</span></div><span class="stat-note">${s.note}</span></article>`).join("");
  }
  function renderNavigation(state) {
    $("#navCount").textContent = state.records.length;
    $("#categoryNav").innerHTML = store.categories.map((category) => {
      const count = state.records.filter((item) => item.category === category).length;
      return `<button class="category-btn" data-category="${category}" style="--cat:${categoryColors[category]}"><i></i>${category}<b>${count}</b></button>`;
    }).join("");
    $$(".nav-item").forEach((button) => button.classList.toggle("active", button.dataset.view === state.view));
    $$(".view").forEach((view) => view.classList.toggle("active", view.id === `${state.view}View`));
    $("#viewTitle").textContent = { library: "协议库", features: "功能索引", sources: "证据来源" }[state.view];
  }
  function renderList(state) {
    const records = filteredRecords(state); const f = state.filters;
    $("#resultCount").textContent = records.length;
    const activeFilters = [f.category, f.direction, f.source, f.status].filter(Boolean);
    $("#filterCount").hidden = !activeFilters.length; $("#filterCount").textContent = activeFilters.length;
    $("#filterDescription").textContent = f.query || activeFilters.length ? "· 当前筛选结果" : "· 全部已收录";
    $("#protocolList").innerHTML = records.map((item) => `<article class="protocol-row ${item.id === state.selectedId ? "selected" : ""}" data-id="${esc(item.id)}" tabindex="0">
      <div class="proto-name"><div class="proto-direction ${item.direction === "推送" ? "push" : ""}">${icon(item.direction === "推送" ? "arrowDown" : item.direction === "响应" ? "inbox" : "arrowUp")}</div><div class="proto-name-text"><strong title="${esc(item.name)}">${esc(item.name)}</strong><span>${esc(item.feature)} · ${esc(item.summary)}</span></div></div>
      <span class="category-chip" style="--cat:${categoryColors[item.category]}"><i></i>${esc(item.category)}</span>
      <div class="source-cell"><span>${esc(item.source)}</span><b class="status-badge ${item.status === "部分实证" ? "partial" : ""}">${esc(item.status)}</b></div>
      <span class="date-cell">${esc(item.verifiedAt || "未注明")}</span><button class="row-action" aria-label="查看详情">${icon("chevron")}</button></article>`).join("");
    $("#protocolList").hidden = !records.length; $("#emptyState").hidden = !!records.length;
  }
  function info(label, value, fallback) { return `<div class="info-item"><label>${label}</label><p>${esc(value || fallback || "未记录，开发前需补充实测。")}</p></div>`; }
  function renderDetail(state) {
    const item = state.records.find((record) => record.id === state.selectedId);
    if (!item) { $("#detailPanel").innerHTML = `<div class="detail-empty">${icon("book")}<p>选择一条协议查看完整开发档案</p></div>`; return; }
    $("#detailPanel").innerHTML = `<div class="detail-head"><div class="detail-top"><span class="record-id">${esc(item.id)} / ${esc(item.direction)}协议</span><div class="detail-actions"><button data-action="copy" title="复制协议名">${icon("copy")}</button><button data-action="edit" title="编辑协议">${icon("edit")}</button><button data-action="delete" class="delete" title="删除协议">${icon("trash")}</button></div></div><h2>${esc(item.name)}</h2><p>${esc(item.summary)}</p><div class="detail-meta"><span class="meta-chip verified">${esc(item.status)}</span><span class="meta-chip">${esc(item.category)} · ${esc(item.feature)}</span><span class="meta-chip">${esc(item.method)}</span></div></div>
      <section class="detail-section"><h3><span>${icon("layers")}</span>来源与获取</h3><div class="info-list">${info("证据来源", item.source)}${info("获取方式", item.acquisition)}${info("验证日期", item.verifiedAt, "未注明")}</div></section>
      <section class="detail-section"><h3><span>${icon("shield")}</span>提交与获取边界</h3><div class="boundary-grid">${info("请求方式", item.method)}${info("鉴权要求", item.auth)}${info("参数与限制", item.parameters)}${info("可获取的数据范围", item.dataScope)}${info("提交限制 / 注意事项", item.limitations)}</div></section>
      <section class="detail-section"><h3><span>${icon("terminal")}</span>功能实现步骤</h3><ol class="steps">${item.implementationSteps.map((step) => `<li>${esc(step)}</li>`).join("")}</ol></section>
      <section class="detail-section"><h3><span>${icon("check")}</span>实证依据</h3><div class="evidence-box">${esc(item.evidence || "尚未关联实证材料，请补充日志或文档出处。")}</div></section>
      <section class="detail-section"><h3><span>${icon("link")}</span>索引与关联</h3><div class="tag-list">${(item.tags || []).map((tag) => `<span class="tag"># ${esc(tag)}</span>`).join("") || "<span class=tag>暂无标签</span>"}</div><div class="related-list" style="margin-top:10px">${(item.related || []).map((name) => `<button class="related-btn" data-related="${esc(name)}">${esc(name)}</button>`).join("")}</div></section>`;
  }
  function renderFeatures(state) {
    const groups = new Map();
    state.records.forEach((item) => { if (!groups.has(item.feature)) groups.set(item.feature, []); groups.get(item.feature).push(item); });
    $("#featureGrid").innerHTML = Array.from(groups.entries()).sort((a, b) => b[1].length - a[1].length).map(([feature, items]) => `<article class="feature-card" data-feature="${esc(feature)}"><div class="feature-card-top"><div class="feature-card-icon">${icon("grid")}</div><span class="category-chip" style="--cat:${categoryColors[items[0].category]}"><i></i>${esc(items[0].category)}</span></div><h3>${esc(feature)}</h3><p>${items.length} 条协议构成开发清单 · ${items.filter((x) => x.direction === "请求").length} 条主动请求 · ${items.filter((x) => x.direction === "推送").length} 条状态推送</p><div class="feature-protocols">${items.slice(0, 4).map((x) => `<code>${esc(x.name)}</code>`).join("")}${items.length > 4 ? `<code>+${items.length - 4}</code>` : ""}</div></article>`).join("");
  }
  function renderSources(state) {
    const descriptions = { "ProtoLogger 登录快照": "登录 init 全量快照，适合确认初始状态字段与业务协议覆盖。", "ProtoLogger 事件监听": "在线实时事件证据，适合构建推送驱动和响应式自动化。", "网页版静态分析": "来自反编译调用链，需区分代码证据与线上动态实测。", "网页版移植实测": "已有功能移植与实测记录，可直接支撑同类功能开发。", "人工受控实测": "研发人员通过受控操作补充的协议、参数和边界证据。" };
    const sources = Array.from(new Set([...store.sources, ...state.records.map((x) => x.source)]));
    $("#sourceGrid").innerHTML = sources.map((source) => { const items = state.records.filter((x) => x.source === source); return `<article class="source-card"><span class="eyebrow">EVIDENCE SOURCE</span><h3>${esc(source)}</h3><div class="source-number">${items.length}</div><p>${esc(descriptions[source] || "自定义协议证据来源。")}</p><button data-source="${esc(source)}">查看该来源协议 →</button></article>`; }).join("");
  }
  function syncFilterControls(state) {
    const f = state.filters;
    $("#searchInput").value = f.query; $("#categoryFilter").value = f.category; $("#directionFilter").value = f.direction; $("#sourceFilter").value = f.source; $("#statusFilter").value = f.status; $("#sortSelect").value = f.sort;
  }
  function render(state) {
    renderNavigation(state); renderStats(state); renderList(state); renderDetail(state); renderFeatures(state); renderSources(state); syncFilterControls(state);
  }
  function selectRecord(id) { store.dispatch({ type: "protocol/selected", payload: id }); if (innerWidth <= 1050) $("#detailPanel").scrollIntoView({ behavior: "smooth", block: "start" }); }
  function openEditor(item) {
    $("#editorTitle").textContent = item ? "编辑协议档案" : "收录新协议";
    $("#recordId").value = item ? item.id : "";
    const values = { fName: item?.name || "", fCategory: item?.category || "奖励", fDirection: item?.direction || "推送", fFeature: item?.feature || "", fSummary: item?.summary || "", fStatus: item?.status || "已实证", fVerifiedAt: item?.verifiedAt || new Date().toISOString().slice(0, 10), fTags: (item?.tags || []).join(", "), fSource: item?.source || "ProtoLogger 事件监听", fMethod: item?.method || "被动监听", fAcquisition: item?.acquisition || "", fParameters: item?.parameters || "", fAuth: item?.auth || "", fLimitations: item?.limitations || "", fDataScope: item?.dataScope || "", fSteps: (item?.implementationSteps || []).join("\n"), fEvidence: item?.evidence || "", fRelated: (item?.related || []).join(", ") };
    Object.entries(values).forEach(([id, value]) => { $(`#${id}`).value = value; });
    $("#editorModal").hidden = false; document.body.style.overflow = "hidden"; setTimeout(() => $("#fName").focus(), 50);
  }
  function closeModal(id) { $(`#${id}`).hidden = true; if ($$(".modal-backdrop:not([hidden])").length === 0) document.body.style.overflow = ""; }
  function initSelects() {
    const allSources = Array.from(new Set([...store.sources, ...store.getState().records.map((x) => x.source)]));
    $("#categoryFilter").innerHTML += store.categories.map((x) => `<option>${x}</option>`).join("");
    $("#sourceFilter").innerHTML += allSources.map((x) => `<option>${esc(x)}</option>`).join("");
    $("#fCategory").innerHTML = store.categories.map((x) => `<option>${x}</option>`).join("");
    $("#fSource").innerHTML = allSources.map((x) => `<option>${esc(x)}</option>`).join("");
  }

  hydrateIcons(); initSelects(); render(store.getState()); store.subscribe(render);
  $$(".nav-item").forEach((button) => button.addEventListener("click", () => { store.dispatch({ type: "view/changed", payload: button.dataset.view }); $("#sidebar").classList.remove("open"); }));
  $("#categoryNav").addEventListener("click", (event) => { const button = event.target.closest("[data-category]"); if (!button) return; store.dispatch({ type: "filters/cleared" }); store.dispatch({ type: "filters/changed", payload: { category: button.dataset.category } }); store.dispatch({ type: "view/changed", payload: "library" }); $("#sidebar").classList.remove("open"); });
  const filterBindings = { searchInput: "query", categoryFilter: "category", directionFilter: "direction", sourceFilter: "source", statusFilter: "status", sortSelect: "sort" };
  Object.entries(filterBindings).forEach(([id, key]) => $(`#${id}`).addEventListener(id === "searchInput" ? "input" : "change", (event) => store.dispatch({ type: "filters/changed", payload: { [key]: event.target.value } })));
  function clearFilters() { store.dispatch({ type: "filters/cleared" }); }
  $("#clearFilters").addEventListener("click", clearFilters); $("#emptyClear").addEventListener("click", clearFilters);
  $("#filterToggle").addEventListener("click", () => $("#filters").classList.toggle("open"));
  $("#protocolList").addEventListener("click", (event) => { const row = event.target.closest("[data-id]"); if (row) selectRecord(row.dataset.id); });
  $("#protocolList").addEventListener("keydown", (event) => { if ((event.key === "Enter" || event.key === " ") && event.target.dataset.id) { event.preventDefault(); selectRecord(event.target.dataset.id); } });
  $("#detailPanel").addEventListener("click", async (event) => {
    const related = event.target.closest("[data-related]");
    if (related) { const item = store.getState().records.find((x) => x.name === related.dataset.related); if (item) { store.dispatch({ type: "filters/cleared" }); store.dispatch({ type: "view/changed", payload: "library" }); selectRecord(item.id); } else { store.dispatch({ type: "filters/cleared" }); store.dispatch({ type: "filters/changed", payload: { query: related.dataset.related } }); store.dispatch({ type: "view/changed", payload: "library" }); } return; }
    const action = event.target.closest("[data-action]")?.dataset.action; const item = store.getState().records.find((x) => x.id === store.getState().selectedId); if (!action || !item) return;
    if (action === "edit") openEditor(item);
    if (action === "delete") { $("#confirmText").textContent = `即将从本地知识库删除 ${item.name}。建议先导出备份，此操作不会影响原始资料。`; $("#confirmModal").hidden = false; document.body.style.overflow = "hidden"; }
    if (action === "copy") { try { await navigator.clipboard.writeText(item.name); toast("协议名已复制"); } catch { toast(`协议名：${item.name}`); } }
  });
  $("#addBtn").addEventListener("click", () => openEditor());
  $$('[data-close]').forEach((button) => button.addEventListener("click", () => closeModal(button.dataset.close)));
  $$(".modal-backdrop").forEach((backdrop) => backdrop.addEventListener("mousedown", (event) => { if (event.target === backdrop) closeModal(backdrop.id); }));
  $("#protocolForm").addEventListener("submit", (event) => {
    event.preventDefault(); const id = $("#recordId").value; const list = (value) => value.split(/[,，\n]/).map((x) => x.trim()).filter(Boolean);
    const payload = { id, name: $("#fName").value, category: $("#fCategory").value, direction: $("#fDirection").value, feature: $("#fFeature").value, summary: $("#fSummary").value, status: $("#fStatus").value, verifiedAt: $("#fVerifiedAt").value, tags: list($("#fTags").value), source: $("#fSource").value, method: $("#fMethod").value, acquisition: $("#fAcquisition").value, parameters: $("#fParameters").value, auth: $("#fAuth").value, limitations: $("#fLimitations").value, dataScope: $("#fDataScope").value, implementationSteps: list($("#fSteps").value), evidence: $("#fEvidence").value, related: list($("#fRelated").value) };
    const success = store.dispatch({ type: id ? "protocol/updated" : "protocol/added", payload }); if (success) { closeModal("editorModal"); store.dispatch({ type: "view/changed", payload: "library" }); toast(id ? "协议档案已更新" : "新协议已收录"); }
  });
  $("#confirmDelete").addEventListener("click", () => { store.dispatch({ type: "protocol/deleted", payload: store.getState().selectedId }); closeModal("confirmModal"); toast("协议已删除，可通过导入备份恢复"); });
  $("#featureGrid").addEventListener("click", (event) => { const card = event.target.closest("[data-feature]"); if (!card) return; store.dispatch({ type: "filters/cleared" }); store.dispatch({ type: "filters/changed", payload: { query: card.dataset.feature } }); store.dispatch({ type: "view/changed", payload: "library" }); });
  $("#sourceGrid").addEventListener("click", (event) => { const button = event.target.closest("[data-source]"); if (!button) return; store.dispatch({ type: "filters/cleared" }); store.dispatch({ type: "filters/changed", payload: { source: button.dataset.source } }); store.dispatch({ type: "view/changed", payload: "library" }); });
  $("#exportBtn").addEventListener("click", () => { const blob = new Blob([store.exportData()], { type: "application/json" }); const url = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = url; link.download = `protohub-backup-${new Date().toISOString().slice(0, 10)}.json`; link.click(); URL.revokeObjectURL(url); toast("知识库备份已导出"); });
  $("#importBtn").addEventListener("click", () => $("#importFile").click());
  $("#importFile").addEventListener("change", async (event) => { const file = event.target.files[0]; if (!file) return; try { const data = JSON.parse(await file.text()); const records = Array.isArray(data) ? data : data.records; if (!confirm(`将导入 ${records?.length || 0} 条协议并替换当前知识库。建议先导出当前备份，是否继续？`)) return; if (store.dispatch({ type: "data/imported", payload: records })) toast("知识库导入成功"); } catch (error) { toast(`导入失败：${error.message}`); } finally { event.target.value = ""; } });
  $("#resetBtn").addEventListener("click", () => { if (confirm("恢复初始数据会替换当前浏览器内的新增和修改。请先导出备份，确认继续？")) { store.dispatch({ type: "data/reset" }); toast("已恢复初始知识库"); } });
  $("#menuBtn").addEventListener("click", () => $("#sidebar").classList.toggle("open"));
  document.addEventListener("keydown", (event) => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") { event.preventDefault(); store.dispatch({ type: "view/changed", payload: "library" }); $("#searchInput").focus(); } if (event.key === "Escape") $$(".modal-backdrop:not([hidden])").forEach((modal) => closeModal(modal.id)); });
  window.addEventListener("validation/error", (event) => toast(event.detail.message)); window.addEventListener("persistence/error", () => toast("本地保存失败，请立即导出备份"));
})();
