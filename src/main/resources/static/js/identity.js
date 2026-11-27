// 管理台：用户与角色 / 我的画像
// 依赖后端 /api/admin/identity/**（管理员或本机+管理令牌）与 /api/profile。
import { get, post, put, patch, del } from './api.js';
import { escapeHtml } from './markdown.js';
import { authState } from './auth.js';

const esc = (value) => escapeHtml(String(value ?? ''));

function notify(message, ok = true) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.className = `toast show ${ok ? 'ok' : 'error'}`;
  clearTimeout(el._timer);
  el._timer = setTimeout(() => {
    el.className = 'toast';
  }, 2800);
}

function fmtTime(value) {
  if (!value) return '—';
  const num = Number(value);
  if (!Number.isFinite(num) || num <= 0) return '—';
  try {
    return new Date(num).toLocaleString('zh-CN', { hour12: false });
  } catch (_) {
    return '—';
  }
}

function badge(kind, label) {
  return `<span class="badge ${kind}">${esc(label)}</span>`;
}

/* ---------------- 弹窗 ---------------- */

function openDialog({ title, bodyHtml, okText = '保存', onOk }) {
  const mask = document.createElement('div');
  mask.className = 'cs-modal-mask';
  mask.innerHTML = `
    <div class="cs-modal" role="dialog" aria-modal="true">
      <div class="cs-modal-head">
        <h3>${esc(title)}</h3>
        <button class="cs-modal-close" type="button" data-close>×</button>
      </div>
      <div class="cs-modal-body">${bodyHtml}</div>
      <div class="cs-modal-error" hidden></div>
      <div class="cs-modal-foot">
        <button class="btn-sm" type="button" data-close>取消</button>
        <button class="btn primary" type="button" data-ok>${esc(okText)}</button>
      </div>
    </div>`;
  document.body.appendChild(mask);
  const close = () => mask.remove();
  mask.querySelectorAll('[data-close]').forEach((btn) => btn.addEventListener('click', close));
  mask.addEventListener('click', (event) => {
    if (event.target === mask) close();
  });
  const errorEl = mask.querySelector('.cs-modal-error');
  const okButton = mask.querySelector('[data-ok]');
  okButton.addEventListener('click', async () => {
    errorEl.hidden = true;
    okButton.disabled = true;
    try {
      await onOk(mask);
      close();
    } catch (error) {
      errorEl.hidden = false;
      errorEl.textContent = error.message || '操作失败';
    } finally {
      okButton.disabled = false;
    }
  });
  setTimeout(() => mask.querySelector('input, select, textarea')?.focus(), 30);
  return mask;
}

function field(name, label, { type = 'text', value = '', placeholder = '', hint = '' } = {}) {
  return `<label class="cs-field">
    <span>${esc(label)}</span>
    <input name="${esc(name)}" type="${esc(type)}" value="${esc(value)}" placeholder="${esc(placeholder)}">
    ${hint ? `<small>${esc(hint)}</small>` : ''}
  </label>`;
}

function domainCheckboxes(domains, selected = []) {
  const set = new Set(selected || []);
  if (!domains.length) return '<div class="cs-empty">暂无知识域，先去知识库上传文档或创建授权</div>';
  return `<div class="cs-check-grid">${domains.map((item) => {
    const domain = item.domain || item;
    const stats = item.files === undefined ? '' : `（${item.files} 文件 / ${item.chunks} 段）`;
    return `<label class="cs-check">
      <input type="checkbox" name="domain" value="${esc(domain)}" ${set.has(domain) ? 'checked' : ''}>
      <span>${esc(domain)} <small>${esc(stats)}</small></span>
    </label>`;
  }).join('')}</div>`;
}

function roleCheckboxes(roles, selected = []) {
  const set = new Set(selected || []);
  return `<div class="cs-check-grid">${roles.map((role) => `
    <label class="cs-check">
      <input type="checkbox" name="role" value="${esc(role.code)}" ${set.has(role.code) ? 'checked' : ''}>
      <span>${esc(role.name)} <small>${esc(role.code)}</small></span>
    </label>`).join('') || '<div class="cs-empty">暂无角色</div>'}</div>`;
}

function checkedValues(scope, name) {
  return [...scope.querySelectorAll(`input[name="${name}"]:checked`)].map((input) => input.value);
}

function valueOf(scope, name) {
  const input = scope.querySelector(`[name="${name}"]`);
  return input ? input.value.trim() : '';
}

/* ---------------- 用户与角色 ---------------- */

export async function renderUsers(body) {
  let usersPage = { total: 0, items: [] };
  let roles = [];
  let domains = [];
  let forbidden = null;
  try {
    const results = await Promise.all([
      get('/api/admin/identity/users?limit=200&offset=0'),
      get('/api/admin/identity/roles'),
      get('/api/admin/identity/domains'),
    ]);
    usersPage = results[0] || usersPage;
    roles = results[1] || [];
    domains = results[2] || [];
  } catch (error) {
    forbidden = error;
  }

  if (forbidden) {
    const state = authState();
    body.innerHTML = `
      <div class="cs-stack">
        <section class="cs-card">
          <div class="cs-card-head"><h2>需要管理员权限</h2>${badge('warn', '403')}</div>
          <p class="cs-hint">${esc(forbidden.message || '无权限访问用户管理接口')}</p>
          <p class="cs-hint">当前身份：${esc(state.authenticated ? (state.displayName || state.loginName) : '匿名')}。
          请使用管理员账号登录，或在本机访问 / 在「设置」里配置管理令牌。</p>
          <div class="cs-inline">
            <a class="btn primary" href="/login?redirect=${encodeURIComponent('/#/admin/users')}">管理员登录</a>
          </div>
        </section>
      </div>`;
    return;
  }

  const roleByCode = new Map(roles.map((role) => [role.code, role]));

  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>用户</h2>
          <div class="cs-inline">
            <input class="cs-input" id="userSearch" placeholder="搜索登录名 / 昵称 / 外部 ID" style="min-width:220px">
            <button class="btn-sm" id="btnUserSearch">搜索</button>
            <button class="btn-sm" id="btnUserAll">全部</button>
            <button class="btn primary" id="btnUserCreate">新建用户</button>
          </div>
        </div>
        <div class="cs-sub" id="userCount">共 ${usersPage.total || 0} 个用户</div>
        <table class="admin-table">
          <thead><tr><th>用户</th><th>角色</th><th>知识域</th><th>来源 / 状态</th><th>最近登录</th><th>操作</th></tr></thead>
          <tbody id="userBody">${userRows(usersPage.items, roleByCode)}</tbody>
        </table>
      </section>

      <section class="cs-card">
        <div class="cs-card-head">
          <h2>角色</h2>
          <div class="cs-inline">
            <span class="cs-sub">角色决定该角色下所有用户可见的知识域</span>
            <button class="btn primary" id="btnRoleCreate">新建角色</button>
          </div>
        </div>
        <table class="admin-table">
          <thead><tr><th>角色</th><th>编码</th><th>成员</th><th>知识域</th><th>操作</th></tr></thead>
          <tbody id="roleBody">${roleRows(roles)}</tbody>
        </table>
      </section>

      <section class="cs-card">
        <div class="cs-card-head"><h2>知识域</h2><span class="cs-sub">来自知识库文档与管理端授权</span></div>
        <div class="cs-domain-list">
          ${domains.map((item) => `<span class="cs-domain">${esc(item.domain)}
            <small>${item.files || 0} 文件 / ${item.chunks || 0} 段</small></span>`).join('')
            || '<div class="cs-empty">暂无知识域</div>'}
        </div>
      </section>

      <section class="cs-card">
        <div class="cs-card-head"><h2>审计日志</h2><button class="btn-sm" id="btnAuditRefresh">刷新</button></div>
        <div id="auditBody"><div class="cs-empty">加载中…</div></div>
      </section>
    </div>`;

  bindUserActions(body, { roles, domains, roleByCode });
  bindRoleActions(body, { roles, domains });
  loadAudit(body);
  body.querySelector('#btnAuditRefresh')?.addEventListener('click', () => loadAudit(body));
}

function userRows(items, roleByCode) {
  if (!items || !items.length) return '<tr><td colspan="6" class="empty">暂无用户</td></tr>';
  return items.map((user) => {
    const status = user.enabled ? badge('ok', '启用') : badge('off', '停用');
    const source = user.source === 'sso' ? 'SSO' : '本地';
    const roleLabels = (user.roles || []).map((code) => roleByCode.get(code)?.name || code);
    return `
      <tr data-user="${esc(user.userId)}">
        <td>
          <div class="cs-user-name">${esc(user.displayName || user.loginName)}</div>
          <div class="cs-user-sub">${esc(user.loginName)} · ${esc(user.userId)}</div>
        </td>
        <td>${roleLabels.length ? roleLabels.map((name) => badge('info', name)).join(' ') : '<span class="cs-hint">无</span>'}</td>
        <td>${(user.domains || []).length ? user.domains.map((domain) => `<span class="cs-tag">${esc(domain)}</span>`).join(' ') : '<span class="cs-hint">未授权</span>'}</td>
        <td>${esc(source)} ${status}${user.hasPassword ? '' : ' <small>无密码</small>'}</td>
        <td>${esc(fmtTime(user.lastLoginAt))}</td>
        <td class="cs-actions">
          <button class="btn-sm" data-act="edit">编辑</button>
          <button class="btn-sm" data-act="roles">角色</button>
          <button class="btn-sm" data-act="domains">知识域</button>
          <button class="btn-sm" data-act="password">重置密码</button>
          <button class="btn-sm" data-act="profile">画像</button>
          <button class="btn-sm danger" data-act="delete">删除</button>
        </td>
      </tr>`;
  }).join('');
}

function roleRows(roles) {
  if (!roles || !roles.length) return '<tr><td colspan="5" class="empty">暂无角色</td></tr>';
  return roles.map((role) => `
    <tr data-role="${esc(role.roleId)}">
      <td>${esc(role.name)} ${role.builtIn ? badge('info', '内置') : ''}<div class="cs-user-sub">${esc(role.description || '')}</div></td>
      <td class="cs-mono">${esc(role.code)}</td>
      <td>${role.userCount || 0}</td>
      <td>${(role.domains || []).length ? role.domains.map((domain) => `<span class="cs-tag">${esc(domain)}</span>`).join(' ') : '<span class="cs-hint">未授权</span>'}</td>
      <td class="cs-actions">
        <button class="btn-sm" data-act="edit">编辑</button>
        <button class="btn-sm" data-act="domains">知识域</button>
        <button class="btn-sm danger" data-act="delete" ${role.builtIn ? 'disabled' : ''}>删除</button>
      </td>
    </tr>`).join('');
}

function bindUserActions(body, { roles, domains, roleByCode }) {
  const reload = () => renderUsers(body);
  const search = body.querySelector('#userSearch');
  body.querySelector('#btnUserSearch')?.addEventListener('click', async () => {
    const query = (search.value || '').trim();
    try {
      const page = await get(`/api/admin/identity/users?limit=200&offset=0&query=${encodeURIComponent(query)}`);
      const tbody = body.querySelector('#userBody');
      tbody.innerHTML = userRows(page.items, roleByCode);
      const count = body.querySelector('#userCount');
      if (count) count.textContent = `共 ${page.total || 0} 个用户`;
      bindUserRow(body, { roles, domains, roleByCode, reload });
    } catch (error) {
      notify(error.message || '搜索失败', false);
    }
  });
  body.querySelector('#btnUserAll')?.addEventListener('click', () => {
    search.value = '';
    reload();
  });
  body.querySelector('#btnUserCreate')?.addEventListener('click', () => {
    openDialog({
      title: '新建用户',
      bodyHtml: `
        <div class="cs-field-grid">
          ${field('loginName', '登录名', { placeholder: '字母/数字/下划线' })}
          ${field('password', '初始密码', { type: 'password', placeholder: '至少 6 位' })}
          ${field('displayName', '显示名', { placeholder: '可选' })}
          ${field('email', '邮箱', { placeholder: '可选' })}
          ${field('phone', '电话', { placeholder: '可选' })}
        </div>
        <div class="cs-modal-section"><span>角色</span>${roleCheckboxes(roles, ['user'])}</div>
        <div class="cs-modal-section"><span>知识域（直接授权）</span>${domainCheckboxes(domains)}</div>`,
      onOk: async (scope) => {
        const payload = {
          loginName: valueOf(scope, 'loginName'),
          password: valueOf(scope, 'password'),
          displayName: valueOf(scope, 'displayName'),
          email: valueOf(scope, 'email'),
          phone: valueOf(scope, 'phone'),
          roles: checkedValues(scope, 'role'),
          domains: checkedValues(scope, 'domain'),
        };
        if (!payload.loginName || !payload.password) throw new Error('登录名与密码不能为空');
        await post('/api/admin/identity/users', payload);
        notify('用户已创建');
        reload();
      },
    });
  });
  bindUserRow(body, { roles, domains, roleByCode, reload });
}

function bindUserRow(body, ctx) {
  const { roles, domains, reload } = ctx;
  body.querySelectorAll('#userBody tr[data-user]').forEach((row) => {
    const userId = row.dataset.user;
    row.querySelectorAll('[data-act]').forEach((button) => {
      button.addEventListener('click', async () => {
        const act = button.dataset.act;
        try {
          if (act === 'edit') {
            const user = await getUser(userId);
            openDialog({
              title: `编辑用户 · ${user.loginName}`,
              bodyHtml: `
                <div class="cs-field-grid">
                  ${field('displayName', '显示名', { value: user.displayName || '' })}
                  ${field('email', '邮箱', { value: user.email || '' })}
                  ${field('phone', '电话', { value: user.phone || '' })}
                  <label class="cs-field"><span>状态</span>
                    <select name="enabled">
                      <option value="true" ${user.enabled ? 'selected' : ''}>启用</option>
                      <option value="false" ${!user.enabled ? 'selected' : ''}>停用</option>
                    </select>
                  </label>
                </div>`,
              onOk: async (scope) => {
                await patch(`/api/admin/identity/users/${encodeURIComponent(userId)}`, {
                  displayName: valueOf(scope, 'displayName'),
                  email: valueOf(scope, 'email'),
                  phone: valueOf(scope, 'phone'),
                  enabled: valueOf(scope, 'enabled') === 'true',
                });
                notify('已保存');
                reload();
              },
            });
          } else if (act === 'roles') {
            const user = await getUser(userId);
            openDialog({
              title: `分配角色 · ${user.loginName}`,
              bodyHtml: roleCheckboxes(roles, user.roles || []),
              onOk: async (scope) => {
                await put(`/api/admin/identity/users/${encodeURIComponent(userId)}/roles`, {
                  roles: checkedValues(scope, 'role'),
                });
                notify('角色已更新');
                reload();
              },
            });
          } else if (act === 'domains') {
            const user = await getUser(userId);
            openDialog({
              title: `知识域授权 · ${user.loginName}`,
              bodyHtml: `<p class="cs-hint">用户最终可见的知识域 = 直接授权 ∪ 所拥有角色的授权。</p>${domainCheckboxes(domains, user.domains || [])}`,
              onOk: async (scope) => {
                await put(`/api/admin/identity/users/${encodeURIComponent(userId)}/domains`, {
                  domains: checkedValues(scope, 'domain'),
                });
                notify('知识域已更新');
                reload();
              },
            });
          } else if (act === 'password') {
            openDialog({
              title: '重置密码',
              bodyHtml: field('password', '新密码', { type: 'password', placeholder: '至少 6 位' }),
              onOk: async (scope) => {
                const password = valueOf(scope, 'password');
                if (password.length < 6) throw new Error('密码至少 6 位');
                await put(`/api/admin/identity/users/${encodeURIComponent(userId)}/password`, { password });
                notify('密码已重置，旧会话已失效');
              },
            });
          } else if (act === 'profile') {
            const profile = await get(`/api/admin/identity/users/${encodeURIComponent(userId)}/profile`);
            openDialog({
              title: `用户画像 · ${profile.displayName || profile.userId}`,
              bodyHtml: profileHtml(profile),
              okText: '关闭',
              onOk: async () => {},
            });
          } else if (act === 'delete') {
            if (!window.confirm('删除该用户？\n会停用账号并清除角色与知识域授权，历史会话仍保留。')) return;
            await del(`/api/admin/identity/users/${encodeURIComponent(userId)}`);
            notify('用户已删除（软删除）');
            reload();
          }
        } catch (error) {
          notify(error.message || '操作失败', false);
        }
      });
    });
  });
}

async function getUser(userId) {
  const page = await get(`/api/admin/identity/users?limit=200&offset=0`);
  const found = (page.items || []).find((item) => item.userId === userId);
  if (!found) throw new Error('用户不存在或列表已刷新');
  return found;
}

function bindRoleActions(body, { domains }) {
  const reload = () => renderUsers(body);
  body.querySelector('#btnRoleCreate')?.addEventListener('click', () => {
    openDialog({
      title: '新建角色',
      bodyHtml: `
        <div class="cs-field-grid">
          ${field('code', '角色编码', { placeholder: '小写字母/数字/-/_' })}
          ${field('name', '角色名称', { placeholder: '例如 装备保障员' })}
        </div>
        ${field('description', '描述', { placeholder: '可选' })}
        <div class="cs-modal-section"><span>可见知识域</span>${domainCheckboxes(domains)}</div>`,
      onOk: async (scope) => {
        const code = valueOf(scope, 'code');
        const name = valueOf(scope, 'name');
        if (!code || !name) throw new Error('角色编码与名称不能为空');
        const role = await post('/api/admin/identity/roles', {
          code,
          name,
          description: valueOf(scope, 'description'),
        });
        const chosen = checkedValues(scope, 'domain');
        if (role && role.roleId && chosen.length) {
          await put(`/api/admin/identity/roles/${encodeURIComponent(role.roleId)}/domains`, { domains: chosen });
        }
        notify('角色已创建');
        reload();
      },
    });
  });

  body.querySelectorAll('#roleBody tr[data-role]').forEach((row) => {
    const roleId = row.dataset.role;
    row.querySelectorAll('[data-act]').forEach((button) => {
      button.addEventListener('click', async () => {
        const act = button.dataset.act;
        try {
          if (act === 'edit') {
            openDialog({
              title: '编辑角色',
              bodyHtml: `
                ${field('name', '角色名称', { value: row.querySelector('td')?.textContent?.trim() || '' })}
                ${field('description', '描述')}`,
              onOk: async (scope) => {
                await patch(`/api/admin/identity/roles/${encodeURIComponent(roleId)}`, {
                  name: valueOf(scope, 'name'),
                  description: valueOf(scope, 'description'),
                });
                notify('角色已更新');
                reload();
              },
            });
          } else if (act === 'domains') {
            const roles = await get('/api/admin/identity/roles');
            const role = (roles || []).find((item) => item.roleId === roleId);
            openDialog({
              title: `角色知识域 · ${role?.name || roleId}`,
              bodyHtml: domainCheckboxes(domains, role?.domains || []),
              onOk: async (scope) => {
                await put(`/api/admin/identity/roles/${encodeURIComponent(roleId)}/domains`, {
                  domains: checkedValues(scope, 'domain'),
                });
                notify('角色知识域已更新');
                reload();
              },
            });
          } else if (act === 'delete') {
            if (!window.confirm('删除该角色？')) return;
            await del(`/api/admin/identity/roles/${encodeURIComponent(roleId)}`);
            notify('角色已删除');
            reload();
          }
        } catch (error) {
          notify(error.message || '操作失败', false);
        }
      });
    });
  });
}

async function loadAudit(body) {
  const holder = body.querySelector('#auditBody');
  if (!holder) return;
  try {
    const entries = await get('/api/admin/identity/audit?limit=100');
    if (!entries || !entries.length) {
      holder.innerHTML = '<div class="cs-empty">暂无审计记录</div>';
      return;
    }
    holder.innerHTML = `
      <table class="admin-table">
        <thead><tr><th>时间</th><th>动作</th><th>操作者</th><th>对象</th><th>详情</th><th>来源 IP</th></tr></thead>
        <tbody>${entries.map((item) => `
          <tr>
            <td>${esc(fmtTime(item.createdAt))}</td>
            <td class="cs-mono">${esc(item.action)}</td>
            <td>${esc(item.userId || 'system')}</td>
            <td class="cs-mono">${esc(item.targetId || '—')}</td>
            <td>${esc(item.detail || '')}</td>
            <td>${esc(item.clientIp || '')}</td>
          </tr>`).join('')}</tbody>
      </table>`;
  } catch (error) {
    holder.innerHTML = `<div class="cs-error">${esc(error.message || '加载失败')}</div>`;
  }
}

/* ---------------- 我的画像 ---------------- */

function prefMap(profile) {
  const map = {};
  (profile.preferences || []).forEach((pref) => {
    if (pref && pref.key) map[pref.key] = pref.value;
  });
  return map;
}

function topicList(profile) {
  return (profile.topics || [])
    .map((topic) => ({ keyword: topic.keyword ?? topic.key ?? String(topic), count: topic.count }))
    .filter((topic) => topic.keyword);
}

function memoryList(profile) {
  const raw = profile.memories;
  if (!raw) return [];
  if (Array.isArray(raw)) {
    return raw.map((memory) => ({ key: memory.key ?? '', value: memory.value ?? '' })).filter((memory) => memory.key);
  }
  // 后端返回的是 Map<String,String>，JSON 化后是对象
  return Object.entries(raw).map(([key, value]) => ({ key, value: String(value ?? '') }));
}

function profileHtml(profile) {
  if (!profile) return '<div class="cs-empty">暂无画像</div>';
  const prefs = prefMap(profile);
  const topics = topicList(profile);
  const memories = memoryList(profile);
  const roles = profile.roles || [];
  const domains = profile.domains || [];
  return `
    <div class="cs-kv">
      <div class="cs-kv-row"><span>用户</span><span>${esc(profile.displayName || profile.userId)}</span></div>
      <div class="cs-kv-row"><span>身份</span><span>${esc(profile.authenticated ? '已登录' : '匿名')}</span></div>
      <div class="cs-kv-row"><span>角色</span><span>${roles.length ? roles.map(esc).join('、') : '—'}</span></div>
      <div class="cs-kv-row"><span>知识域</span><span>${domains.length ? domains.map(esc).join('、') : '—'}</span></div>
      <div class="cs-kv-row"><span>累计轮次</span><span>${esc(profile.turns ?? 0)}</span></div>
      <div class="cs-kv-row"><span>常用模式</span><span>${esc(prefs['last-mode'] || '—')}</span></div>
      <div class="cs-kv-row"><span>常用模型</span><span>${esc(prefs['last-model'] || '—')}</span></div>
    </div>
    <div class="cs-modal-section"><span>关注主题</span>
      ${topics.length ? topics.map((topic) => `<span class="cs-tag">${esc(topic.keyword)} · ${esc(topic.count ?? '')}</span>`).join(' ') : '<span class="cs-hint">暂无</span>'}
    </div>
    <div class="cs-modal-section"><span>长期记忆</span>
      ${memories.length ? memories.map((memory) => `<div class="cs-kv-row"><span class="cs-mono">${esc(memory.key)}</span><span>${esc(memory.value)}</span></div>`).join('') : '<span class="cs-hint">暂无</span>'}
    </div>`;
}

export async function renderProfile(body) {
  let profile = null;
  try {
    profile = await get('/api/profile');
  } catch (error) {
    body.innerHTML = `<div class="cs-card"><h2>加载失败</h2><p class="cs-hint">${esc(error.message || '')}</p></div>`;
    return;
  }
  const state = authState();
  const memories = memoryList(profile);
  const topics = topicList(profile);
  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>我的画像</h2>
          <span class="cs-sub">${esc(state.authenticated ? '画像跨会话保存' : '匿名访问：画像只保存在当前浏览器')}</span>
        </div>
        ${profileHtml(profile)}
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>长期记忆</h2><span class="cs-sub">对话时自动注入，可按条目删除</span></div>
        <table class="admin-table">
          <thead><tr><th>Key</th><th>内容</th><th></th></tr></thead>
          <tbody>${memories.map((memory) => `
            <tr><td class="cs-mono">${esc(memory.key)}</td><td>${esc(memory.value)}</td>
            <td><button class="btn-sm danger" data-memory="${esc(memory.key)}">删除</button></td></tr>`).join('')
            || '<tr><td colspan="3" class="empty">暂无记忆</td></tr>'}</tbody>
        </table>
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>关注主题</h2><span class="cs-sub">来自近期提问，可删除纠正</span></div>
        <div class="cs-domain-list">${topics.map((topic) => `
          <span class="cs-domain">${esc(topic.keyword)} <small>${esc(topic.count ?? '')}</small>
            <button class="btn-sm danger" data-topic="${esc(topic.keyword)}">×</button>
          </span>`).join('') || '<div class="cs-empty">暂无主题</div>'}</div>
      </section>
    </div>`;

  body.querySelectorAll('[data-memory]').forEach((button) => {
    button.addEventListener('click', async () => {
      try {
        await del(`/api/memory/${encodeURIComponent(button.dataset.memory)}`);
        notify('已删除');
        renderProfile(body);
      } catch (error) {
        notify(error.message || '删除失败', false);
      }
    });
  });
  body.querySelectorAll('[data-topic]').forEach((button) => {
    button.addEventListener('click', async () => {
      try {
        await del(`/api/profile/topics/${encodeURIComponent(button.dataset.topic)}`);
        notify('已删除');
        renderProfile(body);
      } catch (error) {
        notify(error.message || '删除失败', false);
      }
    });
  });
}
