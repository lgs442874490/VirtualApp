/* ==========================================================================
 * 管理后台 · 单页应用
 * 原生 ES2020，无框架 / 无外部依赖。
 * 约定：所有动态文本一律通过 textContent 写入，禁止把不可信数据拼进 innerHTML。
 * ========================================================================== */

(function () {
  'use strict';

  /* ======================================================================
   * 常量
   * ====================================================================== */

  var PAGE_SIZES = [20, 50, 100];
  var LOG_PAGE_SIZES = [50, 100];
  var AUDIT_PAGE_SIZES = [20, 50, 100];

  var USAGE_LABEL = {
    unused: '未使用',
    active: '已激活',
    expired: '已过期',
    revoked: '已停用',
    exhausted: '已用尽'
  };

  var USAGE_BADGE = {
    unused: 'badge--info',
    active: 'badge--success',
    expired: 'badge--warn',
    revoked: 'badge--danger',
    exhausted: 'badge--muted'
  };

  var TYPE_LABEL = { month: '月卡', permanent: '永久' };

  var BINDING_STATUS_LABEL = {
    active: '绑定中',
    released: '已解绑',
    revoked: '已停用',
    expired: '已过期'
  };

  var ACTION_LABEL = {
    activate: '激活',
    verify: '校验',
    rebind: '换绑',
    revoke: '停用',
    restore: '启用',
    login: '登录',
    logout: '退出登录',
    generate: '生成兑换码',
    matrix_save: '保存矩阵',
    matrix_reset: '重置矩阵',
    batch_revoke: '批量停用',
    batch_restore: '批量启用',
    export_csv: '导出 CSV'
  };

  /* ======================================================================
   * 状态
   * ====================================================================== */

  var state = {
    authenticated: false,
    navSeq: 0,
    codes: {
      filters: { status: '', usage: '', tier: '', type: '', q: '', from: '', to: '' },
      sort: 'created_desc',
      page: 1,
      size: 20,
      total: 0,
      items: [],
      selected: new Set()
    },
    detail: {
      loadedId: '',
      id: '',
      code: null,
      bindings: [],
      verifyCount: 0,
      logFilters: { action: '', result: '', from: '', to: '' },
      logPage: 1,
      logSize: 50,
      logTotal: 0,
      logItems: []
    },
    matrix: {
      rows: [],
      showImplementedOnly: false
    },
    features: {
      items: [],
      defaultTier: '',
      implemented: ''
    },
    audit: {
      page: 1,
      size: 50,
      total: 0,
      items: []
    }
  };

  /* ======================================================================
   * DOM 小工具
   * ====================================================================== */

  function $(selector, root) {
    return (root || document).querySelector(selector);
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'className') {
          node.className = value;
        } else if (key === 'text') {
          node.textContent = value === null || value === undefined ? '' : String(value);
        } else if (key === 'value') {
          node.value = value;
        } else if (key === 'checked' || key === 'disabled' || key === 'selected' || key === 'required' || key === 'readOnly' || key === 'multiple') {
          node[key] = !!value;
        } else if (key === 'dataset' && typeof value === 'object') {
          Object.keys(value).forEach(function (dataKey) {
            node.dataset[dataKey] = String(value[dataKey]);
          });
        } else if (key === 'style' && typeof value === 'object') {
          Object.keys(value).forEach(function (styleKey) {
            node.style[styleKey] = value[styleKey];
          });
        } else if (key.slice(0, 2) === 'on' && typeof value === 'function') {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else {
          node.setAttribute(key, value === true ? '' : String(value));
        }
      });
    }
    appendChildren(node, children);
    return node;
  }

  function appendChildren(node, children) {
    if (children === null || children === undefined) return;
    if (Array.isArray(children)) {
      children.forEach(function (child) {
        appendChildren(node, child);
      });
      return;
    }
    if (children instanceof Node) {
      node.appendChild(children);
      return;
    }
    node.appendChild(document.createTextNode(String(children)));
  }

  /** 兜底转义函数：仅用于必须拼接 HTML 的场景，正常渲染走 textContent。 */
  function escapeHtml(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function setText(node, value) {
    if (node) node.textContent = value === null || value === undefined ? '' : String(value);
  }

  /* ======================================================================
   * 格式化
   * ====================================================================== */

  function fmtTime(ms) {
    if (ms === null || ms === undefined || ms === '' || ms === 0) return '—';
    var value = Number(ms);
    if (isNaN(value)) return '—';
    var date = new Date(value);
    if (isNaN(date.getTime())) return '—';
    return date.toLocaleString('zh-CN', { hour12: false });
  }

  function fmtNumber(value) {
    if (value === null || value === undefined || value === '') return '0';
    var number = Number(value);
    if (isNaN(number)) return '—';
    return number.toLocaleString('zh-CN');
  }

  function truncate(value, max) {
    var text = value === null || value === undefined ? '' : String(value);
    if (text.length <= max) return text;
    return text.slice(0, max) + '…';
  }

  function tierText(item) {
    if (!item) return '—';
    if (item.isGm || Number(item.tier) === 4 || String(item.tier) === 'gm') return 'GM';
    if (item.tier === null || item.tier === undefined || item.tier === '') return '—';
    return 'T' + item.tier;
  }

  function typeText(type) {
    return TYPE_LABEL[type] || type || '—';
  }

  function actionText(action) {
    return ACTION_LABEL[action] || action || '—';
  }

  function remainText(days) {
    if (days === -1) return '永久';
    if (days === -2 || days === null || days === undefined || days === '') return '—';
    var number = Number(days);
    if (isNaN(number)) return '—';
    if (number <= 0) return '0 天';
    return number + ' 天';
  }

  function todayStamp() {
    var date = new Date();
    var month = String(date.getMonth() + 1).padStart(2, '0');
    var day = String(date.getDate()).padStart(2, '0');
    return String(date.getFullYear()) + month + day;
  }

  function dateRangeQuery(filters) {
    var query = {};
    if (filters.from) {
      var from = new Date(filters.from + 'T00:00:00');
      if (!isNaN(from.getTime())) query.from = from.getTime();
    }
    if (filters.to) {
      var to = new Date(filters.to + 'T23:59:59.999');
      if (!isNaN(to.getTime())) query.to = to.getTime();
    }
    return query;
  }

  function usageBadge(usage) {
    var key = usage || 'unused';
    return el('span', {
      className: 'badge ' + (USAGE_BADGE[key] || 'badge--muted'),
      text: USAGE_LABEL[key] || usage || '—'
    });
  }

  function emptyBox(message, hint) {
    return el('div', { className: 'empty' }, [
      el('p', { text: message || '暂无数据' }),
      hint ? el('p', { className: 'muted small', text: hint }) : null
    ]);
  }

  /* ======================================================================
   * 网络请求
   * ====================================================================== */

  function ApiError(message, status, code) {
    this.name = 'ApiError';
    this.message = message || '请求失败';
    this.status = status || 0;
    this.code = code === undefined ? null : code;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function buildUrl(path, query) {
    var url = path;
    if (!query) return url;
    var params = new URLSearchParams();
    Object.keys(query).forEach(function (key) {
      var value = query[key];
      if (value === null || value === undefined || value === '') return;
      params.set(key, String(value));
    });
    var queryString = params.toString();
    if (!queryString) return url;
    return url + (url.indexOf('?') === -1 ? '?' : '&') + queryString;
  }

  async function api(path, options) {
    var opts = options || {};
    var init = {
      method: opts.method || 'GET',
      credentials: 'same-origin'
    };
    if (opts.body !== undefined) {
      init.headers = { 'Content-Type': 'application/json' };
      init.body = JSON.stringify(opts.body);
    }

    var response;
    try {
      response = await fetch(buildUrl(path, opts.query), init);
    } catch (error) {
      throw new ApiError('网络请求失败，请检查网络后重试', 0, -1);
    }

    if (response.status === 401) {
      if (!opts.silentAuth) onUnauthorized();
      throw new ApiError('会话已过期，请重新登录', 401, 401);
    }

    var raw = '';
    try {
      raw = await response.text();
    } catch (error) {
      raw = '';
    }

    var payload = null;
    if (raw) {
      try {
        payload = JSON.parse(raw);
      } catch (error) {
        payload = null;
      }
    }

    var message = (payload && payload.message) || ('请求失败（HTTP ' + response.status + '）');

    if (!response.ok) {
      throw new ApiError(message, response.status, payload ? payload.code : null);
    }
    if (!payload || typeof payload.code !== 'number') {
      throw new ApiError('响应格式异常，请联系管理员', response.status, -1);
    }
    if (payload.code !== 0) {
      throw new ApiError(payload.message || '操作失败', response.status, payload.code);
    }
    return payload.data === undefined ? null : payload.data;
  }

  /* ======================================================================
   * 全局反馈
   * ====================================================================== */

  function toast(message, type, duration) {
    var box = $('#toast-container');
    if (!box) return;
    var node = el('div', {
      className: 'toast toast--' + (type || 'info'),
      role: 'status'
    }, message === null || message === undefined ? '' : String(message));
    box.appendChild(node);
    while (box.children.length > 4) {
      box.removeChild(box.firstChild);
    }
    window.setTimeout(function () {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, duration || 3800);
  }

  function openModal(options) {
    var root = $('#modal-root');
    if (!root) return Promise.resolve(null);

    return new Promise(function (resolve) {
      var settled = false;

      function finish(value) {
        if (settled) return;
        settled = true;
        document.removeEventListener('keydown', onKeyDown);
        root.classList.add('hidden');
        root.replaceChildren();
        resolve(value);
      }

      function onKeyDown(event) {
        if (event.key === 'Escape') finish(null);
      }

      var footer = el('div', { className: 'modal-foot' });
      (options.actions || []).forEach(function (action) {
        var button = el('button', {
          className: 'btn ' + (action.className || 'btn-ghost'),
          type: 'button',
          text: action.label || '确定'
        });
        button.addEventListener('click', function () {
          if (action.onClick) {
            var result = action.onClick();
            if (result === false) return;
          }
          finish(action.value === undefined ? true : action.value);
        });
        footer.appendChild(button);
      });

      var closeButton = el('button', {
        className: 'modal-close',
        type: 'button',
        'aria-label': '关闭'
      }, '×');

      var overlay = el('div', { className: 'modal-overlay' }, [
        el('div', { className: 'modal', role: 'dialog', 'aria-modal': 'true' }, [
          el('div', { className: 'modal-head' }, [
            el('h2', { className: 'modal-title', text: options.title || '' }),
            closeButton
          ]),
          el('div', { className: 'modal-body' }, options.body || ''),
          footer
        ])
      ]);

      overlay.addEventListener('click', function (event) {
        if (event.target === overlay) finish(null);
      });
      closeButton.addEventListener('click', function () {
        finish(null);
      });

      root.classList.remove('hidden');
      root.replaceChildren(overlay);
      document.addEventListener('keydown', onKeyDown);

      var focusTarget = overlay.querySelector('input, select, textarea, button:not(.modal-close)');
      if (focusTarget) {
        window.setTimeout(function () {
          focusTarget.focus();
        }, 30);
      }
    });
  }

  function confirmModal(options) {
    return openModal({
      title: options.title,
      body: el('p', { className: 'modal-message', text: options.message || '' }),
      actions: [
        { label: '取消', className: 'btn-ghost', value: false },
        {
          label: options.okText || '确定',
          className: options.danger ? 'btn-danger' : 'btn-primary',
          value: true
        }
      ]
    }).then(function (result) {
      return result === true;
    });
  }

  function promptModal(options) {
    var input = el('input', {
      type: 'text',
      className: 'input',
      maxlength: String(options.maxlength || 100),
      value: options.value || ''
    });

    return openModal({
      title: options.title,
      body: el('div', { className: 'modal-form' }, [
        el('p', { className: 'muted', text: options.message || '' }),
        el('label', { className: 'field' }, [
          el('span', { text: options.label || '请输入' }),
          input
        ])
      ]),
      actions: [
        { label: '取消', className: 'btn-ghost', value: null },
        {
          label: options.okText || '确定',
          className: 'btn-primary',
          onClick: function () {
            if (!input.value.trim()) {
              toast('请输入' + (options.label || '内容'), 'error');
              input.focus();
              return false;
            }
            return true;
          },
          value: 'ok'
        }
      ]
    }).then(function (result) {
      return result === 'ok' ? input.value.trim() : null;
    });
  }

  function copyText(text) {
    var value = String(text === null || text === undefined ? '' : text);
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(value).then(function () {
        return true;
      }).catch(function () {
        return legacyCopy(value);
      });
    }
    return Promise.resolve(legacyCopy(value));
  }

  function legacyCopy(value) {
    var area = el('textarea', { 'aria-hidden': 'true' });
    area.value = value;
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (error) {
      ok = false;
    }
    document.body.removeChild(area);
    return ok;
  }

  /* ======================================================================
   * CSV / 下载
   * ====================================================================== */

  function csvCell(value) {
    var text = value === null || value === undefined ? '' : String(value);
    if (/[",\r\n]/.test(text)) {
      return '"' + text.replace(/"/g, '""') + '"';
    }
    return text;
  }

  function triggerDownload(blob, filename) {
    var url = URL.createObjectURL(blob);
    var link = el('a', { href: url, download: filename });
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.setTimeout(function () {
      URL.revokeObjectURL(url);
    }, 2000);
  }

  function downloadText(content, filename) {
    triggerDownload(new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8' }), filename);
  }

  async function exportServerCsv(path, query, filename) {
    try {
      var response = await fetch(buildUrl(path, query), { credentials: 'same-origin' });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      var contentType = (response.headers.get('content-type') || '').toLowerCase();
      if (!response.ok || contentType.indexOf('text/csv') === -1) {
        var message = '导出失败（HTTP ' + response.status + '）';
        try {
          var payload = await response.json();
          if (payload && payload.message) message = payload.message;
        } catch (error) {
          /* 非 JSON 响应，保留默认提示 */
        }
        throw new ApiError(message, response.status);
      }
      var blob = await response.blob();
      triggerDownload(blob, filename);
      toast('导出已开始', 'success');
    } catch (error) {
      toast(error && error.message ? error.message : '导出失败', 'error');
    }
  }

  /* ======================================================================
   * 会话与路由
   * ====================================================================== */

  async function init() {
    try {
      await api('/admin/api/me', { silentAuth: true });
      state.authenticated = true;
      setText($('#actor-label'), '管理员');
      showApp();
    } catch (error) {
      showLogin();
    }
  }

  function showApp() {
    $('#boot').classList.add('hidden');
    $('#login-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
    if (!location.hash || location.hash === '#' || location.hash === '#/') {
      location.hash = '#/overview';
    } else {
      renderRoute();
    }
  }

  function showLogin(message) {
    state.authenticated = false;
    state.navSeq += 1;
    $('#boot').classList.add('hidden');
    $('#app-view').classList.add('hidden');
    $('#login-view').classList.remove('hidden');
    var errorBox = $('#login-error');
    if (message) {
      errorBox.textContent = message;
      errorBox.classList.remove('hidden');
    } else {
      errorBox.textContent = '';
      errorBox.classList.add('hidden');
    }
    var passwordInput = $('#login-password');
    passwordInput.value = '';
    window.setTimeout(function () {
      passwordInput.focus();
    }, 30);
  }

  function onUnauthorized() {
    if (!state.authenticated) return;
    showLogin('会话已过期，请重新登录');
    toast('会话已过期，请重新登录', 'error');
  }

  async function logout() {
    try {
      await api('/admin/api/logout', { method: 'POST', silentAuth: true });
    } catch (error) {
      /* 退出失败也回到登录页 */
    }
    state.authenticated = false;
    state.navSeq += 1;
    if (location.hash) {
      history.replaceState(null, '', location.pathname + location.search);
    }
    showLogin();
    toast('已退出登录', 'info');
  }

  function parseRoute() {
    var raw = (location.hash || '').replace(/^#\/?/, '');
    var parts = raw.split('/').filter(Boolean);
    var section = parts[0] || 'overview';
    if (section === 'codes' && parts[1]) {
      var id = parts[1];
      try {
        id = decodeURIComponent(id);
      } catch (error) {
        id = parts[1];
      }
      return { view: 'detail', id: id };
    }
    if (['overview', 'codes', 'matrix', 'features', 'audit'].indexOf(section) !== -1) {
      return { view: section, id: null };
    }
    return { view: 'overview', id: null };
  }

  function updateNav(route) {
    var active = route.view === 'detail' ? 'codes' : route.view;
    var links = document.querySelectorAll('#main-nav a');
    Array.prototype.forEach.call(links, function (link) {
      if (link.dataset.route === active) {
        link.classList.add('active');
        link.setAttribute('aria-current', 'page');
      } else {
        link.classList.remove('active');
        link.removeAttribute('aria-current');
      }
    });
  }

  function renderError(container, message) {
    container.replaceChildren(el('section', { className: 'card' }, [
      el('div', { className: 'card-body' }, [
        el('div', { className: 'empty' }, [
          el('p', { text: '加载失败' }),
          el('p', { className: 'muted small', text: message || '未知错误' }),
          el('button', {
            className: 'btn btn-primary btn-sm',
            type: 'button',
            text: '重试',
            onClick: function () {
              renderRoute();
            }
          })
        ])
      ])
    ]));
  }

  async function renderRoute() {
    if (!state.authenticated) return;
    var route = parseRoute();
    updateNav(route);

    var main = $('#main');
    var seq = ++state.navSeq;

    try {
      if (route.view === 'overview') {
        await renderOverview(main, seq);
      } else if (route.view === 'codes') {
        await renderCodes(main, seq);
      } else if (route.view === 'detail') {
        state.detail.id = route.id;
        await renderDetail(main, seq);
      } else if (route.view === 'matrix') {
        await renderMatrix(main, seq);
      } else if (route.view === 'features') {
        await renderFeatures(main, seq);
      } else if (route.view === 'audit') {
        await renderAudit(main, seq);
      }
    } catch (error) {
      if (seq !== state.navSeq) return;
      if (error && error.status === 401) return;
      renderError(main, error && error.message ? error.message : '加载失败');
    }
  }

  function handleLoadError(error) {
    if (error && error.status === 401) return;
    toast(error && error.message ? error.message : '加载失败', 'error');
  }

  /* ======================================================================
   * 登录
   * ====================================================================== */

  function bindLogin() {
    $('#login-form').addEventListener('submit', async function (event) {
      event.preventDefault();
      var passwordInput = $('#login-password');
      var submitButton = $('#login-submit');
      var errorBox = $('#login-error');
      var password = passwordInput.value;

      if (!password) {
        errorBox.textContent = '请输入管理员口令';
        errorBox.classList.remove('hidden');
        passwordInput.focus();
        return;
      }

      errorBox.classList.add('hidden');
      errorBox.textContent = '';
      submitButton.disabled = true;
      submitButton.textContent = '登录中…';

      try {
        await api('/admin/api/login', {
          method: 'POST',
          body: { password: password },
          silentAuth: true
        });
        passwordInput.value = '';
        state.authenticated = true;
        setText($('#actor-label'), '管理员');
        showApp();
        toast('登录成功', 'success');
      } catch (error) {
        errorBox.textContent = error && error.message ? error.message : '登录失败';
        errorBox.classList.remove('hidden');
      } finally {
        submitButton.disabled = false;
        submitButton.textContent = '登录';
      }
    });
  }

  /* ======================================================================
   * 概览
   * ====================================================================== */

  var OVERVIEW_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">概览</h1>
        <p class="page-sub">兑换码库存、激活情况与最近管理操作</p>
      </div>
      <div class="page-head-actions">
        <button class="btn btn-ghost" type="button" id="ov-refresh">刷新</button>
      </div>
    </div>
    <div class="stat-grid" id="ov-stats"></div>
    <div class="grid-2">
      <section class="card">
        <div class="card-head"><h2 class="card-title">按角色分布</h2></div>
        <div class="card-body" id="ov-tier"></div>
      </section>
      <section class="card">
        <div class="card-head"><h2 class="card-title">按类型分布</h2></div>
        <div class="card-body" id="ov-type"></div>
      </section>
    </div>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">最近 20 条审计</h2>
        <a class="link" href="#/audit">查看全部 →</a>
      </div>
      <div class="card-body card-body--flush" id="ov-audit"><div class="loading">加载中…</div></div>
    </section>
  `;

  async function renderOverview(main, seq) {
    main.innerHTML = OVERVIEW_SKELETON;
    $('#ov-refresh').addEventListener('click', function () {
      renderRoute();
    });

    var data = await api('/admin/api/overview');
    if (seq !== state.navSeq) return;

    renderStatCards($('#ov-stats'), [
      { label: '码总数', value: data.total },
      { label: '未使用', value: data.unused },
      { label: '已激活', value: data.active },
      { label: '已过期', value: data.expired },
      { label: '已停用', value: data.revoked },
      { label: '7 日新增', value: data.new7d, accent: true },
      { label: '7 日激活', value: data.activated7d, accent: true }
    ]);

    var byTier = data.byTier || {};
    renderBars($('#ov-tier'), [
      { label: 'T1', value: byTier['1'], cls: 't1' },
      { label: 'T2', value: byTier['2'], cls: 't2' },
      { label: 'T3', value: byTier['3'], cls: 't3' },
      { label: 'GM', value: byTier['gm'] !== undefined ? byTier['gm'] : byTier['4'], cls: 'gm' }
    ]);

    var byType = data.byType || {};
    renderBars($('#ov-type'), [
      { label: '月卡', value: byType.month, cls: 'month' },
      { label: '永久', value: byType.permanent, cls: 'permanent' }
    ]);

    var auditBox = $('#ov-audit');
    var recent = data.recentAudit || [];
    if (!recent.length) {
      auditBox.replaceChildren(emptyBox('暂无审计记录', '登录、生成、停用/启用与矩阵变更都会记录在这里'));
    } else {
      auditBox.replaceChildren(buildAuditTable(recent));
    }
  }

  function renderStatCards(container, cards) {
    container.replaceChildren();
    cards.forEach(function (card) {
      container.appendChild(el('div', {
        className: 'stat-card' + (card.accent ? ' stat-card--accent' : '')
      }, [
        el('div', { className: 'stat-value', text: fmtNumber(card.value) }),
        el('div', { className: 'stat-label', text: card.label })
      ]));
    });
  }

  function renderBars(container, rows) {
    container.replaceChildren();
    var max = 1;
    rows.forEach(function (row) {
      var value = Number(row.value || 0);
      if (value > max) max = value;
    });

    var wrap = el('div', { className: 'dist' });
    var hasValue = false;

    rows.forEach(function (row) {
      var value = Number(row.value || 0);
      if (value > 0) hasValue = true;
      var percent = value > 0 ? Math.max(2, Math.round((value / max) * 100)) : 0;
      var fill = el('div', { className: 'dist-fill dist-fill--' + (row.cls || '') });
      fill.style.width = percent + '%';
      wrap.appendChild(el('div', { className: 'dist-row' }, [
        el('span', { className: 'dist-label', text: row.label }),
        el('div', { className: 'dist-track' }, fill),
        el('span', { className: 'dist-value', text: fmtNumber(value) })
      ]));
    });

    if (!hasValue) {
      wrap.appendChild(el('div', { className: 'dist-empty', text: '暂无数据' }));
    }
    container.appendChild(wrap);
  }

  /* ======================================================================
   * 审计表格（概览与审计页共用）
   * ====================================================================== */

  function auditDetailText(detail) {
    if (!detail) return '—';
    var text = detail;
    if (typeof detail !== 'string') {
      try {
        text = JSON.stringify(detail);
      } catch (error) {
        text = String(detail);
      }
    }
    var parsed = null;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      parsed = null;
    }
    if (!parsed || typeof parsed !== 'object') {
      return truncate(text, 80);
    }
    var parts = [];
    Object.keys(parsed).slice(0, 5).forEach(function (key) {
      var value = parsed[key];
      if (value && typeof value === 'object') {
        try {
          value = JSON.stringify(value);
        } catch (error) {
          value = String(value);
        }
      }
      parts.push(key + '=' + String(value));
    });
    return truncate(parts.join('；') || '—', 100);
  }

  function buildAuditTable(items) {
    if (!items || !items.length) {
      return emptyBox('暂无审计记录');
    }
    var table = el('table', { className: 'table' });
    var thead = el('thead');
    var headRow = el('tr');
    ['时间', '操作', '对象', '详情', '操作人', 'IP'].forEach(function (title) {
      headRow.appendChild(el('th', { text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    items.forEach(function (item) {
      var target = '';
      if (item.targetType) target += item.targetType;
      if (item.targetId) target += '#' + item.targetId;
      var detailText = auditDetailText(item.detail);

      tbody.appendChild(el('tr', null, [
        el('td', { className: 'nowrap', text: fmtTime(item.createdAt) }),
        el('td', null, el('span', { className: 'badge badge--neutral', text: actionText(item.action) })),
        el('td', { className: 'mono small', text: target || '—' }),
        el('td', { className: 'cell-detail', text: detailText, title: detailText === '—' ? '' : detailText }),
        el('td', { text: item.actor || '—' }),
        el('td', { className: 'mono small', text: item.ip || '—' })
      ]));
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    return el('div', { className: 'table-wrap table-wrap--flush' }, table);
  }

  /* ======================================================================
   * 分页
   * ====================================================================== */

  function pageNumbers(page, pages) {
    if (pages <= 7) {
      var all = [];
      for (var i = 1; i <= pages; i += 1) all.push(i);
      return all;
    }
    var wanted = [1, pages, page - 1, page, page + 1];
    var unique = [];
    wanted.forEach(function (value) {
      if (value >= 1 && value <= pages && unique.indexOf(value) === -1) unique.push(value);
    });
    unique.sort(function (a, b) {
      return a - b;
    });
    var result = [];
    unique.forEach(function (value, index) {
      if (index > 0 && value - unique[index - 1] > 1) result.push('…');
      result.push(value);
    });
    return result;
  }

  function renderPager(container, options) {
    if (!container) return;
    container.replaceChildren();

    var total = Math.max(0, Number(options.total) || 0);
    var size = Number(options.size) || 20;
    var pages = Math.max(1, Math.ceil(total / size));
    var page = Math.min(Math.max(1, Number(options.page) || 1), pages);

    container.appendChild(el('span', {
      className: 'page-total',
      text: '共 ' + total + ' 条 · 第 ' + page + ' / ' + pages + ' 页'
    }));

    var nav = el('div', { className: 'page-nav' });

    function navButton(label, target, config) {
      var cfg = config || {};
      var button = el('button', {
        className: 'page-btn' + (cfg.active ? ' is-active' : ''),
        type: 'button',
        text: label,
        disabled: !!cfg.disabled
      });
      if (cfg.active) button.setAttribute('aria-current', 'page');
      if (!cfg.disabled) {
        button.addEventListener('click', function () {
          options.onPage(target);
        });
      }
      return button;
    }

    nav.appendChild(navButton('上一页', page - 1, { disabled: page <= 1 }));
    pageNumbers(page, pages).forEach(function (value) {
      if (value === '…') {
        nav.appendChild(el('span', { className: 'page-ellipsis', text: '…' }));
        return;
      }
      nav.appendChild(navButton(String(value), value, { active: value === page }));
    });
    nav.appendChild(navButton('下一页', page + 1, { disabled: page >= pages }));
    container.appendChild(nav);

    if (options.sizes && options.sizes.length) {
      var select = el('select', { className: 'select select--sm', 'aria-label': '每页条数' });
      options.sizes.forEach(function (optionSize) {
        var option = el('option', { value: String(optionSize), text: optionSize + ' 条/页' });
        if (Number(optionSize) === size) option.selected = true;
        select.appendChild(option);
      });
      select.addEventListener('change', function () {
        options.onSize(Number(select.value));
      });
      container.appendChild(select);
    }
  }

  /* ======================================================================
   * 兑换码列表
   * ====================================================================== */

  var CODES_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">兑换码</h1>
        <p class="page-sub">批量生成、筛选查询与停用/启用</p>
      </div>
    </div>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">批量生成</h2>
        <span class="muted small">单次 1–100 条；月卡激活后 30 天，永久不过期</span>
      </div>
      <div class="card-body">
        <form id="gen-form" class="form-grid form-grid--gen" autocomplete="off" novalidate>
          <label class="field">
            <span>数量</span>
            <input id="gen-count" class="input" type="number" min="1" max="100" step="1" value="10" required>
          </label>
          <label class="field">
            <span>类型</span>
            <select id="gen-type" class="select">
              <option value="month">月卡（30 天）</option>
              <option value="permanent">永久</option>
            </select>
          </label>
          <label class="field">
            <span>等级</span>
            <select id="gen-tier" class="select">
              <option value="1">T1</option>
              <option value="2" selected>T2</option>
              <option value="3">T3</option>
              <option value="gm">GM</option>
            </select>
          </label>
          <label class="field">
            <span>备注（≤100 字）</span>
            <input id="gen-remark" class="input" type="text" maxlength="100" placeholder="可选，例如：双十一活动">
          </label>
          <div class="field">
            <span>&nbsp;</span>
            <button id="gen-submit" class="btn btn-primary" type="submit">生成</button>
          </div>
        </form>
      </div>
    </section>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">兑换码列表</h2>
        <button class="btn btn-ghost btn-sm" type="button" id="codes-export">导出 CSV</button>
      </div>
      <div class="card-body">
        <form id="filter-form" class="filters" autocomplete="off" novalidate>
          <label class="field field--inline">
            <span>状态</span>
            <select id="f-status" class="select">
              <option value="">全部状态</option>
              <option value="active">正常</option>
              <option value="revoked">已停用</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>使用状态</span>
            <select id="f-usage" class="select">
              <option value="">全部</option>
              <option value="unused">未使用</option>
              <option value="active">已激活</option>
              <option value="expired">已过期</option>
              <option value="revoked">已停用</option>
              <option value="exhausted">已用尽（历史）</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>等级</span>
            <select id="f-tier" class="select">
              <option value="">全部等级</option>
              <option value="1">T1</option>
              <option value="2">T2</option>
              <option value="3">T3</option>
              <option value="gm">GM</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>类型</span>
            <select id="f-type" class="select">
              <option value="">全部类型</option>
              <option value="month">月卡</option>
              <option value="permanent">永久</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>关键词</span>
            <input id="f-q" class="input" type="search" maxlength="64" placeholder="码 / 备注 / 设备">
          </label>
          <label class="field field--inline">
            <span>创建起</span>
            <input id="f-from" class="input" type="date">
          </label>
          <label class="field field--inline">
            <span>创建止</span>
            <input id="f-to" class="input" type="date">
          </label>
          <label class="field field--inline">
            <span>排序</span>
            <select id="f-sort" class="select">
              <option value="created_desc">创建时间倒序</option>
              <option value="expire_asc">到期时间升序</option>
            </select>
          </label>
          <button class="btn btn-primary" type="submit">查询</button>
          <button class="btn btn-ghost" type="button" id="filter-reset">重置</button>
        </form>
        <div id="batch-bar" class="batch-bar hidden">
          <span id="batch-count" class="batch-count">已选 0 项</span>
          <button class="btn btn-danger btn-sm" type="button" id="batch-revoke" disabled>批量停用</button>
          <button class="btn btn-success btn-sm" type="button" id="batch-restore" disabled>批量启用</button>
          <button class="btn btn-ghost btn-sm" type="button" id="batch-clear">取消选择</button>
        </div>
        <div id="codes-table-wrap" class="table-wrap"><div class="loading">加载中…</div></div>
        <div id="codes-pagination" class="pagination"></div>
      </div>
    </section>
  `;

  async function renderCodes(main, seq) {
    main.innerHTML = CODES_SKELETON;
    fillCodeFilters();
    updateBatchBar();
    bindCodeEvents();
    await loadCodes(seq);
  }

  function bindCodeEvents() {
    $('#gen-form').addEventListener('submit', submitGenerate);
    $('#filter-form').addEventListener('submit', function (event) {
      event.preventDefault();
      state.codes.filters = readCodeFilters();
      state.codes.sort = $('#f-sort').value;
      state.codes.page = 1;
      state.codes.selected.clear();
      updateBatchBar();
      void loadCodes(state.navSeq).catch(handleLoadError);
    });
    $('#filter-reset').addEventListener('click', function () {
      state.codes.filters = { status: '', usage: '', tier: '', type: '', q: '', from: '', to: '' };
      state.codes.sort = 'created_desc';
      state.codes.page = 1;
      state.codes.size = 20;
      state.codes.selected.clear();
      fillCodeFilters();
      updateBatchBar();
      void loadCodes(state.navSeq).catch(handleLoadError);
    });
    $('#codes-export').addEventListener('click', function () {
      var filters = state.codes.filters;
      var query = {
        status: filters.status,
        usage: filters.usage,
        tier: filters.tier,
        type: filters.type,
        q: filters.q,
        sort: state.codes.sort
      };
      var range = dateRangeQuery(filters);
      if (range.from) query.from = range.from;
      if (range.to) query.to = range.to;
      exportServerCsv('/admin/api/codes/export', query, 'codes-' + todayStamp() + '.csv');
    });
    $('#batch-revoke').addEventListener('click', function () {
      runBatch('revoke');
    });
    $('#batch-restore').addEventListener('click', function () {
      runBatch('restore');
    });
    $('#batch-clear').addEventListener('click', function () {
      state.codes.selected.clear();
      renderCodesTable();
      updateBatchBar();
    });
  }

  function fillCodeFilters() {
    var filters = state.codes.filters;
    $('#f-status').value = filters.status || '';
    $('#f-usage').value = filters.usage || '';
    $('#f-tier').value = filters.tier || '';
    $('#f-type').value = filters.type || '';
    $('#f-q').value = filters.q || '';
    $('#f-from').value = filters.from || '';
    $('#f-to').value = filters.to || '';
    $('#f-sort').value = state.codes.sort;
  }

  function readCodeFilters() {
    return {
      status: $('#f-status').value,
      usage: $('#f-usage').value,
      tier: $('#f-tier').value,
      type: $('#f-type').value,
      q: $('#f-q').value.trim(),
      from: $('#f-from').value,
      to: $('#f-to').value
    };
  }

  function updateBatchBar() {
    var bar = $('#batch-bar');
    var count = $('#batch-count');
    var revokeButton = $('#batch-revoke');
    var restoreButton = $('#batch-restore');
    if (!bar || !count) return;
    var selected = state.codes.selected.size;
    count.textContent = '已选 ' + selected + ' 项';
    bar.classList.remove('hidden');
    if (revokeButton) revokeButton.disabled = selected === 0;
    if (restoreButton) restoreButton.disabled = selected === 0;
  }

  async function loadCodes(seq) {
    var wrap = $('#codes-table-wrap');
    if (!wrap) return;
    wrap.replaceChildren(el('div', { className: 'loading' }, '加载中…'));

    var current = state.codes;
    var query = {
      status: current.filters.status,
      usage: current.filters.usage,
      tier: current.filters.tier,
      type: current.filters.type,
      q: current.filters.q,
      sort: current.sort,
      page: current.page,
      size: current.size
    };
    var range = dateRangeQuery(current.filters);
    if (range.from) query.from = range.from;
    if (range.to) query.to = range.to;

    var data = await api('/admin/api/codes', { query: query });
    if (seq !== state.navSeq) return;

    var items = data.items || [];
    var total = Number(data.total) || 0;
    current.items = items;
    current.total = total;
    current.size = Number(data.size) || current.size;
    current.page = Number(data.page) || current.page;

    var pages = Math.max(1, Math.ceil(total / current.size));
    if (current.page > pages) {
      current.page = pages;
      await loadCodes(seq);
      return;
    }

    renderCodesTable();
    renderCodesPagination(seq);
  }

  function renderCodesTable() {
    var wrap = $('#codes-table-wrap');
    if (!wrap) return;
    var items = state.codes.items;

    if (!items.length) {
      wrap.replaceChildren(emptyBox('没有符合条件的兑换码', '可调整筛选条件，或在上方批量生成新码'));
      return;
    }

    var table = el('table', { className: 'table table--wide' });
    var thead = el('thead');
    var headRow = el('tr');

    var selectAll = el('input', {
      type: 'checkbox',
      className: 'checkbox',
      'aria-label': '全选本页'
    });
    selectAll.checked = items.every(function (item) {
      return state.codes.selected.has(String(item.id));
    });
    selectAll.addEventListener('change', function () {
      var checked = selectAll.checked;
      items.forEach(function (item) {
        var id = String(item.id);
        if (checked) state.codes.selected.add(id);
        else state.codes.selected.delete(id);
      });
      var boxes = wrap.querySelectorAll('tbody input[type="checkbox"]');
      Array.prototype.forEach.call(boxes, function (box) {
        box.checked = checked;
      });
      updateBatchBar();
    });
    headRow.appendChild(el('th', { className: 'cell-check' }, selectAll));
    ['兑换码', '类型', '等级', '使用状态', '绑定设备', '激活时间', '到期时间 / 剩余', '备注', '操作'].forEach(function (title) {
      headRow.appendChild(el('th', { text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    items.forEach(function (item) {
      var row = el('tr');
      var id = String(item.id);

      var checkbox = el('input', {
        type: 'checkbox',
        className: 'checkbox',
        'aria-label': '选择兑换码 ' + (item.code || id)
      });
      checkbox.checked = state.codes.selected.has(id);
      checkbox.addEventListener('change', function () {
        if (checkbox.checked) state.codes.selected.add(id);
        else state.codes.selected.delete(id);
        updateBatchBar();
      });
      row.appendChild(el('td', { className: 'cell-check' }, checkbox));

      row.appendChild(el('td', { className: 'cell-code', text: item.code || '—' }));
      row.appendChild(el('td', { text: typeText(item.type) }));
      row.appendChild(el('td', null, el('span', {
        className: 'tag' + (tierText(item) === 'GM' ? ' tag--gm' : ''),
        text: tierText(item)
      })));
      row.appendChild(el('td', null, usageBadge(item.usage)));
      row.appendChild(el('td', {
        className: 'cell-device',
        text: item.boundDevice || '—',
        title: item.boundDevice || ''
      }));
      row.appendChild(el('td', { className: 'nowrap', text: fmtTime(item.activatedAt) }));
      row.appendChild(el('td', { className: 'nowrap' }, buildExpireCell(item)));
      row.appendChild(el('td', {
        className: 'cell-remark',
        text: item.remark || '—',
        title: item.remark || ''
      }));
      row.appendChild(buildCodeActions(item));

      tbody.appendChild(row);
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrap.replaceChildren(table);
  }

  function buildExpireCell(item) {
    var remain = remainText(item.remainDays);
    var sub = '—';
    if (remain === '永久') sub = '永久有效';
    else if (remain !== '—') sub = '剩余 ' + remain;
    return [
      el('div', { text: fmtTime(item.expireAt) }),
      el('div', { className: 'muted small', text: sub })
    ];
  }

  function buildCodeActions(item) {
    var box = el('div', { className: 'row-actions' });
    box.appendChild(el('button', {
      className: 'btn btn-link',
      type: 'button',
      text: '详情',
      onClick: function () {
        location.hash = '#/codes/' + encodeURIComponent(String(item.id));
      }
    }));
    if (item.status === 'revoked') {
      box.appendChild(el('button', {
        className: 'btn btn-link',
        type: 'button',
        text: '启用',
        onClick: function () {
          restoreCode(item);
        }
      }));
    } else {
      box.appendChild(el('button', {
        className: 'btn btn-link btn-link--danger',
        type: 'button',
        text: '停用',
        onClick: function () {
          revokeCode(item);
        }
      }));
    }
    return el('td', { className: 'cell-actions' }, box);
  }

  function renderCodesPagination(seq) {
    var container = $('#codes-pagination');
    if (!container) return;
    var current = state.codes;
    var pages = Math.max(1, Math.ceil(current.total / current.size));
    renderPager(container, {
      total: current.total,
      page: current.page,
      size: current.size,
      sizes: PAGE_SIZES,
      onPage: function (target) {
        if (target < 1 || target > pages || target === current.page) return;
        current.page = target;
        current.selected.clear();
        updateBatchBar();
        void loadCodes(seq).catch(handleLoadError);
      },
      onSize: function (size) {
        if (size === current.size) return;
        current.size = size;
        current.page = 1;
        current.selected.clear();
        updateBatchBar();
        void loadCodes(seq).catch(handleLoadError);
      }
    });
  }

  async function submitGenerate(event) {
    event.preventDefault();
    var countInput = $('#gen-count');
    var rawCount = countInput.value.trim();
    var count = Number(rawCount);

    if (!/^\d+$/.test(rawCount) || count < 1 || count > 100) {
      toast('数量必须是 1–100 的整数', 'error');
      countInput.focus();
      return;
    }

    var remark = $('#gen-remark').value.trim();
    if (remark.length > 100) {
      toast('备注不能超过 100 字', 'error');
      return;
    }

    var button = $('#gen-submit');
    button.disabled = true;
    button.textContent = '生成中…';

    try {
      var data = await api('/admin/api/codes/generate', {
        method: 'POST',
        body: {
          count: count,
          type: $('#gen-type').value,
          tier: $('#gen-tier').value,
          remark: remark
        }
      });
      var codes = (data && data.codes) || [];
      toast('已生成 ' + codes.length + ' 个兑换码', 'success');
      showGeneratedResult(codes);
      state.codes.page = 1;
      state.codes.selected.clear();
      updateBatchBar();
      await loadCodes(state.navSeq);
    } catch (error) {
      toast(error && error.message ? error.message : '生成失败', 'error');
    } finally {
      button.disabled = false;
      button.textContent = '生成';
    }
  }

  function showGeneratedResult(codes) {
    if (!codes || !codes.length) {
      toast('生成成功，但未返回兑换码列表', 'warn');
      return;
    }

    var list = el('div', { className: 'code-list' });
    codes.forEach(function (item) {
      list.appendChild(el('div', { className: 'code-row' }, [
        el('span', { className: 'code-text mono', text: item.code || '—' }),
        el('span', { className: 'muted small', text: typeText(item.type) + ' · ' + tierText(item) })
      ]));
    });

    openModal({
      title: '已生成 ' + codes.length + ' 个兑换码',
      body: el('div', null, [
        el('p', { className: 'muted', text: '请及时复制或导出保存，兑换码仅在此处完整展示。' }),
        list
      ]),
      actions: [
        {
          label: '导出 CSV',
          className: 'btn-ghost',
          onClick: function () {
            exportGeneratedCsv(codes);
            return false;
          }
        },
        {
          label: '复制全部',
          className: 'btn-primary',
          onClick: function () {
            var text = codes.map(function (item) {
              return item.code;
            }).filter(Boolean).join('\n');
            copyText(text).then(function (ok) {
              toast(ok ? '已复制 ' + codes.length + ' 个兑换码' : '复制失败，请手动选择文本', ok ? 'success' : 'error');
            });
            return false;
          }
        },
        { label: '关闭', className: 'btn-ghost', value: 'close' }
      ]
    });
  }

  function exportGeneratedCsv(codes) {
    var lines = [['code', 'type', 'tier', 'remark', 'created_at'].join(',')];
    codes.forEach(function (item) {
      lines.push([
        csvCell(item.code),
        csvCell(item.type),
        csvCell(tierText(item)),
        csvCell(item.remark || ''),
        csvCell(fmtTime(item.createdAt))
      ].join(','));
    });
    downloadText(lines.join('\r\n'), 'codes-' + todayStamp() + '.csv');
    toast('已导出本次生成的兑换码', 'success');
  }

  async function revokeCode(item) {
    var deviceLabel = item.boundDevice || '未绑定';
    var reason = await promptModal({
      title: '停用兑换码',
      message: '停用后，设备「' + deviceLabel + '」下次校验将进入锁定状态；可随时重新启用，到期时间与绑定关系不变。',
      label: '停用原因',
      value: '后台停用',
      maxlength: 100,
      okText: '确认停用'
    });
    if (reason === null) return;

    try {
      await api('/admin/api/codes/' + encodeURIComponent(String(item.id)) + '/revoke', {
        method: 'POST',
        body: { reason: reason }
      });
      toast('已停用', 'success');
      await loadCodes(state.navSeq);
    } catch (error) {
      toast(error && error.message ? error.message : '停用失败', 'error');
    }
  }

  async function restoreCode(item) {
    var confirmed = await confirmModal({
      title: '启用兑换码',
      message: '确认启用兑换码「' + (item.code || '') + '」？启用后保留原绑定设备与到期时间。',
      okText: '确认启用'
    });
    if (!confirmed) return;

    try {
      await api('/admin/api/codes/' + encodeURIComponent(String(item.id)) + '/restore', { method: 'POST' });
      toast('已启用', 'success');
      await loadCodes(state.navSeq);
    } catch (error) {
      toast(error && error.message ? error.message : '启用失败', 'error');
    }
  }

  async function runBatch(action) {
    var ids = Array.from(state.codes.selected);
    if (!ids.length) return;
    if (ids.length > 100) {
      toast('单次最多操作 100 条，请缩小选择范围', 'error');
      return;
    }

    var label = action === 'revoke' ? '停用' : '启用';
    var confirmed = await confirmModal({
      title: '批量' + label,
      message: '确定要' + label + '选中的 ' + ids.length + ' 个兑换码吗？',
      okText: '确认' + label,
      danger: action === 'revoke'
    });
    if (!confirmed) return;

    try {
      var data = await api('/admin/api/codes/batch', {
        method: 'POST',
        body: {
          ids: ids.map(function (id) {
            return /^\d+$/.test(id) ? Number(id) : id;
          }),
          action: action
        }
      });
      var failed = (data && data.failed) || [];
      var successCount = (data && Number(data.ok)) || 0;
      if (failed.length) {
        var reason = failed[0] && failed[0].message ? failed[0].message : '未知原因';
        toast(label + '完成：成功 ' + successCount + ' 条，失败 ' + failed.length + ' 条（' + reason + '）', 'warn', 6000);
      } else {
        toast(label + '成功：' + successCount + ' 条', 'success');
      }
      state.codes.selected.clear();
      updateBatchBar();
      await loadCodes(state.navSeq);
    } catch (error) {
      toast(error && error.message ? error.message : label + '失败', 'error');
    }
  }

  /* ======================================================================
   * 兑换码详情
   * ====================================================================== */

  var DETAIL_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <a class="back-link" href="#/codes">← 返回兑换码列表</a>
        <h1 class="page-title" id="detail-title">兑换码详情</h1>
      </div>
    </div>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">基本信息</h2>
        <span id="detail-verify" class="muted small"></span>
      </div>
      <div class="card-body" id="detail-info"><div class="loading">加载中…</div></div>
    </section>
    <section class="card">
      <div class="card-head"><h2 class="card-title">绑定设备</h2></div>
      <div class="card-body card-body--flush" id="detail-bindings"><div class="loading">加载中…</div></div>
    </section>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">使用记录</h2>
        <button class="btn btn-ghost btn-sm" type="button" id="log-export">导出记录 CSV</button>
      </div>
      <div class="card-body">
        <form id="log-filter" class="filters" autocomplete="off" novalidate>
          <label class="field field--inline">
            <span>动作</span>
            <select id="lf-action" class="select">
              <option value="">全部动作</option>
              <option value="activate">激活</option>
              <option value="verify">校验</option>
              <option value="rebind">换绑</option>
              <option value="revoke">停用</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>结果</span>
            <select id="lf-result" class="select">
              <option value="">全部结果</option>
              <option value="success">成功</option>
              <option value="fail">失败</option>
            </select>
          </label>
          <label class="field field--inline">
            <span>起</span>
            <input id="lf-from" class="input" type="date">
          </label>
          <label class="field field--inline">
            <span>止</span>
            <input id="lf-to" class="input" type="date">
          </label>
          <button class="btn btn-primary btn-sm" type="submit">查询</button>
          <button class="btn btn-ghost btn-sm" type="button" id="lf-reset">重置</button>
        </form>
        <div id="log-table" class="table-wrap"><div class="loading">加载中…</div></div>
        <div id="log-pagination" class="pagination"></div>
      </div>
    </section>
  `;

  async function renderDetail(main, seq) {
    var id = String(state.detail.id || '');
    if (state.detail.loadedId !== id) {
      state.detail.loadedId = id;
      state.detail.logFilters = { action: '', result: '', from: '', to: '' };
      state.detail.logPage = 1;
      state.detail.logSize = 50;
      state.detail.logItems = [];
      state.detail.logTotal = 0;
    }

    main.innerHTML = DETAIL_SKELETON;
    bindDetailEvents();

    var data = await api('/admin/api/codes/' + encodeURIComponent(id));
    if (seq !== state.navSeq) return;

    state.detail.code = data.code || null;
    state.detail.bindings = data.bindings || [];
    state.detail.verifyCount = Number(data.verifyCount) || 0;

    renderDetailInfo();
    await loadLogs(seq);
  }

  function bindDetailEvents() {
    $('#log-filter').addEventListener('submit', function (event) {
      event.preventDefault();
      state.detail.logFilters = readLogFilters();
      state.detail.logPage = 1;
      void loadLogs(state.navSeq).catch(handleLoadError);
    });
    $('#lf-reset').addEventListener('click', function () {
      state.detail.logFilters = { action: '', result: '', from: '', to: '' };
      state.detail.logPage = 1;
      fillLogFilters();
      void loadLogs(state.navSeq).catch(handleLoadError);
    });
    $('#log-export').addEventListener('click', function () {
      var filters = state.detail.logFilters;
      var query = { action: filters.action, result: filters.result };
      var range = dateRangeQuery(filters);
      if (range.from) query.from = range.from;
      if (range.to) query.to = range.to;
      exportServerCsv(
        '/admin/api/codes/' + encodeURIComponent(state.detail.id) + '/logs/export',
        query,
        'code-' + state.detail.id + '-logs-' + todayStamp() + '.csv'
      );
    });
    fillLogFilters();
  }

  function fillLogFilters() {
    var filters = state.detail.logFilters;
    $('#lf-action').value = filters.action || '';
    $('#lf-result').value = filters.result || '';
    $('#lf-from').value = filters.from || '';
    $('#lf-to').value = filters.to || '';
  }

  function readLogFilters() {
    return {
      action: $('#lf-action').value,
      result: $('#lf-result').value,
      from: $('#lf-from').value,
      to: $('#lf-to').value
    };
  }

  function renderDetailInfo() {
    var code = state.detail.code;
    var infoBox = $('#detail-info');
    var title = $('#detail-title');
    var verifyHint = $('#detail-verify');

    if (!code) {
      setText(title, '兑换码详情');
      infoBox.replaceChildren(emptyBox('兑换码不存在或已删除'));
      verifyHint.textContent = '';
      $('#detail-bindings').replaceChildren(emptyBox('暂无绑定设备'));
      return;
    }

    setText(title, '兑换码 ' + (code.code || code.id));
    verifyHint.textContent = '累计校验 ' + fmtNumber(state.detail.verifyCount) + ' 次';

    var items = [
      ['兑换码', code.code || '—', 'mono'],
      ['类型', typeText(code.type), ''],
      ['等级', tierText(code), ''],
      ['使用状态', usageBadge(code.usage), 'badge'],
      ['状态', code.status === 'revoked' ? '已停用' : '正常', ''],
      ['绑定设备', code.boundDevice || '—', ''],
      ['激活时间', fmtTime(code.activatedAt), ''],
      ['到期时间', fmtTime(code.expireAt), ''],
      ['剩余天数', remainText(code.remainDays), ''],
      ['备注', code.remark || '—', ''],
      ['创建时间', fmtTime(code.createdAt), ''],
      ['校验次数', fmtNumber(state.detail.verifyCount), '']
    ];

    var list = el('dl', { className: 'detail-grid' });
    items.forEach(function (pair) {
      var valueNode;
      if (pair[2] === 'badge') {
        valueNode = el('dd', null, pair[1]);
      } else {
        valueNode = el('dd', { className: pair[2], text: pair[1] });
      }
      list.appendChild(el('div', { className: 'detail-item' }, [
        el('dt', { text: pair[0] }),
        valueNode
      ]));
    });
    infoBox.replaceChildren(list);

    renderBindings();
  }

  function renderBindings() {
    var box = $('#detail-bindings');
    if (!box) return;
    var bindings = state.detail.bindings || [];
    if (!bindings.length) {
      box.replaceChildren(emptyBox('暂无绑定设备', '设备首次激活后会出现在这里'));
      return;
    }

    var table = el('table', { className: 'table' });
    var thead = el('thead');
    var headRow = el('tr');
    ['设备标签', '设备标识', '状态', '绑定时间', '最近校验', '校验次数'].forEach(function (title) {
      headRow.appendChild(el('th', { text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    bindings.forEach(function (binding) {
      var statusKey = binding.status || '';
      var statusLabel = BINDING_STATUS_LABEL[statusKey] || statusKey || '—';
      tbody.appendChild(el('tr', null, [
        el('td', { text: binding.deviceLabel || '—' }),
        el('td', { className: 'mono small', text: truncate(binding.deviceHash || '—', 24) }),
        el('td', null, el('span', {
          className: 'badge ' + (statusKey === 'active' ? 'badge--success' : 'badge--muted'),
          text: statusLabel
        })),
        el('td', { className: 'nowrap', text: fmtTime(binding.boundAt) }),
        el('td', { className: 'nowrap', text: fmtTime(binding.lastSeenAt) }),
        el('td', { text: fmtNumber(binding.verifyCount) })
      ]));
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    box.replaceChildren(el('div', { className: 'table-wrap table-wrap--flush' }, table));
  }

  async function loadLogs(seq) {
    var wrap = $('#log-table');
    if (!wrap) return;
    wrap.replaceChildren(el('div', { className: 'loading' }, '加载中…'));

    var detail = state.detail;
    var query = {
      action: detail.logFilters.action,
      result: detail.logFilters.result,
      page: detail.logPage,
      size: detail.logSize
    };
    var range = dateRangeQuery(detail.logFilters);
    if (range.from) query.from = range.from;
    if (range.to) query.to = range.to;

    var data = await api('/admin/api/codes/' + encodeURIComponent(detail.id) + '/logs', { query: query });
    if (seq !== state.navSeq) return;

    var items = data.items || [];
    detail.logItems = items;
    detail.logTotal = Number(data.total) || 0;
    detail.logSize = Number(data.size) || detail.logSize;
    detail.logPage = Number(data.page) || detail.logPage;

    var pages = Math.max(1, Math.ceil(detail.logTotal / detail.logSize));
    if (detail.logPage > pages) {
      detail.logPage = pages;
      await loadLogs(seq);
      return;
    }

    renderLogTable();
    renderLogPagination(seq);
  }

  function renderLogTable() {
    var wrap = $('#log-table');
    if (!wrap) return;
    var items = state.detail.logItems;

    if (!items.length) {
      wrap.replaceChildren(emptyBox('暂无使用记录', '激活、校验、换绑与停用都会记录在这里'));
      return;
    }

    var table = el('table', { className: 'table' });
    var thead = el('thead');
    var headRow = el('tr');
    ['时间', '动作', '结果', '设备', 'IP', 'UA'].forEach(function (title) {
      headRow.appendChild(el('th', { text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    items.forEach(function (item) {
      var ok = String(item.resultCode) === '0';
      var resultText = item.resultText || (ok ? '成功' : '失败');
      var device = item.deviceLabel || item.deviceHash || '—';
      var ua = item.ua || '—';

      tbody.appendChild(el('tr', null, [
        el('td', { className: 'nowrap', text: fmtTime(item.createdAt) }),
        el('td', { text: actionText(item.action) }),
        el('td', null, el('span', {
          className: 'badge ' + (ok ? 'badge--success' : 'badge--danger'),
          text: resultText,
          title: '结果码 ' + (item.resultCode === null || item.resultCode === undefined ? '—' : item.resultCode)
        })),
        el('td', { className: 'cell-device', text: truncate(device, 40), title: device }),
        el('td', { className: 'mono small', text: item.ip || '—' }),
        el('td', { className: 'cell-detail small', text: truncate(ua, 60), title: ua })
      ]));
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrap.replaceChildren(table);
  }

  function renderLogPagination(seq) {
    var container = $('#log-pagination');
    if (!container) return;
    var detail = state.detail;
    var pages = Math.max(1, Math.ceil(detail.logTotal / detail.logSize));
    renderPager(container, {
      total: detail.logTotal,
      page: detail.logPage,
      size: detail.logSize,
      sizes: LOG_PAGE_SIZES,
      onPage: function (target) {
        if (target < 1 || target > pages || target === detail.logPage) return;
        detail.logPage = target;
        void loadLogs(seq).catch(handleLoadError);
      },
      onSize: function (size) {
        if (size === detail.logSize) return;
        detail.logSize = size;
        detail.logPage = 1;
        void loadLogs(seq).catch(handleLoadError);
      }
    });
  }

  /* ======================================================================
   * 功能权限矩阵
   * ====================================================================== */

  var MATRIX_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">功能权限</h1>
        <p class="page-sub">配置 T1 / T2 / T3 可见的功能；GM 恒为全部已实现功能且只读。</p>
      </div>
      <div class="page-head-actions">
        <label class="checkbox-inline">
          <input type="checkbox" id="matrix-show-implemented"> 仅显示已实现
        </label>
        <button class="btn btn-ghost" type="button" id="matrix-reset">恢复默认</button>
        <button class="btn btn-primary" type="button" id="matrix-save">保存</button>
      </div>
    </div>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">权限矩阵</h2>
        <div id="matrix-counts" class="matrix-counts"></div>
      </div>
      <div class="card-body card-body--flush" id="matrix-table-wrap"><div class="loading">加载中…</div></div>
      <div class="card-foot">
        <p class="muted small">未实现 / 仅调试包的功能不可勾选；保存后服务端立即生效，App 下次启动或校验时应用。</p>
        <p class="muted small">勾选数按全部功能统计，不受上方过滤影响。</p>
      </div>
    </section>
  `;

  async function renderMatrix(main, seq) {
    main.innerHTML = MATRIX_SKELETON;

    $('#matrix-show-implemented').addEventListener('change', function (event) {
      state.matrix.showImplementedOnly = event.target.checked;
      renderMatrixRows();
    });
    $('#matrix-save').addEventListener('click', saveMatrix);
    $('#matrix-reset').addEventListener('click', resetMatrix);

    var data = await api('/admin/api/matrix');
    if (seq !== state.navSeq) return;

    state.matrix.rows = (data.rows || []).map(function (row) {
      return {
        key: row.key,
        label: row.label,
        implemented: !!row.implemented,
        debugOnly: !!row.debugOnly,
        t1: !!row.t1,
        t2: !!row.t2,
        t3: !!row.t3,
        gm: row.gm === undefined ? true : !!row.gm
      };
    });
    renderMatrixRows();
  }

  function matrixToggleable(row) {
    return !!row.implemented && !row.debugOnly;
  }

  function renderMatrixRows() {
    var wrap = $('#matrix-table-wrap');
    if (!wrap) return;

    var rows = state.matrix.rows;
    var visible = rows.filter(function (row) {
      return !state.matrix.showImplementedOnly || matrixToggleable(row);
    });

    if (!visible.length) {
      wrap.replaceChildren(emptyBox('没有可显示的功能', '可取消“仅显示已实现”筛选'));
      updateMatrixCounts();
      return;
    }

    var table = el('table', { className: 'table table--matrix' });
    var thead = el('thead');
    var headRow = el('tr');
    headRow.appendChild(el('th', { text: '功能' }));
    headRow.appendChild(el('th', { text: '实现状态' }));
    ['T1', 'T2', 'T3', 'GM'].forEach(function (title) {
      headRow.appendChild(el('th', { className: 'cell-check', text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    visible.forEach(function (row) {
      var toggleable = matrixToggleable(row);
      var tr = el('tr', { className: toggleable ? '' : 'row-disabled' });

      tr.appendChild(el('td', null, [
        el('div', { className: 'mono small', text: row.key }),
        el('div', { className: 'muted small', text: row.label || '—' })
      ]));

      var statusTag;
      if (!row.implemented) {
        statusTag = el('span', { className: 'tag tag--muted', text: '未实现' });
      } else if (row.debugOnly) {
        statusTag = el('span', { className: 'tag tag--warn', text: '仅调试包' });
      } else {
        statusTag = el('span', { className: 'tag tag--ok', text: '已实现' });
      }
      tr.appendChild(el('td', null, statusTag));

      tr.appendChild(buildMatrixCell(row, 't1', 1, toggleable));
      tr.appendChild(buildMatrixCell(row, 't2', 2, toggleable));
      tr.appendChild(buildMatrixCell(row, 't3', 3, toggleable));
      tr.appendChild(buildMatrixCell(row, 'gm', 4, toggleable));

      tbody.appendChild(tr);
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrap.replaceChildren(table);
    updateMatrixCounts();
  }

  function buildMatrixCell(row, key, role, toggleable) {
    var checkbox = el('input', {
      type: 'checkbox',
      className: 'checkbox',
      'aria-label': (row.label || row.key) + ' 对 ' + (role === 4 ? 'GM' : 'T' + role) + ' 可见'
    });

    if (role === 4) {
      checkbox.checked = true;
      checkbox.disabled = true;
      return el('td', { className: 'cell-check' }, checkbox);
    }

    checkbox.checked = !!row[key];
    checkbox.disabled = !toggleable;
    if (toggleable) {
      checkbox.addEventListener('change', function () {
        row[key] = checkbox.checked;
        updateMatrixCounts();
      });
    }
    return el('td', { className: 'cell-check' }, checkbox);
  }

  function updateMatrixCounts() {
    var box = $('#matrix-counts');
    if (!box) return;
    var rows = state.matrix.rows;
    function count(key) {
      return rows.filter(function (row) {
        return matrixToggleable(row) && !!row[key];
      }).length;
    }
    var gmCount = rows.filter(matrixToggleable).length;

    box.replaceChildren(
      el('span', { className: 'matrix-count' }, ['T1 已勾选 ', el('strong', { text: String(count('t1')) })]),
      el('span', { className: 'matrix-count' }, ['T2 已勾选 ', el('strong', { text: String(count('t2')) })]),
      el('span', { className: 'matrix-count' }, ['T3 已勾选 ', el('strong', { text: String(count('t3')) })]),
      el('span', { className: 'matrix-count' }, ['GM 已勾选 ', el('strong', { text: String(gmCount) })])
    );
  }

  async function saveMatrix() {
    var button = $('#matrix-save');
    if (!button) return;

    var rows = [];
    state.matrix.rows.forEach(function (row) {
      var toggleable = matrixToggleable(row);
      [1, 2, 3].forEach(function (role) {
        var key = 't' + role;
        rows.push({
          key: row.key,
          role: role,
          visible: toggleable ? !!row[key] : false
        });
      });
    });

    button.disabled = true;
    button.textContent = '保存中…';

    try {
      await api('/admin/api/matrix', { method: 'PUT', body: { rows: rows } });
      toast('权限矩阵已保存，App 下次校验时生效', 'success');
      await reloadMatrix();
    } catch (error) {
      toast(error && error.message ? error.message : '保存失败', 'error');
    } finally {
      button.disabled = false;
      button.textContent = '保存';
    }
  }

  async function resetMatrix() {
    var confirmed = await confirmModal({
      title: '恢复默认矩阵',
      message: '将按默认规则覆盖 T1 / T2 / T3 的全部配置，操作会记录到审计。确定继续？',
      okText: '恢复默认',
      danger: true
    });
    if (!confirmed) return;

    try {
      await api('/admin/api/matrix/reset', { method: 'POST' });
      toast('已恢复默认矩阵', 'success');
      await reloadMatrix();
    } catch (error) {
      toast(error && error.message ? error.message : '恢复默认失败', 'error');
    }
  }

  async function reloadMatrix() {
    var wrap = $('#matrix-table-wrap');
    if (!wrap) return;
    var data = await api('/admin/api/matrix');
    state.matrix.rows = (data.rows || []).map(function (row) {
      return {
        key: row.key,
        label: row.label,
        implemented: !!row.implemented,
        debugOnly: !!row.debugOnly,
        t1: !!row.t1,
        t2: !!row.t2,
        t3: !!row.t3,
        gm: row.gm === undefined ? true : !!row.gm
      };
    });
    renderMatrixRows();
  }

  /* ======================================================================
   * 功能目录
   * ====================================================================== */

  var FEATURES_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">功能目录</h1>
        <p class="page-sub">服务端内置的功能 key 目录，只读；矩阵以该目录为唯一来源。</p>
      </div>
      <div class="page-head-actions">
        <select id="ft-tier" class="select select--sm" aria-label="按默认等级过滤">
          <option value="">全部默认等级</option>
          <option value="1">T1</option>
          <option value="2">T2</option>
          <option value="3">T3</option>
        </select>
        <select id="ft-impl" class="select select--sm" aria-label="按实现状态过滤">
          <option value="">全部实现状态</option>
          <option value="1">已实现</option>
          <option value="0">未实现</option>
        </select>
      </div>
    </div>
    <section class="card">
      <div class="card-head">
        <h2 class="card-title">功能清单</h2>
        <span id="ft-count" class="muted small"></span>
      </div>
      <div class="card-body card-body--flush" id="features-table"><div class="loading">加载中…</div></div>
    </section>
  `;

  async function renderFeatures(main, seq) {
    main.innerHTML = FEATURES_SKELETON;

    $('#ft-tier').value = state.features.defaultTier;
    $('#ft-impl').value = state.features.implemented;
    $('#ft-tier').addEventListener('change', function (event) {
      state.features.defaultTier = event.target.value;
      renderFeatureRows();
    });
    $('#ft-impl').addEventListener('change', function (event) {
      state.features.implemented = event.target.value;
      renderFeatureRows();
    });

    var data = await api('/admin/api/features');
    if (seq !== state.navSeq) return;

    state.features.items = data.items || [];
    renderFeatureRows();
  }

  function renderFeatureRows() {
    var box = $('#features-table');
    if (!box) return;

    var features = state.features;
    var items = features.items.filter(function (item) {
      if (features.defaultTier && String(item.defaultTier) !== String(features.defaultTier)) return false;
      if (features.implemented === '1' && !item.implemented) return false;
      if (features.implemented === '0' && item.implemented) return false;
      return true;
    });

    var countBox = $('#ft-count');
    if (countBox) countBox.textContent = '显示 ' + items.length + ' / ' + features.items.length + ' 项';

    if (!items.length) {
      box.replaceChildren(emptyBox('没有符合条件的功能'));
      return;
    }

    var table = el('table', { className: 'table' });
    var thead = el('thead');
    var headRow = el('tr');
    ['功能 key', '中文名', '默认等级', '实现状态', '调试项'].forEach(function (title) {
      headRow.appendChild(el('th', { text: title }));
    });
    thead.appendChild(headRow);

    var tbody = el('tbody');
    items.forEach(function (item) {
      var implementedTag = item.implemented
        ? el('span', { className: 'tag tag--ok', text: '已实现' })
        : el('span', { className: 'tag tag--muted', text: '未实现' });
      tbody.appendChild(el('tr', null, [
        el('td', { className: 'mono small', text: item.key }),
        el('td', { text: item.label || '—' }),
        el('td', { text: item.defaultTier === null || item.defaultTier === undefined ? '—' : 'T' + item.defaultTier }),
        el('td', null, implementedTag),
        el('td', null, el('span', {
          className: 'tag ' + (item.debugOnly ? 'tag--warn' : 'tag--muted'),
          text: item.debugOnly ? '仅调试包' : '否'
        }))
      ]));
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    box.replaceChildren(table);
  }

  /* ======================================================================
   * 操作审计
   * ====================================================================== */

  var AUDIT_SKELETON = `
    <div class="page-head">
      <div class="page-head-main">
        <h1 class="page-title">操作审计</h1>
        <p class="page-sub">管理后台的所有写操作记录，不可编辑或删除。</p>
      </div>
      <div class="page-head-actions">
        <button class="btn btn-ghost" type="button" id="audit-refresh">刷新</button>
      </div>
    </div>
    <section class="card">
      <div class="card-head"><h2 class="card-title">审计记录</h2></div>
      <div class="card-body">
        <div id="audit-table" class="table-wrap"><div class="loading">加载中…</div></div>
        <div id="audit-pagination" class="pagination"></div>
      </div>
    </section>
  `;

  async function renderAudit(main, seq) {
    main.innerHTML = AUDIT_SKELETON;
    $('#audit-refresh').addEventListener('click', function () {
      void loadAudit(state.navSeq).catch(handleLoadError);
    });
    await loadAudit(seq);
  }

  async function loadAudit(seq) {
    var box = $('#audit-table');
    if (!box) return;
    box.replaceChildren(el('div', { className: 'loading' }, '加载中…'));

    var audit = state.audit;
    var data = await api('/admin/api/audit', { query: { page: audit.page, size: audit.size } });
    if (seq !== state.navSeq) return;

    audit.items = data.items || [];
    audit.total = Number(data.total) || 0;
    audit.size = Number(data.size) || audit.size;
    audit.page = Number(data.page) || audit.page;

    var pages = Math.max(1, Math.ceil(audit.total / audit.size));
    if (audit.page > pages) {
      audit.page = pages;
      await loadAudit(seq);
      return;
    }

    box.replaceChildren(buildAuditTable(audit.items));
    renderAuditPagination(seq);
  }

  function renderAuditPagination(seq) {
    var container = $('#audit-pagination');
    if (!container) return;
    var audit = state.audit;
    var pages = Math.max(1, Math.ceil(audit.total / audit.size));
    renderPager(container, {
      total: audit.total,
      page: audit.page,
      size: audit.size,
      sizes: AUDIT_PAGE_SIZES,
      onPage: function (target) {
        if (target < 1 || target > pages || target === audit.page) return;
        audit.page = target;
        void loadAudit(seq).catch(handleLoadError);
      },
      onSize: function (size) {
        if (size === audit.size) return;
        audit.size = size;
        audit.page = 1;
        void loadAudit(seq).catch(handleLoadError);
      }
    });
  }

  /* ======================================================================
   * 启动
   * ====================================================================== */

  function boot() {
    bindLogin();
    $('#logout-btn').addEventListener('click', function () {
      logout();
    });
    window.addEventListener('hashchange', function () {
      renderRoute();
    });
    init();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
