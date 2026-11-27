// 全屏管理控制台：概览 / 知识库 / MCP / 技能 / 记忆 / 日志 / 设置
import { get, post, del, upload } from './api.js';
import { escapeHtml, renderMarkdown, enhance } from './markdown.js';

const SECTIONS = {
  overview: { title: '概览', subtitle: '智能体、知识库与运行时状态' },
  profile: { title: '我的画像', subtitle: '长期偏好、关注主题与记忆' },
  users: { title: '用户与角色', subtitle: '账号、角色与知识库权限' },
  knowledge: { title: '知识库', subtitle: '文档管理、分段编辑与检索测试' },
  models: { title: '模型', subtitle: '语言模型、Embedding 配置与流量监控' },
  mcp: { title: 'MCP', subtitle: 'MCP Server 连接与工具状态' },
  skills: { title: '技能', subtitle: '本地与 Nacos 技能清单' },
  memory: { title: '记忆', subtitle: '当前用户的长期记忆条目' },
  logs: { title: '日志', subtitle: '运行时日志文件查看' },
  settings: { title: '设置', subtitle: 'RAG 与检索配置、管理令牌' },
};

const state = { section: 'overview', timer: null, opened: false };

/* ---------------- 对外入口 ---------------- */

export function initConsole() {
  document.getElementById('btnAdmin')?.addEventListener('click', () => openConsole('overview'));
  document.getElementById('btnKnowledge')?.addEventListener('click', () => openConsole('knowledge'));
  document.getElementById('consoleClose')?.addEventListener('click', closeConsole);
  document.getElementById('consoleBack')?.addEventListener('click', closeConsole);
  document.getElementById('consoleRefresh')?.addEventListener('click', () => renderSection(state.section, true));
  document.querySelectorAll('#consoleNav button').forEach((button) => {
    button.addEventListener('click', () => openConsole(button.dataset.section));
  });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (document.querySelector('.kb-drawer.show')) return; // 抽屉自行处理
    if (state.opened) closeConsole();
  });
  window.addEventListener('hashchange', () => {
    const section = sectionFromHash();
    if (section && state.opened && section !== state.section) renderSection(section);
  });
  const initial = sectionFromHash();
  if (initial) openConsole(initial);
}

export function openConsole(section) {
  const target = SECTIONS[section] ? section : 'overview';
  const root = document.getElementById('console');
  if (!root) return;
  if (!state.opened) {
    state.opened = true;
    root.hidden = false;
    document.body.classList.add('console-open');
  }
  if (location.hash !== `#/admin/${target}`) {
    history.replaceState(null, '', `#/admin/${target}`);
  }
  renderSection(target);
}

export function closeConsole() {
  const root = document.getElementById('console');
  if (!root || !state.opened) return;
  state.opened = false;
  root.hidden = true;
  document.body.classList.remove('console-open');
  clearTimer();
  if (location.hash.startsWith('#/admin/')) {
    history.replaceState(null, '', location.pathname + location.search);
  }
}

/* ---------------- 路由与渲染 ---------------- */

function sectionFromHash() {
  const match = /^#\/admin\/([a-z]+)$/.exec(location.hash || '');
  return match && SECTIONS[match[1]] ? match[1] : null;
}

async function renderSection(section, force = false) {
  state.section = section;
  clearTimer();
  document.querySelectorAll('#consoleNav button').forEach((button) => {
    button.classList.toggle('active', button.dataset.section === section);
  });
  const meta = SECTIONS[section];
  setHead(meta.title, meta.subtitle);
  const body = document.getElementById('consoleBody');
  if (!body) return;
  body.innerHTML = '<div class="cs-loading">加载中…</div>';
  try {
    if (section === 'overview') await renderOverview(body);
    if (section === 'profile') {
      const module = await import('./identity.js');
      await module.renderProfile(body);
    }
    if (section === 'users') {
      const module = await import('./identity.js');
      await module.renderUsers(body);
    }
    if (section === 'knowledge') {
      const module = await import('./kb.js');
      await module.renderKnowledgeWorkbench(body);
    }
    if (section === 'models') {
      const module = await import('./models.js');
      await module.renderModels(body);
    }
    if (section === 'mcp') await renderMcp(body);
    if (section === 'skills') await renderSkills(body);
    if (section === 'memory') await renderMemory(body);
    if (section === 'logs') await renderLogs(body);
    if (section === 'settings') await renderSettings(body);
  } catch (error) {
    if (force) {
      body.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载失败')}</div>`;
    } else {
      body.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载失败')}</div>`;
    }
  }
}

function setHead(title, subtitle) {
  const titleEl = document.getElementById('consoleTitle');
  const subtitleEl = document.getElementById('consoleSubtitle');
  if (titleEl) titleEl.textContent = title;
  if (subtitleEl) subtitleEl.textContent = subtitle;
}

function clearTimer() {
  if (state.timer) {
    clearInterval(state.timer);
    state.timer = null;
  }
}

/* ---------------- 通用小工具 ---------------- */

export function toast(message, kind = 'ok') {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.className = `toast show ${kind}`;
  clearTimeout(el._timer);
  el._timer = setTimeout(() => {
    el.className = 'toast';
  }, 2600);
}

function confirmAction(message) {
  return window.confirm(message);
}

function fmtNumber(value) {
  const num = Number(value || 0);
  return num.toLocaleString('zh-CN');
}

function fmtBytes(size) {
  const num = Number(size || 0);
  if (num >= 1024 * 1024) return `${(num / 1024 / 1024).toFixed(1)} MB`;
  if (num >= 1024) return `${(num / 1024).toFixed(1)} KB`;
  return `${num} B`;
}

function badge(kind, label) {
  return `<span class="badge ${kind}">${escapeHtml(label)}</span>`;
}

/* ---------------- 概览 ---------------- */

async function renderOverview(body) {
  const results = await Promise.allSettled([
    get('/api/health'),
    get('/api/knowledge/stats'),
    get('/api/admin/agents'),
    get('/api/mcp/servers'),
    get('/api/skills'),
  ]);
  const pick = (index, fallback) => (results[index].status === 'fulfilled' ? results[index].value : fallback);
  const health = pick(0, null);
  const stats = pick(1, null);
  const agents = pick(2, []);
  const servers = pick(3, []);
  const skills = pick(4, []);

  const online = health && health.status === 'up' && health.redis === 'up';
  const components = health ? [
    ['Redis', health.redis],
    ['DM 数据库', health.dm],
    ['用户体系', health.identity],
    ['知识库检索', health.redisSearch],
    ['向量库', `${health.vectorStore || 'milvus'} · ${health.milvus || 'unknown'}`],
    ['模型', health.model],
    ['Nacos', health.nacos],
    ['MCP Server', health.mcpServers],
    ['智能体', health.agents],
  ] : [];

  const connected = servers.filter((item) => item.status === 'CONNECTED' || item.connected).length;
  const enabledAgents = agents.filter((item) => item.enabled);

  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>运行状态</h2>
          <span class="cs-sub">${online ? badge('ok', '在线') : badge('off', '降级')}</span>
        </div>
        <div class="cs-grid">
          <div class="cs-stat">
            <div class="cs-stat-label">知识库分段</div>
            <div class="cs-stat-value">${stats ? fmtNumber(stats.chunks) : '—'}</div>
            <div class="cs-stat-hint">停用 ${stats ? fmtNumber(stats.disabledChunks) : '—'} 段</div>
          </div>
          <div class="cs-stat">
            <div class="cs-stat-label">文档数</div>
            <div class="cs-stat-value">${stats ? fmtNumber((stats.files || []).length) : '—'}</div>
            <div class="cs-stat-hint">原文留存 ${stats ? fmtNumber(stats.storedOriginals) : '—'} 份</div>
          </div>
          <div class="cs-stat">
            <div class="cs-stat-label">向量维度</div>
            <div class="cs-stat-value">${stats && stats.dimension ? stats.dimension : '—'}</div>
            <div class="cs-stat-hint">${stats ? escapeHtml(stats.embeddingTarget || '') : ''}</div>
          </div>
          <div class="cs-stat">
            <div class="cs-stat-label">智能体</div>
            <div class="cs-stat-value">${enabledAgents.length}</div>
            <div class="cs-stat-hint">共 ${agents.length} 个</div>
          </div>
          <div class="cs-stat">
            <div class="cs-stat-label">MCP Server</div>
            <div class="cs-stat-value">${connected}/${servers.length}</div>
            <div class="cs-stat-hint">已连接 / 总数</div>
          </div>
          <div class="cs-stat">
            <div class="cs-stat-label">技能</div>
            <div class="cs-stat-value">${skills.length}</div>
            <div class="cs-stat-hint">可用 ${skills.filter((item) => item.available).length}</div>
          </div>
        </div>
      </section>

      <section class="cs-card">
        <div class="cs-card-head"><h2>组件明细</h2><button class="btn-sm" id="ovRefresh">刷新</button></div>
        ${components.length ? `<div class="cs-kv">${components.map(([label, value]) => `
          <div class="cs-kv-row"><span>${escapeHtml(label)}</span><span>${escapeHtml(String(value ?? '—'))}</span></div>`).join('')}
        </div>` : '<div class="cs-empty">健康检查不可用</div>'}
      </section>

      <section class="cs-card">
        <div class="cs-card-head">
          <h2>快捷入口</h2>
          <span class="cs-sub">${stats ? `集合 ${escapeHtml(stats.indexFields ? 'Milvus 双集合' : '')}` : ''}</span>
        </div>
        <div class="cs-inline">
          <button class="btn" data-go="users">用户与角色</button>
          <button class="btn" data-go="knowledge">上传与管理文档</button>
          <button class="btn" data-go="mcp">检查 MCP 连接</button>
          <button class="btn" data-go="skills">查看技能</button>
          <button class="btn" data-go="settings">检索配置</button>
        </div>
      </section>
    </div>`;

  body.querySelector('#ovRefresh')?.addEventListener('click', () => renderSection('overview'));
  body.querySelectorAll('[data-go]').forEach((button) => {
    button.addEventListener('click', () => openConsole(button.dataset.go));
  });
}

/* ---------------- MCP ---------------- */

const MCP_STATUS = {
  CONNECTED: { cls: 'ok', label: '已连接' },
  CONNECTING: { cls: 'warn', label: '连接中' },
  RECONNECTING: { cls: 'warn', label: '重连中' },
  FAILED: { cls: 'off', label: '失败' },
  DISABLED: { cls: 'off', label: '未启用' },
};

function mcpStatusBadge(server) {
  const info = MCP_STATUS[server.status] || (server.connected ? MCP_STATUS.CONNECTED : MCP_STATUS.FAILED);
  let extra = '';
  if (info.cls === 'warn' && server.attempts > 0) extra = `（第 ${server.attempts + 1} 次）`;
  else if (server.status === 'FAILED' && server.attempts > 0) extra = `（已重试 ${server.attempts} 次）`;
  const err = server.lastError
    ? `<div class="mcp-err" title="${escapeHtml(server.lastError)}">${escapeHtml(server.lastError)}</div>`
    : '';
  return `<div class="mcp-status"><span class="badge ${info.cls}" title="${escapeHtml(server.lastError || info.label)}">${info.label}${extra}</span>${err}</div>`;
}

function mcpRowHtml(server) {
  const name = escapeHtml(server.name);
  const target = escapeHtml(server.target || '');
  return `
    <tr>
      <td>${name}<div class="mcp-sub">${escapeHtml(server.source || '')}</div></td>
      <td>${mcpStatusBadge(server)}</td>
      <td><div class="mcp-target" title="${target}">${target || '—'}</div></td>
      <td>${fmtNumber(server.toolCount)}</td>
      <td>
        <button class="btn-sm" data-action="reconnect" data-name="${name}">重连</button>
        <button class="btn-sm danger" data-action="disconnect" data-name="${name}">删除</button>
      </td>
    </tr>`;
}

async function renderMcp(body) {
  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>已管理 MCP Server</h2>
          <span class="cs-sub">服务重启或断连后自动健康探测并指数退避重连</span>
        </div>
        <table class="admin-table">
          <thead><tr><th>名称</th><th>状态</th><th>目标</th><th>工具数</th><th>操作</th></tr></thead>
          <tbody id="mcpServersBody"><tr><td colspan="5" class="empty">加载中…</td></tr></tbody>
        </table>
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>连接 Stdio MCP</h2><span class="cs-sub">本地进程方式</span></div>
        <div class="form-grid">
          <label>名称 <input id="mcpName" placeholder="my-server"></label>
          <label>命令 <input id="mcpCommand" placeholder="npx"></label>
          <label>参数 <input id="mcpArgs" placeholder="-y @modelcontextprotocol/server-everything"></label>
        </div>
        <div class="cs-inline" style="margin-top:10px"><button class="btn primary" id="btnConnectStdio">连接 Stdio</button></div>
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>连接 SSE / HTTP MCP</h2><span class="cs-sub">远程服务方式</span></div>
        <div class="form-grid">
          <label>名称 <input id="mcpSseName" placeholder="remote-server"></label>
          <label>URL <input id="mcpSseUrl" placeholder="http://localhost:8081/mcp/sse"></label>
        </div>
        <div class="cs-inline" style="margin-top:10px"><button class="btn primary" id="btnConnectSse">连接 SSE</button></div>
      </section>
    </div>`;

  await refreshMcpServersTable();
  state.timer = setInterval(() => {
    if (!document.getElementById('mcpServersBody')) {
      clearTimer();
      return;
    }
    refreshMcpServersTable();
  }, 10000);

  body.querySelector('#btnConnectStdio')?.addEventListener('click', async () => {
    const name = body.querySelector('#mcpName').value.trim();
    const command = body.querySelector('#mcpCommand').value.trim();
    const args = body.querySelector('#mcpArgs').value.trim();
    if (!name || !command) return toast('名称和命令不能为空', 'error');
    try {
      const query = new URLSearchParams({ name, command, enabled: 'true' });
      if (args) query.set('args', args);
      const resp = await post(`/api/mcp/connect/stdio?${query.toString()}`);
      if (resp && resp.success === false) toast(resp.error || '连接失败（条目已保留，服务启动后会自动重连）', 'error');
      else toast('已发起连接');
    } catch (error) {
      toast(error.message || '连接失败', 'error');
    }
    await refreshMcpServersTable();
  });

  body.querySelector('#btnConnectSse')?.addEventListener('click', async () => {
    const name = body.querySelector('#mcpSseName').value.trim();
    const url = body.querySelector('#mcpSseUrl').value.trim();
    if (!name || !url) return toast('名称和 URL 不能为空', 'error');
    try {
      const query = new URLSearchParams({ name, url, enabled: 'true' });
      const resp = await post(`/api/mcp/connect/sse?${query.toString()}`);
      if (resp && resp.success === false) toast(resp.error || '连接失败（条目已保留，服务启动后会自动重连）', 'error');
      else toast('已发起连接');
    } catch (error) {
      toast(error.message || '连接失败', 'error');
    }
    await refreshMcpServersTable();
  });
}

async function refreshMcpServersTable() {
  const tbody = document.getElementById('mcpServersBody');
  if (!tbody) return;
  let servers;
  try {
    servers = await get('/api/mcp/servers');
  } catch (_) {
    return; // 网络抖动时保留旧行，下个周期再试
  }
  tbody.innerHTML = (servers || []).map(mcpRowHtml).join('')
    || '<tr><td colspan="5" class="empty">暂无 MCP Server</td></tr>';
  tbody.querySelectorAll('[data-action="disconnect"]').forEach((button) => {
    button.addEventListener('click', async () => {
      if (!confirmAction(`删除 MCP Server「${button.dataset.name}」？`)) return;
      try {
        await del(`/api/mcp/disconnect?name=${encodeURIComponent(button.dataset.name)}`);
        toast('已删除');
      } catch (error) {
        toast(error.message || '删除失败', 'error');
      }
      await refreshMcpServersTable();
    });
  });
  tbody.querySelectorAll('[data-action="reconnect"]').forEach((button) => {
    button.addEventListener('click', async () => {
      try {
        const resp = await post(`/api/mcp/reconnect?name=${encodeURIComponent(button.dataset.name)}`);
        if (resp && resp.success === false) toast(resp.error || '重连失败', 'error');
        else toast('正在重连…');
      } catch (error) {
        toast(error.message || '重连失败', 'error');
      }
      setTimeout(refreshMcpServersTable, 1200);
    });
  });
}

/* ---------------- 技能 ---------------- */

async function renderSkills(body) {
  const skills = await get('/api/skills');
  const list = skills || [];
  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>技能（本地 + Nacos）</h2>
          <div class="cs-inline">
            <span class="cs-sub">共 ${list.length} 个</span>
            <button class="btn-sm" id="btnRefreshSkills">刷新技能</button>
          </div>
        </div>
        <div class="cs-split">
          <div class="cs-skin-list">
            ${list.map((skill) => `
              <div class="cs-skill" data-skill="${escapeHtml(skill.name)}">
                <div class="cs-skill-name">${escapeHtml(skill.name)} ${skill.available ? badge('ok', '可用') : badge('off', '缺失')}</div>
                <div class="cs-skill-desc">${escapeHtml(skill.description || '')}</div>
              </div>`).join('') || '<div class="cs-empty">暂无技能</div>'}
          </div>
          <div>
            <div class="cs-inline" style="margin-bottom:8px">
              <span class="cs-sub" id="skillName">选择左侧技能查看内容</span>
            </div>
            <pre class="cs-pre" id="skillContent">—</pre>
          </div>
        </div>
      </section>
    </div>`;

  body.querySelector('#btnRefreshSkills')?.addEventListener('click', async () => {
    try {
      await post('/api/skills/refresh');
      toast('技能已刷新');
      await renderSkills(body);
    } catch (error) {
      toast(error.message || '刷新失败', 'error');
    }
  });

  body.querySelectorAll('[data-skill]').forEach((item) => {
    item.addEventListener('click', async () => {
      body.querySelectorAll('[data-skill]').forEach((node) => node.classList.remove('active'));
      item.classList.add('active');
      const nameEl = body.querySelector('#skillName');
      const pre = body.querySelector('#skillContent');
      if (nameEl) nameEl.textContent = item.dataset.skill;
      pre.textContent = '加载中…';
      try {
        const data = await get(`/api/skills/${encodeURIComponent(item.dataset.skill)}`);
        pre.textContent = data.content || '（空）';
        if (data.resources && data.resources.length) {
          pre.textContent += `\n\n-- 资源 --\n${data.resources.join('\n')}`;
        }
      } catch (error) {
        pre.textContent = error.message || '加载失败';
      }
    });
  });
}

/* ---------------- 记忆 ---------------- */

async function renderMemory(body) {
  const memories = await get('/api/memory');
  const rows = (memories || []).map((memory) => `
    <tr>
      <td class="cs-mono">${escapeHtml(memory.key)}</td>
      <td>${escapeHtml(memory.value)}</td>
      <td><button class="btn-sm danger" data-memory="${escapeHtml(memory.key)}">删除</button></td>
    </tr>`).join('');

  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head">
          <h2>长期记忆</h2>
          <button class="btn-sm danger" id="btnClearMemory">清空全部</button>
        </div>
        <table class="admin-table">
          <thead><tr><th>Key</th><th>内容</th><th></th></tr></thead>
          <tbody>${rows || '<tr><td colspan="3" class="empty">暂无记忆</td></tr>'}</tbody>
        </table>
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>新增记忆</h2><span class="cs-sub">会注入到该用户的对话上下文</span></div>
        <div class="form-grid">
          <label>Key <input id="memKey" placeholder="例如 reply_style"></label>
          <label>内容 <input id="memValue" placeholder="例如 喜欢简洁的回答"></label>
        </div>
        <div class="cs-inline" style="margin-top:10px"><button class="btn primary" id="btnAddMemory">添加记忆</button></div>
      </section>
    </div>`;

  body.querySelector('#btnClearMemory')?.addEventListener('click', async () => {
    if (!confirmAction('清空全部记忆？')) return;
    try {
      await del('/api/memory');
      toast('已清空');
      await renderMemory(body);
    } catch (error) {
      toast(error.message || '清空失败', 'error');
    }
  });

  body.querySelectorAll('[data-memory]').forEach((button) => {
    button.addEventListener('click', async () => {
      try {
        await del(`/api/memory/${encodeURIComponent(button.dataset.memory)}`);
        toast('已删除');
        await renderMemory(body);
      } catch (error) {
        toast(error.message || '删除失败', 'error');
      }
    });
  });

  body.querySelector('#btnAddMemory')?.addEventListener('click', async () => {
    const key = body.querySelector('#memKey').value.trim();
    const value = body.querySelector('#memValue').value.trim();
    if (!key || !value) return toast('Key 与内容不能为空', 'error');
    try {
      await post('/api/memory', { key, value });
      toast('已添加');
      await renderMemory(body);
    } catch (error) {
      toast(error.message || '添加失败', 'error');
    }
  });
}

/* ---------------- 日志 ---------------- */

async function renderLogs(body) {
  const files = await get('/api/logs/files');
  const rows = (files || []).map((file) => `
    <tr>
      <td>${escapeHtml(file.name)}</td>
      <td>${escapeHtml(file.sizeHuman || '')}</td>
      <td><button class="btn-sm" data-log="${escapeHtml(file.name)}">查看</button></td>
    </tr>`).join('');

  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head"><h2>日志文件</h2><span class="cs-sub">仅显示 .log，单次读取 300 行</span></div>
        <table class="admin-table">
          <thead><tr><th>文件</th><th>大小</th><th></th></tr></thead>
          <tbody>${rows || '<tr><td colspan="3" class="empty">暂无日志</td></tr>'}</tbody>
        </table>
      </section>
      <section class="cs-card">
        <div class="cs-card-head">
          <h2 id="logTitle">日志内容</h2>
          <div class="cs-inline">
            <span class="cs-sub" id="logMeta"></span>
            <button class="btn-sm" id="btnLogMore" hidden>加载更多</button>
            <button class="btn-sm" id="btnLogTail" hidden>刷新尾部</button>
          </div>
        </div>
        <pre class="cs-pre" id="logContent">选择上方文件查看内容</pre>
      </section>
    </div>`;

  let currentFile = '';
  let offset = 0;

  const appendLines = (lines, replace) => {
    const pre = body.querySelector('#logContent');
    if (replace) pre.textContent = '';
    const text = pre.textContent && !replace ? `${pre.textContent}\n` : '';
    pre.textContent = text + (lines || []).join('\n');
    pre.scrollTop = pre.scrollHeight;
  };

  const load = async (file, nextOffset, replace) => {
    const data = await get(`/api/logs/view?file=${encodeURIComponent(file)}&offset=${nextOffset}&limit=300`);
    appendLines(data.lines || [], replace);
    offset = nextOffset + (data.lines || []).length;
    body.querySelector('#logMeta').textContent = `${file} · 已加载 ${offset} 行`;
    body.querySelector('#logTitle').textContent = file;
    body.querySelector('#btnLogMore').hidden = (data.lines || []).length < 300;
    body.querySelector('#btnLogTail').hidden = false;
  };

  body.querySelectorAll('[data-log]').forEach((button) => {
    button.addEventListener('click', async () => {
      currentFile = button.dataset.log;
      offset = 0;
      body.querySelector('#logContent').textContent = '加载中…';
      try {
        await load(currentFile, 0, true);
      } catch (error) {
        body.querySelector('#logContent').textContent = error.message || '读取失败';
      }
    });
  });

  body.querySelector('#btnLogMore')?.addEventListener('click', async () => {
    if (!currentFile) return;
    try {
      await load(currentFile, offset, false);
    } catch (error) {
      toast(error.message || '读取失败', 'error');
    }
  });

  body.querySelector('#btnLogTail')?.addEventListener('click', async () => {
    if (!currentFile) return;
    try {
      const data = await get(`/api/logs/view?file=${encodeURIComponent(currentFile)}&offset=0&limit=300`);
      appendLines(data.lines || [], true);
      offset = (data.lines || []).length;
      body.querySelector('#logMeta').textContent = `${currentFile} · 已加载 ${offset} 行`;
    } catch (error) {
      toast(error.message || '读取失败', 'error');
    }
  });
}

/* ---------------- 设置 ---------------- */

async function renderSettings(body) {
  const settings = await get('/api/knowledge/settings');
  const rows = [
    ['检索提供方', settings.provider],
    ['稠密集合', settings.denseCollection],
    ['BM25 集合', settings.bm25Collection],
    ['向量维度', settings.dimension],
    ['Embedding 模型', settings.embeddingTarget],
    ['Embedding 可用', settings.embeddingEnabled ? '是' : '否'],
    ['Rerank', settings.rerankEnabled ? '已启用' : '未启用'],
    ['查询扩展', settings.queryExpansionEnabled ? '已启用' : '未启用'],
    ['分段目标长度', `${settings.chunkTargetChars} 字符`],
    ['分段硬上限', `${settings.chunkMaxChars} 字符（段落/表格行不跨块）`],
    ['父块上限', `${settings.chunkParentChars} 字符`],
    ['单次重建上限', `${settings.maxReembedChunks} 段`],
    ['分页上限', `${settings.maxPageSize} 段/页`],
    ['单段最大字符', `${settings.maxChunkChars}`],
    ['原文目录', settings.storageDir],
    ['RRF k', settings.rrfK],
    ['稠密权重 / BM25 权重', `${settings.denseWeight} / ${settings.bm25Weight}`],
    ['topK / 候选数', `${settings.topK} / ${settings.candidateLimit}`],
    ['最小向量相似度', settings.minVectorSimilarity],
  ];

  body.innerHTML = `
    <div class="cs-stack">
      <section class="cs-card">
        <div class="cs-card-head"><h2>RAG 配置</h2><span class="cs-sub">只读，来自 application.yml</span></div>
        <div class="cs-kv">
          ${rows.map(([label, value]) => `<div class="cs-kv-row"><span>${escapeHtml(label)}</span><span class="cs-mono">${escapeHtml(String(value ?? '—'))}</span></div>`).join('')}
        </div>
      </section>
      <section class="cs-card">
        <div class="cs-card-head"><h2>管理令牌</h2><span class="cs-sub">远程访问管理接口时需要</span></div>
        <div class="cs-inline">
          <input class="cs-input" id="adminToken" placeholder="X-Admin-Token" style="flex:1;min-width:220px">
          <button class="btn primary" id="btnSaveToken">保存</button>
          <button class="btn-sm" id="btnClearToken">清除</button>
        </div>
        <div class="cs-hint" style="margin-top:8px">令牌保存在浏览器 localStorage（键名 agentchat.adminToken），仅本机访问时通常无需填写。</div>
      </section>
    </div>`;

  const tokenInput = body.querySelector('#adminToken');
  tokenInput.value = localStorage.getItem('agentchat.adminToken') || '';
  body.querySelector('#btnSaveToken')?.addEventListener('click', () => {
    const value = tokenInput.value.trim();
    if (value) localStorage.setItem('agentchat.adminToken', value);
    else localStorage.removeItem('agentchat.adminToken');
    toast('已保存');
  });
  body.querySelector('#btnClearToken')?.addEventListener('click', () => {
    localStorage.removeItem('agentchat.adminToken');
    tokenInput.value = '';
    toast('已清除');
  });
}

/* 兼容 admin.js 旧导出（如仍被引用时不至于报错） */
export function initAdmin() {
  initConsole();
}

export const __internal = { fmtBytes, renderMarkdown, enhance, upload };
