/* 单向数据流 Store：UI 只派发 action，所有变更统一经过 reducer 与持久化层。 */
(function () {
  "use strict";
  const STORAGE_KEY = "protohub.knowledge.v1";
  const CATEGORIES = ["奖励", "城镇", "活动", "野外", "联盟", "商店", "系统", "独立工具"];
  const SOURCES = ["ProtoLogger 登录快照", "ProtoLogger 事件监听", "网页版静态分析", "网页版移植实测", "人工受控实测", "当前项目代码索引", "项目真机与生产实测", "命名规约推断"];
  const requiredFields = ["name", "category", "direction", "feature", "summary", "acquisition"];

  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  function cleanText(value) { return String(value == null ? "" : value).trim(); }
  function normalize(record, index) {
    const item = record && typeof record === "object" ? record : {};
    return {
      id: cleanText(item.id) || `P${String(index + 1).padStart(3, "0")}`,
      name: cleanText(item.name), summary: cleanText(item.summary),
      category: CATEGORIES.includes(item.category) ? item.category : "系统",
      direction: ["推送", "请求", "响应"].includes(item.direction) ? item.direction : "请求",
      feature: cleanText(item.feature), status: ["已实证", "部分实证", "待实证", "命名推测"].includes(item.status) ? item.status : "待实证",
      source: cleanText(item.source) || "人工受控实测", acquisition: cleanText(item.acquisition),
      implementationSteps: Array.isArray(item.implementationSteps) ? item.implementationSteps.map(cleanText).filter(Boolean) : [],
      method: cleanText(item.method) || "游戏 TCP sproto", parameters: cleanText(item.parameters),
      auth: cleanText(item.auth), dataScope: cleanText(item.dataScope), limitations: cleanText(item.limitations),
      evidence: cleanText(item.evidence), verifiedAt: cleanText(item.verifiedAt),
      tags: Array.isArray(item.tags) ? item.tags.map(cleanText).filter(Boolean) : [],
      related: Array.isArray(item.related) ? item.related.map(cleanText).filter(Boolean) : [],
      updatedAt: cleanText(item.updatedAt) || new Date().toISOString()
    };
  }
  function validate(record, records, originalId) {
    const missing = requiredFields.filter((key) => !cleanText(record[key]));
    if (missing.length) throw new Error("请完整填写协议名称、分类、方向、功能、说明和获取方式");
    if (!record.implementationSteps.length) throw new Error("至少需要一条具体实现步骤");
    const duplicate = records.some((item) => item.name.toLowerCase() === record.name.toLowerCase() && item.id !== originalId);
    if (duplicate) throw new Error(`协议 ${record.name} 已存在，请直接编辑原条目`);
  }
  function seedRecords() {
    const overrides = window.PROJECT_PROTOCOL_OVERRIDES || {};
    const base = clone(window.PROTOCOL_SEED).map((item) => ({ ...item, ...(overrides[item.name] || {}) }));
    return base.concat(clone(window.PROJECT_PROTOCOL_SEED || [])).map(normalize);
  }
  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return seedRecords();
      const parsed = JSON.parse(raw);
      if (!parsed || !Array.isArray(parsed.records)) throw new Error("invalid schema");
      const saved = parsed.records.map(normalize);
      if (parsed.schemaVersion >= 2) return saved;
      // v1 → v2：保留用户编辑，只追加新协议并补充有硬证据的项目实证结论。
      const byName = new Map(saved.map((item) => [item.name.toLowerCase(), item]));
      const overrides = window.PROJECT_PROTOCOL_OVERRIDES || {};
      Object.entries(overrides).forEach(([name, patch]) => {
        const existing = byName.get(name.toLowerCase());
        if (existing) Object.assign(existing, clone(patch));
      });
      seedRecords().forEach((item) => { if (!byName.has(item.name.toLowerCase())) saved.push(item); });
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ schemaVersion: 2, savedAt: new Date().toISOString(), records: saved }));
      return saved;
    } catch (error) {
      console.warn("本地数据读取失败，已回退到初始知识库", error);
      return seedRecords();
    }
  }
  function reducer(state, action) {
    const records = clone(state.records);
    switch (action.type) {
      case "protocol/added": return { ...state, records: [action.payload, ...records], selectedId: action.payload.id, revision: state.revision + 1 };
      case "protocol/updated": return { ...state, records: records.map((item) => item.id === action.payload.id ? action.payload : item), selectedId: action.payload.id, revision: state.revision + 1 };
      case "protocol/deleted": {
        const remaining = records.filter((item) => item.id !== action.payload);
        return { ...state, records: remaining, selectedId: state.selectedId === action.payload ? (remaining[0] || {}).id : state.selectedId, revision: state.revision + 1 };
      }
      case "protocol/selected": return { ...state, selectedId: action.payload };
      case "filters/changed": return { ...state, filters: { ...state.filters, ...action.payload } };
      case "filters/cleared": return { ...state, filters: { query: "", category: "", direction: "", source: "", status: "", sort: "updated" } };
      case "view/changed": return { ...state, view: action.payload };
      case "data/imported": return { ...state, records: action.payload, selectedId: (action.payload[0] || {}).id, revision: state.revision + 1 };
      case "data/reset": return { ...state, records: seedRecords(), selectedId: "P001", filters: { query: "", category: "", direction: "", source: "", status: "", sort: "updated" }, revision: state.revision + 1 };
      default: return state;
    }
  }

  const state = { records: load(), selectedId: "P001", view: "library", revision: 0, filters: { query: "", category: "", direction: "", source: "", status: "", sort: "updated" } };
  if (!state.records.some((item) => item.id === state.selectedId)) state.selectedId = (state.records[0] || {}).id;
  const listeners = new Set();
  let saveTimer;
  const store = {
    getState() { return state; },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    dispatch(action) {
      try {
        if (["protocol/added", "protocol/updated"].includes(action.type)) {
          const originalId = action.type === "protocol/updated" ? action.payload.id : null;
          validate(action.payload, state.records, originalId);
          action = { ...action, payload: normalize({ ...action.payload, updatedAt: new Date().toISOString() }, state.records.length) };
          if (action.type === "protocol/added") {
            const max = Math.max(0, ...state.records.map((item) => Number(item.id.replace(/\D/g, "")) || 0));
            action.payload.id = `P${String(max + 1).padStart(3, "0")}`;
          }
        }
        if (action.type === "data/imported") {
          if (!Array.isArray(action.payload) || !action.payload.length) throw new Error("备份中没有可导入的协议记录");
          const names = new Set();
          action.payload = action.payload.map((item, index) => {
            const record = normalize(item, index);
            validate(record, [], null);
            if (names.has(record.name.toLowerCase())) throw new Error(`备份中存在重复协议：${record.name}`);
            names.add(record.name.toLowerCase());
            return record;
          });
        }
        const previousRevision = state.revision;
        Object.assign(state, reducer(state, action));
        if (state.revision !== previousRevision) {
          const saveState = document.getElementById("saveState");
          if (saveState) { saveState.classList.add("saving"); saveState.innerHTML = "<i></i>正在保存…"; }
          clearTimeout(saveTimer);
          saveTimer = setTimeout(() => {
            try {
              localStorage.setItem(STORAGE_KEY, JSON.stringify({ schemaVersion: 2, savedAt: new Date().toISOString(), records: state.records }));
              if (saveState) { saveState.classList.remove("saving"); saveState.innerHTML = "<i></i>所有更改已保存"; }
            } catch (error) {
              if (saveState) saveState.innerHTML = "<i></i>保存失败，请导出备份";
              store.emit("persistence/error", error);
            }
          }, 180);
        }
        listeners.forEach((listener) => listener(state, action));
        return true;
      } catch (error) {
        store.emit("validation/error", error);
        return false;
      }
    },
    emit(type, error) { window.dispatchEvent(new CustomEvent(type, { detail: error })); },
    exportData() { return JSON.stringify({ schemaVersion: 2, exportedAt: new Date().toISOString(), records: state.records }, null, 2); },
    categories: CATEGORIES,
    sources: SOURCES
  };
  window.ProtocolStore = store;
})();
