// 模型配置工作台：语言模型 / Embedding / 流量监控
import { get, post, del } from './api.js';
import { escapeHtml } from './markdown.js';

const state = {
  tab: 'chat',
  config: null,
  agents: [],
  window: '24h',
  traffic: null,
  reindexTimer: null,
  editingChat: null,
  editingEmbedding: null,
};

/* ---------------- 对外入口 ---------------- */

export async function renderModels(body) {
  clearReindexTimer();
  body.innerHTML = '<div class="cs-loading">加载模型配置…</div>';
  try {
    const [config, agents] = await Promise.all([
      get('/api/admin/models'),
      get('/api/admin/agents').catch(() => []),
    ]);
    state.config = config;
    state.agents = agents || [];
    if (state.tab === 'traffic' || !state.tab) state.tab = state.tab || 'chat';
    await renderShell(body);
    if (state.config.reindex?.running) watchReindex();
  } catch (error) {
    body.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载模型配置失败')}</div>`;
  }
}

/* ---------------- 骨架与 tab ---------------- */

async function renderShell(body) {
  const tabs = [
    ['chat', '语言模型'],
    ['embedding', 'Embedding'],
    ['traffic', '流量监控'],
  ];
  const tabHtml = tabs
    .map(([id, label]) => `<button type="button" data-mdl-tab="${id}" class="${state.tab === id ? 'active' : ''}">${label}</button>`)
    .join('');
  body.innerHTML = `
    <div class="cs-stack">
      <div class="mdl-tabs">${tabHtml}</div>
      <div id="mdlPane"></div>
    </div>`;
  body.querySelectorAll('[data-mdl-tab]').forEach((button) => {
    button.addEventListener('click', () => {
      state.tab = button.dataset.mdlTab;
      state.editingChat = null;
      state.editingEmbedding = null;
      renderShell(body);
    });
  });
  const pane = body.querySelector('#mdlPane');
  if (state.tab === 'chat') renderChatTab(pane);
  else if (state.tab === 'embedding') await renderEmbeddingTab(pane);
  else await renderTrafficTab(pane);
}

function card(title, subtitle, actionsHtml, bodyHtml) {
  return `
    <section class="cs-card">
      <div class="cs-card-head">
        <div>
          <h3>${escapeHtml(title)}</h3>
          ${subtitle ? `<p class="cs-hint">${subtitle}</p>` : ''}
        </div>
        ${actionsHtml ? `<div class="cs-inline">${actionsHtml}</div>` : ''}
      </div>
      ${bodyHtml || ''}
    </section>`;
}

function badge(ok, text) {
  return `<span class="badge ${ok ? 'ok' : 'off'}">${escapeHtml(text)}</span>`;
}

/* ---------------- 语言模型 ---------------- */

function renderChatTab(pane) {
  const config = state.config;
  const profiles = config.chatProfiles || [];
  const rows = profiles.map((profile) => `
    <tr>
      <td>
        <div class="mdl-name">${escapeHtml(profile.name || profile.modelName)}
          ${profile.id === config.defaultChatId ? '<span class="badge ok">默认</span>' : ''}
        </div>
        <div class="mdl-meta cs-mono">${escapeHtml(profile.id)} · ${escapeHtml(profile.modelName)}</div>
      </td>
      <td class="cs-mono mdl-url">${escapeHtml(profile.baseUrl)}</td>
      <td>${escapeHtml(String(profile.temperature))} / ${profile.maxTokens}</td>
      <td class="cs-mono">${escapeHtml(profile.apiKeyMasked || '—')}</td>
      <td>${profile.usable ? badge(true, '可用') : badge(false, '停用')}</td>
      <td class="mdl-actions">
        <button class="btn-sm" data-chat-edit="${escapeHtml(profile.id)}">编辑</button>
        <button class="btn-sm" data-chat-test="${escapeHtml(profile.id)}">测试</button>
        <button class="btn-sm" data-chat-default="${escapeHtml(profile.id)}">设默认</button>
        <button class="btn-sm danger" data-chat-del="${escapeHtml(profile.id)}">删除</button>
      </td>
    </tr>`).join('');

  const table = card('语言模型配置', '每套配置对应一个 OpenAI 兼容的 /chat/completions 服务',
    '<button class="btn-sm primary" id="mdlChatAdd">新增模型</button>',
    profiles.length
      ? `<div class="cs-table-wrap"><table class="admin-table">
           <thead><tr><th>名称 / 模型</th><th>base-url</th><th>temperature/tokens</th><th>密钥</th><th>状态</th><th>操作</th></tr></thead>
           <tbody>${rows}</tbody></table></div>`
      : '<div class="cs-empty">还没有语言模型配置</div>');

  const form = state.editingChat === 'new' || state.editingChat
    ? renderChatForm(profiles.find((profile) => profile.id === state.editingChat) || null)
    : '';

  const agentRows = state.agents.map((agent) => `
    <tr>
      <td><div class="mdl-name">${escapeHtml(agent.name || agent.id)}</div>
          <div class="mdl-meta cs-mono">${escapeHtml(agent.id)}${agent.toolGroup ? ' · ' + escapeHtml(agent.toolGroup) : ''}</div></td>
      <td><select class="cs-select" data-agent-model="${escapeHtml(agent.id)}">
        <option value="">跟随默认模型</option>
        ${profiles.map((profile) => `<option value="${escapeHtml(profile.id)}"
          ${config.agentProfiles?.[agent.id] === profile.id ? 'selected' : ''}>${escapeHtml(profile.name || profile.modelName)}</option>`).join('')}
      </select></td>
    </tr>`).join('');
  const agentsCard = card('Agent 模型映射', '不指定时所有 Agent 使用默认模型；编排模式下 worker 各用各的映射', '',
    state.agents.length
      ? `<div class="cs-table-wrap"><table class="admin-table"><thead><tr><th>Agent</th><th>绑定模型</th></tr></thead>
         <tbody>${agentRows}</tbody></table></div>
         <div class="cs-inline"><button class="btn-sm primary" id="mdlAgentSave">保存映射</button></div>`
      : '<div class="cs-empty">没有已注册的 Agent</div>');

  pane.innerHTML = `<div class="cs-stack">${table}${form}${agentsCard}</div>`;
  bindChatTab(pane);
}

function renderChatForm(profile) {
  const editing = Boolean(profile);
  const value = (key, fallback) => escapeHtml(profile?.[key] ?? fallback ?? '');
  return card(editing ? '编辑语言模型' : '新增语言模型', '密钥留空表示沿用已保存的密钥',
    `<button class="btn-sm" id="mdlChatCancel">取消</button>`,
    `<form id="mdlChatForm" class="form-grid">
      <label>配置 id<input class="cs-input cs-mono" name="id" value="${value('id')}" ${editing ? 'readonly' : ''} placeholder="例如 fast-model"></label>
      <label>显示名称<input class="cs-input" name="name" value="${value('name')}" placeholder="例如 快速模型"></label>
      <label class="full">base-url<input class="cs-input cs-mono" name="baseUrl" value="${value('baseUrl', 'https://dashscope.aliyuncs.com/compatible-mode/v1')}"></label>
      <label class="full">API Key<input class="cs-input" name="apiKey" autocomplete="off"
        placeholder="${escapeHtml(profile?.apiKeyMasked || 'sk-…')}"></label>
      <label>模型名<input class="cs-input cs-mono" name="modelName" value="${value('modelName')}" placeholder="deepseek-chat"></label>
      <label>temperature<input class="cs-input" name="temperature" type="number" step="0.1" min="0" max="2" value="${value('temperature', '0.7')}"></label>
      <label>max-tokens<input class="cs-input" name="maxTokens" type="number" min="1" value="${value('maxTokens', '4096')}"></label>
      <label class="mdl-check"><input type="checkbox" name="enabled" ${profile?.enabled === false ? '' : 'checked'}> 启用</label>
      <div class="full cs-inline">
        <button class="btn-sm primary" type="submit">保存</button>
        <span class="cs-hint">保存后立即生效，新会话默认使用默认模型</span>
      </div>
    </form>`);
}

function bindChatTab(pane) {
  pane.querySelector('#mdlChatAdd')?.addEventListener('click', () => { state.editingChat = 'new'; renderShell(pane.closest('.console-body')); });
  pane.querySelector('#mdlChatCancel')?.addEventListener('click', () => { state.editingChat = null; renderShell(pane.closest('.console-body')); });
  pane.querySelectorAll('[data-chat-edit]').forEach((button) => button.addEventListener('click', () => {
    state.editingChat = button.dataset.chatEdit;
    renderShell(pane.closest('.console-body'));
  }));
  pane.querySelectorAll('[data-chat-test]').forEach((button) => button.addEventListener('click', async () => {
    const id = button.dataset.chatTest;
    button.disabled = true;
    button.textContent = '测试中…';
    try {
      const result = await post(`/api/admin/models/chat/${encodeURIComponent(id)}/test`, {});
      toast(result.ok
        ? `连通成功：${result.modelName}，${result.latencyMs}ms，返回「${(result.sample || '').slice(0, 40)}」`
        : `连通失败：${result.error || '未知错误'}`, result.ok ? 'ok' : 'error');
    } catch (error) {
      toast(error.message || '测试失败', 'error');
    } finally {
      button.disabled = false;
      button.textContent = '测试';
    }
  }));
  pane.querySelectorAll('[data-chat-default]').forEach((button) => button.addEventListener('click', async () => {
    try {
      await post('/api/admin/models/default', { kind: 'chat', id: button.dataset.chatDefault });
      toast('默认模型已更新', 'ok');
      await reload(pane);
    } catch (error) {
      toast(error.message || '设置失败', 'error');
    }
  }));
  pane.querySelectorAll('[data-chat-del]').forEach((button) => button.addEventListener('click', async () => {
    const id = button.dataset.chatDel;
    if (!window.confirm(`确认删除模型配置 ${id}？`)) return;
    try {
      await del(`/api/admin/models/chat/${encodeURIComponent(id)}`);
      toast('已删除', 'ok');
      await reload(pane);
    } catch (error) {
      toast(error.message || '删除失败', 'error');
    }
  }));
  const form = pane.querySelector('#mdlChatForm');
  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    const payload = {
      id: String(data.get('id') || '').trim(),
      name: String(data.get('name') || '').trim(),
      baseUrl: String(data.get('baseUrl') || '').trim(),
      apiKey: String(data.get('apiKey') || '').trim(),
      modelName: String(data.get('modelName') || '').trim(),
      temperature: Number(data.get('temperature') || 0.7),
      maxTokens: Number(data.get('maxTokens') || 4096),
      enabled: data.get('enabled') === 'on',
      builtin: false,
    };
    try {
      await post('/api/admin/models/chat', payload);
      toast('语言模型已保存', 'ok');
      state.editingChat = null;
      await reload(pane);
    } catch (error) {
      toast(error.message || '保存失败', 'error');
    }
  });
  pane.querySelector('#mdlAgentSave')?.addEventListener('click', async () => {
    const mapping = {};
    pane.querySelectorAll('[data-agent-model]').forEach((select) => {
      if (select.value) mapping[select.dataset.agentModel] = select.value;
    });
    try {
      await post('/api/admin/models/agent-map', mapping);
      toast('Agent 映射已保存', 'ok');
      await reload(pane);
    } catch (error) {
      toast(error.message || '保存失败', 'error');
    }
  });
}

/* ---------------- Embedding ---------------- */

async function renderEmbeddingTab(pane) {
  const config = state.config;
  const profiles = config.embeddingProfiles || [];
  const rows = profiles.map((profile) => `
    <tr>
      <td>
        <div class="mdl-name">${escapeHtml(profile.name || profile.modelName)}
          ${profile.id === config.defaultEmbeddingId ? '<span class="badge ok">当前</span>' : ''}
        </div>
        <div class="mdl-meta cs-mono">${escapeHtml(profile.id)} · ${escapeHtml(profile.modelName)}</div>
      </td>
      <td class="cs-mono mdl-url">${escapeHtml(profile.baseUrl)}</td>
      <td>${profile.dimension > 0 ? profile.dimension : '自动探测'}</td>
      <td>${profile.maxTokens}</td>
      <td class="cs-mono">${escapeHtml(profile.apiKeyMasked || '—')}</td>
      <td>${profile.usable ? badge(true, '可用') : badge(false, '停用')}</td>
      <td class="mdl-actions">
        <button class="btn-sm" data-emb-edit="${escapeHtml(profile.id)}">编辑</button>
        <button class="btn-sm" data-emb-test="${escapeHtml(profile.id)}">测试</button>
        <button class="btn-sm primary" data-emb-apply="${escapeHtml(profile.id)}">应用</button>
        <button class="btn-sm danger" data-emb-del="${escapeHtml(profile.id)}"
          ${profile.id === config.defaultEmbeddingId ? 'disabled title="当前正在应用的配置不能删除，请先应用其它配置"' : ''}>删除</button>
      </td>
    </tr>`).join('');

  const table = card('Embedding 模型配置', '切换 Embedding 后向量维度若变化，必须重建知识库索引',
    '<button class="btn-sm primary" id="mdlEmbAdd">新增配置</button>',
    profiles.length
      ? `<div class="cs-table-wrap"><table class="admin-table">
           <thead><tr><th>名称 / 模型</th><th>base-url</th><th>维度</th><th>max-tokens</th><th>密钥</th><th>状态</th><th>操作</th></tr></thead>
           <tbody>${rows}</tbody></table></div>`
      : '<div class="cs-empty">还没有 Embedding 配置</div>');

  const form = state.editingEmbedding === 'new' || state.editingEmbedding
    ? renderEmbeddingForm(profiles.find((profile) => profile.id === state.editingEmbedding) || null)
    : '';

  const progress = config.reindex || {};
  const progressCard = card('索引重建', progress.message || '尚未执行',
    progress.running ? '<span class="badge warn">进行中</span>' : '',
    `<div class="mdl-reindex">
       <div class="kb-bar"><i style="width:${progress.total ? Math.round((progress.done / progress.total) * 100) : 0}%"></i></div>
       <div class="cs-hint">集合版本 ${escapeHtml(config.collectionVersion || 'v1')} ·
         进度 ${progress.done || 0}/${progress.total || 0}
         ${progress.failed ? ` · 失败 ${progress.failed}` : ''}
         ${progress.currentFile ? ` · 正在处理 ${escapeHtml(progress.currentFile)}` : ''}</div>
     </div>`);

  pane.innerHTML = `<div class="cs-stack">${table}${form}${progressCard}</div>`;
  bindEmbeddingTab(pane);
}

function renderEmbeddingForm(profile) {
  const editing = Boolean(profile);
  const value = (key, fallback) => escapeHtml(profile?.[key] ?? fallback ?? '');
  return card(editing ? '编辑 Embedding 配置' : '新增 Embedding 配置', 'dimension 填 0 表示首次调用时自动探测',
    '<button class="btn-sm" id="mdlEmbCancel">取消</button>',
    `<form id="mdlEmbForm" class="form-grid">
      <label>配置 id<input class="cs-input cs-mono" name="id" value="${value('id')}" ${editing ? 'readonly' : ''} placeholder="embedding-v2"></label>
      <label>显示名称<input class="cs-input" name="name" value="${value('name')}"></label>
      <label class="full">base-url<input class="cs-input cs-mono" name="baseUrl" value="${value('baseUrl', 'https://dashscope.aliyuncs.com/compatible-mode/v1')}"></label>
      <label class="full">API Key<input class="cs-input" name="apiKey" autocomplete="off" placeholder="${escapeHtml(profile?.apiKeyMasked || 'sk-…')}"></label>
      <label>模型名<input class="cs-input cs-mono" name="modelName" value="${value('modelName')}" placeholder="text-embedding-v3"></label>
      <label>max-tokens<input class="cs-input" name="maxTokens" type="number" min="1" value="${value('maxTokens', '512')}"></label>
      <label>维度 dimension<input class="cs-input" name="dimension" type="number" min="0" value="${value('dimension', '0')}"></label>
      <label class="mdl-check"><input type="checkbox" name="enabled" ${profile?.enabled === false ? '' : 'checked'}> 启用</label>
      <div class="full cs-inline">
        <button class="btn-sm primary" type="submit">保存</button>
        <span class="cs-hint">保存只改配置；点列表里的「应用」才会切换默认并触发重建</span>
      </div>
    </form>`);
}

function bindEmbeddingTab(pane) {
  const shell = pane.closest('.console-body');
  pane.querySelector('#mdlEmbAdd')?.addEventListener('click', () => { state.editingEmbedding = 'new'; renderShell(shell); });
  pane.querySelector('#mdlEmbCancel')?.addEventListener('click', () => { state.editingEmbedding = null; renderShell(shell); });
  pane.querySelectorAll('[data-emb-edit]').forEach((button) => button.addEventListener('click', () => {
    state.editingEmbedding = button.dataset.embEdit;
    renderShell(shell);
  }));
  pane.querySelectorAll('[data-emb-test]').forEach((button) => button.addEventListener('click', async () => {
    const id = button.dataset.embTest;
    button.disabled = true;
    button.textContent = '测试中…';
    try {
      const result = await post(`/api/admin/models/embedding/${encodeURIComponent(id)}/test`, {});
      toast(result.ok
        ? `连通成功：${result.modelName}，维度 ${result.dimension}，${result.latencyMs}ms`
        : `连通失败：${result.error || '未知错误'}`, result.ok ? 'ok' : 'error');
    } catch (error) {
      toast(error.message || '测试失败', 'error');
    } finally {
      button.disabled = false;
      button.textContent = '测试';
    }
  }));
  pane.querySelectorAll('[data-emb-apply]').forEach((button) => button.addEventListener('click', () => applyEmbedding(button, shell)));
  pane.querySelectorAll('[data-emb-del]').forEach((button) => button.addEventListener('click', async () => {
    const id = button.dataset.embDel;
    if (!id) return;
    if (!window.confirm(`确认删除 Embedding 配置 ${id}？删除后不可恢复，已生成的向量不受影响。`)) return;
    button.disabled = true;
    try {
      await del(`/api/admin/models/embedding/${encodeURIComponent(id)}`);
      if (state.editingEmbedding === id) state.editingEmbedding = null;
      toast('Embedding 配置已删除', 'ok');
      await reload(pane);
    } catch (error) {
      toast(error.message || '删除失败', 'error');
      button.disabled = false;
    }
  }));
  const form = pane.querySelector('#mdlEmbForm');
  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    const payload = {
      id: String(data.get('id') || '').trim(),
      name: String(data.get('name') || '').trim(),
      baseUrl: String(data.get('baseUrl') || '').trim(),
      apiKey: String(data.get('apiKey') || '').trim(),
      modelName: String(data.get('modelName') || '').trim(),
      maxTokens: Number(data.get('maxTokens') || 512),
      dimension: Number(data.get('dimension') || 0),
      enabled: data.get('enabled') === 'on',
      builtin: false,
    };
    try {
      await post('/api/admin/models/embedding', payload);
      toast('Embedding 配置已保存', 'ok');
      state.editingEmbedding = null;
      await reload(pane);
    } catch (error) {
      toast(error.message || '保存失败', 'error');
    }
  });
}

async function applyEmbedding(button, shell) {
  const id = button.dataset.embApply;
  if (!window.confirm('应用该 Embedding 配置？若维度变化会立即重建整个知识库索引。')) return;
  button.disabled = true;
  button.textContent = '应用中…';
  try {
    await post('/api/admin/models/embedding/apply', { id, rebuild: false });
    toast('Embedding 配置已应用', 'ok');
  } catch (error) {
    if (error.status === 409) {
      const rebuild = window.confirm(`${error.message}\n\n现在重建索引吗？（会重新解析并重新嵌入所有已上传文档）`);
      if (rebuild) {
        try {
          await post('/api/admin/models/embedding/apply', { id, rebuild: true });
          toast('已切换配置并开始重建索引', 'ok');
        } catch (inner) {
          toast(inner.message || '应用失败', 'error');
        }
      }
    } else {
      toast(error.message || '应用失败', 'error');
    }
  } finally {
    button.disabled = false;
    button.textContent = '应用';
    await reload(shell);
  }
}

/* ---------------- 流量监控 ---------------- */

async function renderTrafficTab(pane) {
  let data;
  try {
    data = await get(`/api/admin/models/traffic?window=${encodeURIComponent(state.window)}`);
    state.traffic = data;
  } catch (error) {
    pane.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载流量数据失败')}</div>`;
    return;
  }
  const totals = data.totals || {};
  const windows = [['1h', '近 1 小时'], ['24h', '近 24 小时'], ['7d', '近 7 天']];
  const switchHtml = windows.map(([id, label]) =>
    `<button class="btn-sm ${state.window === id ? 'primary' : ''}" data-tf-window="${id}">${label}</button>`).join('');

  const stat = (label, value, hint) => `
    <div class="cs-stat">
      <div class="cs-stat-label">${escapeHtml(label)}</div>
      <div class="cs-stat-value">${escapeHtml(String(value))}</div>
      ${hint ? `<div class="cs-stat-hint">${escapeHtml(hint)}</div>` : ''}
    </div>`;

  const statsHtml = `
    <div class="cs-grid">
      ${stat('调用次数', totals.calls ?? 0, `采样 ${data.sampledCalls ?? 0} 条`)}
      ${stat('错误率', formatPercent(totals.errorRate), `失败 ${totals.errors ?? 0} 次`)}
      ${stat('输入 tokens', formatNumber(totals.inputTokens), `输出 ${formatNumber(totals.outputTokens)}`)}
      ${stat('缓存命中 tokens', formatNumber(totals.cachedTokens), 'cached tokens')}
      ${stat('平均延迟', `${formatNumber(totals.avgLatencyMs)} ms`, `p95 ${formatNumber(totals.p95LatencyMs)} ms`)}
      ${stat('每分钟调用', totals.rpm ?? 0, 'rpm')}
    </div>`;

  const series = data.series || [];
  const maxCalls = Math.max(1, ...series.map((point) => point.calls || 0));
  const seriesHtml = series.length
    ? `<div class="mdl-bars">${series.map((point) => `
        <div class="mdl-bar" title="${escapeHtml(point.label || '')}｜调用 ${point.calls || 0}｜错误 ${point.errors || 0}">
          <i style="height:${Math.max(2, Math.round(((point.calls || 0) / maxCalls) * 100))}%"></i>
          <span>${escapeHtml(point.label || '')}</span>
        </div>`).join('')}</div>`
    : '<div class="cs-empty">该时间窗内没有调用记录</div>';

  const profileRows = (data.byProfile || []).map((item) => `
    <tr>
      <td>${escapeHtml(item.kind || '')}</td>
      <td class="cs-mono">${escapeHtml(item.profileId || '')}</td>
      <td class="cs-mono">${escapeHtml(item.modelName || '')}</td>
      <td>${item.calls ?? 0}</td>
      <td>${item.errors ?? 0}</td>
      <td>${formatNumber(item.inputTokens)}</td>
      <td>${formatNumber(item.outputTokens)}</td>
      <td>${formatNumber(item.avgLatencyMs)} ms</td>
    </tr>`).join('');

  const kindRows = (data.byKind || []).map((item) => `
    <tr><td>${escapeHtml(item.kind || '')}</td><td>${item.calls ?? 0}</td>
    <td>${item.errors ?? 0}</td><td>${formatNumber(item.inputTokens + item.outputTokens)}</td></tr>`).join('');

  const recentRows = (data.recent || []).map((item) => `
    <tr>
      <td class="cs-mono">${formatTime(item.ts)}</td>
      <td>${escapeHtml(item.kind || '')}</td>
      <td class="cs-mono">${escapeHtml(item.profileId || '')}</td>
      <td class="cs-mono">${escapeHtml(item.modelName || '')}</td>
      <td>${escapeHtml(item.agentId || '—')}</td>
      <td>${formatNumber(item.inputTokens)} / ${formatNumber(item.outputTokens)}</td>
      <td>${formatNumber(item.latencyMs)} ms</td>
      <td>${item.ok ? badge(true, '成功') : badge(false, '失败')}</td>
      <td class="mdl-err">${escapeHtml(item.error || '')}</td>
    </tr>`).join('');

  pane.innerHTML = `
    <div class="cs-stack">
      ${card('流量概览', `时间窗 ${escapeHtml(data.window || state.window)} · Redis ${data.redisAvailable ? '已接入' : '未接入（仅内存）'}`,
        `${switchHtml}<button class="btn-sm danger" id="mdlTrafficReset">重置统计</button>`, statsHtml)}
      ${card('调用趋势', '按时间桶统计的调用次数', '', seriesHtml)}
      ${card('按配置聚合', '', '',
        (data.byProfile || []).length
          ? `<div class="cs-table-wrap"><table class="admin-table">
              <thead><tr><th>类型</th><th>配置</th><th>模型</th><th>调用</th><th>失败</th><th>输入</th><th>输出</th><th>平均延迟</th></tr></thead>
              <tbody>${profileRows}</tbody></table></div>`
          : '<div class="cs-empty">暂无数据</div>')}
      ${card('按类型聚合', '', '',
        (data.byKind || []).length
          ? `<div class="cs-table-wrap"><table class="admin-table">
              <thead><tr><th>类型</th><th>调用</th><th>失败</th><th>tokens</th></tr></thead>
              <tbody>${kindRows}</tbody></table></div>`
          : '<div class="cs-empty">暂无数据</div>')}
      ${card('最近调用', '内存中保留最近 50 条', '',
        (data.recent || []).length
          ? `<div class="cs-table-wrap"><table class="admin-table">
              <thead><tr><th>时间</th><th>类型</th><th>配置</th><th>模型</th><th>Agent</th><th>输入/输出</th><th>延迟</th><th>结果</th><th>错误</th></tr></thead>
              <tbody>${recentRows}</tbody></table></div>`
          : '<div class="cs-empty">暂无调用</div>')}
    </div>`;

  pane.querySelectorAll('[data-tf-window]').forEach((button) => button.addEventListener('click', async () => {
    state.window = button.dataset.tfWindow;
    await renderTrafficTab(pane);
  }));
  pane.querySelector('#mdlTrafficReset')?.addEventListener('click', async () => {
    if (!window.confirm('确认重置流量统计？')) return;
    try {
      await post('/api/admin/models/traffic/reset', {});
      toast('统计已重置', 'ok');
      await renderTrafficTab(pane);
    } catch (error) {
      toast(error.message || '重置失败', 'error');
    }
  });
}

/* ---------------- 工具 ---------------- */

async function reload(pane) {
  const body = pane.closest('.console-body') || document.getElementById('consoleBody');
  if (!body) return;
  try {
    state.config = await get('/api/admin/models');
  } catch (_) { /* ignore */ }
  await renderShell(body);
  if (state.config?.reindex?.running) watchReindex();
}

function watchReindex() {
  clearReindexTimer();
  state.reindexTimer = setInterval(async () => {
    try {
      const progress = await get('/api/admin/models/embedding/reindex');
      if (!progress.running) {
        clearReindexTimer();
        toast(`索引重建完成：成功 ${progress.done}，失败 ${progress.failed}`, progress.failed ? 'warn' : 'ok');
        if (state.tab === 'embedding') await reload(document.getElementById('consoleBody'));
        return;
      }
      if (state.tab === 'embedding') await reload(document.getElementById('consoleBody'));
    } catch (_) {
      clearReindexTimer();
    }
  }, 3000);
}

function clearReindexTimer() {
  if (state.reindexTimer) {
    clearInterval(state.reindexTimer);
    state.reindexTimer = null;
  }
}

function formatNumber(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return '0';
  if (number >= 1_000_000) return (number / 1_000_000).toFixed(2) + 'M';
  if (number >= 1_000) return (number / 1_000).toFixed(1) + 'k';
  return String(Math.round(number));
}

function formatPercent(value) {
  const number = Number(value || 0);
  return `${(number * 100).toFixed(1)}%`;
}

function formatTime(ts) {
  if (!ts) return '—';
  const date = new Date(Number(ts));
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`;
}

function toast(message, kind = 'ok') {
  const el = document.getElementById('toast');
  if (!el) {
    window.alert(message);
    return;
  }
  el.textContent = message;
  el.className = `toast show ${kind}`;
  clearTimeout(el._timer);
  el._timer = setTimeout(() => {
    el.className = 'toast';
  }, 2600);
}
