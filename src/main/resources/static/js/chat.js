import { get, post, del, uploadFile } from './api.js';
import { streamChat } from './sse.js';
import { renderMarkdown, enhance, escapeHtml } from './markdown.js';
import { emit, on } from './bus.js';
import {
  applyMapCommand,
  clearMap,
  replayMapCommands,
  setMapConversation,
  onMapSelect,
  openEarthWindow,
} from './map-bridge.js';

const MAX_FILES = 5;
const MAX_FILE_SIZE = 20 * 1024 * 1024;
const PARALLEL_KEY = 'agentchat.orchestrateParallel';
const MODEL_KEY = 'agentchat.model';
const MODE_LABELS = { chat: '对话', deep: '深度思考', orchestrate: '多 Agent' };
const ALLOWED_EXTENSIONS = new Set([
  '.pdf', '.docx', '.xlsx', '.xls',
  '.txt', '.md', '.markdown', '.csv', '.tsv', '.json', '.jsonl', '.log',
  '.yaml', '.yml', '.xml', '.html', '.htm', '.sql', '.properties', '.ini',
  '.conf', '.sh', '.bat', '.ps1', '.java', '.py', '.js', '.ts', '.css',
]);

const state = {
  conversationId: null,
  mode: 'chat',
  parallel: false,
  streaming: false,
  runId: null,
  controller: null,
  lastUserSeq: 0,
  lastUserText: '',
  lastUserFileIds: [],
  lastUserAttachments: [],
  lastAssistantEl: null,
  usage: null,
  modelId: null,
  modelUsage: null,
};

/** 待发送附件：id -> {id,name,size,chars,status} */
const pendingFiles = new Map();

const $ = (id) => document.getElementById(id);

export function initChat() {
  $('btnSend').addEventListener('click', () => (state.streaming ? stop() : send()));
  $('input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!state.streaming) send();
    }
  });
  $('input').addEventListener('input', autosize);

  $('btnAttach')?.addEventListener('click', () => {
    if (!state.streaming) $('fileInput').click();
  });
  $('fileInput')?.addEventListener('change', (event) => {
    [...(event.target.files || [])].forEach(addLocalFile);
    event.target.value = '';
  });
  initDropZone();

  document.querySelectorAll('#modeSwitch button').forEach((button) => {
    button.addEventListener('click', () => setMode(button.dataset.mode));
  });

  const parallelCheck = $('parallelCheck');
  state.parallel = localStorage.getItem(PARALLEL_KEY) === '1';
  if (parallelCheck) {
    parallelCheck.checked = state.parallel;
    parallelCheck.addEventListener('change', () => {
      state.parallel = parallelCheck.checked;
      localStorage.setItem(PARALLEL_KEY, state.parallel ? '1' : '0');
    });
  }
  syncParallelToggle();

  on('conversation:open', (id) => openConversation(id));
  on('conversation:new', () => reset());
  on('ui:toast', ({ message, ok } = {}) => {
    if (message) toast(message, ok);
  });

  onMapSelect((feature) => {
    const name = feature?.name || feature?.label || feature?.city;
    if (!name) return;
    const input = $('input');
    input.value = `介绍一下${name}`;
    autosize();
    input.focus();
    toast(`已填入：介绍一下${name}`);
  });

  initModelPicker();
  renderEmptyState();
  autosize();
}

/* ---------------- 会话级模型选择 ---------------- */

async function initModelPicker() {
  const picker = $('modelPicker');
  if (!picker) return;
  let models = [];
  try {
    const data = await get('/api/models');
    models = data.models || [];
    picker.innerHTML = models
      .map((model) => `<option value="${escapeHtml(model.id)}">${escapeHtml(model.name || model.modelName)}</option>`)
      .join('');
    if (!models.length) {
      picker.hidden = true;
      return;
    }
    const stored = localStorage.getItem(MODEL_KEY);
    const wanted = state.modelId
      || (stored && models.some((model) => model.id === stored) ? stored : null)
      || data.defaultId
      || models[0].id;
    picker.value = models.some((model) => model.id === wanted) ? wanted : models[0].id;
    state.modelId = picker.value || null;
    picker.hidden = false;
    picker.addEventListener('change', () => {
      state.modelId = picker.value || null;
      if (state.modelId) localStorage.setItem(MODEL_KEY, state.modelId);
    });
  } catch (_) {
    picker.hidden = true;
  }
}

function syncModelPicker(modelId) {
  const picker = $('modelPicker');
  if (!picker || picker.hidden) return;
  if (modelId && [...picker.options].some((option) => option.value === modelId)) {
    picker.value = modelId;
    state.modelId = modelId;
    return;
  }
  if (!modelId && picker.value) state.modelId = picker.value;
}

export function setMode(mode) {
  state.mode = mode || 'chat';
  document.querySelectorAll('#modeSwitch button').forEach((button) => {
    button.classList.toggle('active', button.dataset.mode === state.mode);
  });
  syncParallelToggle();
}

/** 「并行+汇总」仅在多 Agent 模式下可见 */
function syncParallelToggle() {
  const toggle = $('parallelToggle');
  if (!toggle) return;
  toggle.hidden = state.mode !== 'orchestrate';
  const check = $('parallelCheck');
  if (check) check.checked = state.parallel;
}

function autosize() {
  const input = $('input');
  if (!input) return;
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 200) + 'px';
}

function setStreaming(streaming) {
  state.streaming = streaming;
  const button = $('btnSend');
  button.classList.toggle('stop', streaming);
  button.textContent = streaming ? '■' : '➤';
  button.title = streaming ? '停止生成' : '发送';
  $('input').placeholder = streaming ? '正在生成，可点击停止…' : '给 AgentChat 发送消息…';
  const attach = $('btnAttach');
  if (attach) attach.disabled = streaming;
  const fileInput = $('fileInput');
  if (fileInput) fileInput.disabled = streaming;
}

function ensureConversationId() {
  return state.conversationId;
}

async function send(text, regenerateFromSeq, fileIdsOverride) {
  const input = $('input');
  const content = (text ?? input.value).trim();
  const uploading = [...pendingFiles.values()].some((file) => file.status === 'uploading');
  const ids = fileIdsOverride ?? [...pendingFiles.keys()];
  if (state.streaming || uploading || (!content && ids.length === 0)) return;

  const displayAttachments = ids.map((id) => {
    const local = pendingFiles.get(id);
    if (local) return { id: local.id, name: local.name, size: local.size };
    return (state.lastUserAttachments || []).find((item) => item.id === id);
  }).filter(Boolean);

  if (text === undefined) input.value = '';
  autosize();
  clearEmptyState();

  state.lastUserText = content;
  state.lastUserSeq = 0;
  state.lastUserFileIds = ids;
  state.lastUserAttachments = displayAttachments;
  appendUser(content, 0, displayAttachments);

  const assistant = createAssistant();
  assistant._mapCommands = [];
  state.lastAssistantEl = assistant;
  let buffer = '';
  let reasoning = '';
  let renderPending = false;
  let finalized = false;

  const renderSoon = () => {
    if (renderPending) return;
    renderPending = true;
    requestAnimationFrame(() => {
      renderPending = false;
      renderAssistantBody(assistant, buffer, true);
      scrollToBottom();
    });
  };

  const finalize = (status, stats) => {
    if (finalized) return;
    finalized = true;
    renderAssistantBody(assistant, buffer, false);
    assistant.classList.remove('streaming');
    assistant.classList.add(status || 'completed');
    if (stats) appendStats(assistant, stats);
    addMessageActions(assistant, buffer, true);
    state.streaming = false;
    state.runId = null;
    state.controller = null;
    setStreaming(false);
    emit('chat:finished', { conversationId: state.conversationId });
    scrollToBottom();
  };

  setStreaming(true);
  state.controller = new AbortController();

  try {
    await streamChat(
      {
        conversationId: ensureConversationId(),
        content,
        fileIds: ids.length ? ids : undefined,
        mode: state.mode,
        modelId: state.modelId || undefined,
        parallel: state.mode === 'orchestrate' ? state.parallel : undefined,
        regenerateFromSeq: regenerateFromSeq === undefined || regenerateFromSeq === null ? null : regenerateFromSeq,
      },
      {
        onEvent: (type, data) => {
          switch (type) {
            case 'meta': {
              state.conversationId = data.conversationId;
              state.runId = data.runId;
              setMapConversation(data.conversationId);
              state.lastUserSeq = data.userSeq;
              state.lastUserFileIds = (data.attachments || []).map((item) => item.id);
              state.lastUserAttachments = data.attachments || displayAttachments;
              clearAttachments();
              emit('conversation:changed', data.conversationId);
              if (data.modelId) syncModelPicker(data.modelId);
              if (data.modelFallbackReason) {
                assistant.appendChild(note(data.modelFallbackReason));
              }
              if (data.mode && data.mode !== state.mode) {
                assistant.appendChild(note(
                  `本轮按「${MODE_LABELS[data.mode] || data.mode}」执行（附件消息不支持多 Agent 编排）`));
              }
              break;
            }
            case 'delta': {
              buffer += data.text || '';
              renderSoon();
              break;
            }
            case 'reasoning': {
              reasoning += data.text || '';
              ensureReasoning(assistant, reasoning);
              break;
            }
            case 'tool_start': {
              addToolCard(assistant, data);
              break;
            }
            case 'tool_exec': {
              markToolExecuting(assistant, data);
              break;
            }
            case 'tool_end': {
              updateToolCard(assistant, data);
              break;
            }
            case 'tool_wait': {
              updateToolWait(assistant, data);
              break;
            }
            case 'agent_start': {
              upsertAgentChip(assistant, data, 'running');
              break;
            }
            case 'agent_result': {
              upsertAgentChip(assistant, data, data.status && data.status !== 'ok' ? 'error' : 'ok');
              break;
            }
            case 'file_created': {
              appendOutput(assistant, data);
              break;
            }
            case 'usage': {
              state.usage = data;
              break;
            }
            case 'model_usage': {
              state.modelUsage = data;
              break;
            }
            case 'map': {
              if (!Array.isArray(assistant._mapCommands)) assistant._mapCommands = [];
              assistant._mapCommands.push(data);
              applyMapCommand(data);
              break;
            }
            case 'error': {
              showMessageError(assistant, data.message || data.code || '生成失败');
              break;
            }
            case 'done': {
              finalize(data.status || 'completed', data);
              break;
            }
            default:
              break;
          }
        },
      },
      state.controller.signal,
    );
    finalize('completed');
  } catch (error) {
    if (error.name === 'AbortError') {
      if (buffer) buffer += '\n\n> 已停止生成';
      finalize('cancelled');
    } else {
      showMessageError(assistant, error.message || '生成失败');
      finalize('error');
    }
  }
}

async function stop() {
  if (!state.streaming) return;
  const runId = state.runId;
  state.controller?.abort();
  if (runId) {
    try { await post('/api/chat/stop', { runId }); } catch (_) { /* ignore */ }
  }
}

function reset() {
  state.conversationId = null;
  state.runId = null;
  state.lastUserSeq = 0;
  state.lastUserText = '';
  state.lastUserFileIds = [];
  state.lastUserAttachments = [];
  state.lastAssistantEl = null;
  state.usage = null;
  state.modelUsage = null;
  clearAttachments();
  $('messages').innerHTML = '';
  $('convTitle').textContent = '新对话';
  clearMap();
  setMapConversation(null);
  renderEmptyState();
  emit('conversation:active', null);
}

async function openConversation(id) {
  if (!id || state.streaming) return;
  try {
    const data = await get(`/api/conversations/${id}`);
    state.conversationId = id;
    setMapConversation(id);
    state.lastUserSeq = 0;
    state.lastUserText = '';
    state.lastUserFileIds = [];
    state.lastUserAttachments = [];
    clearAttachments();
    $('convTitle').textContent = data.conversation.title || '新对话';
    setMode(data.conversation.mode || 'chat');
    if (data.conversation.model) {
      state.modelId = data.conversation.model;
      syncModelPicker(data.conversation.model);
    }
    renderHistory(data.messages || []);
    emit('conversation:active', id);
  } catch (error) {
    toast(error.message || '加载对话失败', false);
  }
}

function renderHistory(messages) {
  const container = $('messages');
  container.innerHTML = '';
  let lastMapCommands = null;
  if (!messages.length) {
    renderEmptyState();
    clearMap();
    return;
  }
  for (const message of messages) {
    if (message.role === 'user') {
      const attachments = message.attachments || [];
      appendUser(message.content, message.seq, attachments);
      state.lastUserSeq = message.seq;
      state.lastUserText = message.content;
      state.lastUserFileIds = attachments.map((item) => item.id);
      state.lastUserAttachments = attachments;
    } else {
      const element = createAssistant();
      element._mapCommands = message.mapCommands || [];
      if (element._mapCommands.length) lastMapCommands = element._mapCommands;
      renderAssistantBody(element, message.content || '', false);
      if (message.reasoning) ensureReasoning(element, message.reasoning, true);
      (message.toolCalls || []).forEach((tool) => {
        addToolCard(element, tool);
        updateToolCard(element, tool);
      });
      (message.outputs || []).forEach((file) => appendOutput(element, {
        fileId: file.id,
        name: file.name,
        size: file.size,
      }));
      if (message.status === 'cancelled') element.appendChild(note('已停止生成'));
      if (message.status === 'error') element.appendChild(note('生成失败：' + (message.error || '未知错误'), true));
      appendStats(element, message);
      addMessageActions(element, message.content || '', true);
      state.lastAssistantEl = element;
    }
  }
  if (lastMapCommands && lastMapCommands.length) replayMapCommands(lastMapCommands);
  else clearMap();
  scrollToBottom(true);
}

function appendUser(text, seq, attachments) {
  const container = $('messages');
  const element = document.createElement('div');
  element.className = 'msg user';
  if (seq) element.dataset.seq = String(seq);
  const files = (attachments || []).filter(Boolean);
  const hasText = Boolean(text && text.trim());
  element.innerHTML = `
    ${files.length ? `<div class="msg-files">${files.map(fileChipHtml).join('')}</div>` : ''}
    ${hasText ? `<div class="bubble">${escapeHtml(text)}</div>` : ''}
    ${hasText ? '<div class="msg-actions"><button type="button" data-action="copy">复制</button></div>' : ''}`;
  if (hasText) {
    element.querySelector('[data-action="copy"]').addEventListener('click', () => copyText(text));
  }
  container.appendChild(element);
  scrollToBottom();
}

function createAssistant() {
  const container = $('messages');
  const element = document.createElement('div');
  element.className = 'msg assistant streaming';
  element.innerHTML = `
    <div class="msg-head"><span class="avatar">A</span><span class="name">AgentChat</span></div>
    <div class="agent-strip"></div>
    <div class="msg-body"></div>
    <span class="caret" aria-hidden="true"></span>
    <div class="tool-list"></div>
    <div class="msg-actions"></div>`;
  container.appendChild(element);
  scrollToBottom();
  return element;
}

function renderAssistantBody(element, text, streaming) {
  const body = element.querySelector('.msg-body');
  if (!body) return;
  body.innerHTML = text
    ? renderMarkdown(text)
    : (streaming ? '<p class="thinking-placeholder">正在生成…</p>' : '<p class="thinking-placeholder">（无内容）</p>');
  enhance(body);
}

function ensureReasoning(element, text, finished) {
  let details = element.querySelector('.reasoning');
  if (!details) {
    details = document.createElement('details');
    details.className = 'reasoning';
    details.innerHTML = '<summary><span class="r-icon">🧠</span> <span class="r-title">已深度思考</span></summary><div class="reasoning-body"></div>';
    element.insertBefore(details, element.querySelector('.msg-body'));
  }
  details.querySelector('.reasoning-body').textContent = text;
  if (!finished) details.open = true;
}

function addToolCard(element, data) {
  const list = element.querySelector('.tool-list');
  const card = document.createElement('details');
  card.className = 'tool-card running';
  card.dataset.callId = data.callId || data.name;
  card.innerHTML = `
    <summary>
      <span class="t-icon">🔧</span>
      <span class="t-name">${escapeHtml(data.name || '工具')}</span>
      <span class="t-server">${escapeHtml(data.server || '')}</span>
      <span class="t-status">运行中…</span>
    </summary>
    <div class="tool-meta"><div class="meta-line"></div><div class="meta-line tool-wait"></div></div>
    <pre class="tool-preview"></pre>`;
  list.appendChild(card);
}

function findToolCard(element, data) {
  const key = data.callId || data.name;
  for (const item of element.querySelectorAll('.tool-card')) {
    if (item.dataset.callId === key) return item;
  }
  return null;
}

/** 统一时间格式：<1s 用毫秒，≥1s 用秒（保留 1 位小数） */
function fmtMs(ms) {
  const value = Math.max(0, Math.round(ms || 0));
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(1)}s`;
}

/** 绝对时间：11:23:45.120 */
function fmtClock(ts) {
  const date = new Date(ts);
  const pad = (value, len = 2) => String(value).padStart(len, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

/** 结果大小：按预览字符数粗算，避免把"4.2KB 的 JSON"当成执行耗时 */
function fmtChars(text) {
  const length = (text || '').length;
  if (length < 1000) return `${length} 字符`;
  return `${(length / 1000).toFixed(1)}K 字符`;
}

function updateToolCard(element, data) {
  const card = findToolCard(element, data);
  if (!card) return;
  const failed = data.status === 'error';
  const cancelled = data.status === 'cancelled';
  // 旧数据只有 durationMs（整卡窗口），无法拆出工具执行耗时，降级显示"完成"
  const legacy = data.toolMs === undefined || data.toolMs === null;
  card.classList.remove('running');
  card.classList.toggle('error', failed);
  card.classList.toggle('cancelled', cancelled);
  card.classList.toggle('ok', !failed && !cancelled);

  const status = card.querySelector('.t-status');
  if (cancelled) status.textContent = '已中断';
  else if (failed) status.textContent = `失败 ${fmtMs(legacy ? data.durationMs : data.toolMs)}`;
  else if (legacy) status.textContent = '完成';
  else status.textContent = `执行 ${fmtMs(data.toolMs)}`;

  card.querySelector('summary').title = legacy
    ? '旧消息只记录了"调用开始到结果结束"的总窗口，未拆分工具执行耗时'
    : `工具执行 ${fmtMs(data.toolMs)}（从模型给出调用参数算到结果返回，含派发与回传；不含模型生成调用与后续等待）`;

  const meta = card.querySelector('.meta-line');
  if (meta) {
    const parts = [];
    if (data.startedAt) parts.push(`开始 ${fmtClock(data.startedAt)}`);
    if (data.endedAt) parts.push(`结束 ${fmtClock(data.endedAt)}`);
    if (data.callMs > 0) parts.push(`生成调用 ${fmtMs(data.callMs)}`);
    if (data.toolMs > 0) parts.push(`执行 ${fmtMs(data.toolMs)}`);
    if (data.preview) parts.push(`结果 ${fmtChars(data.preview)}`);
    meta.textContent = parts.join(' · ');
  }
  renderToolWait(card, data.waitMs);

  const preview = card.querySelector('.tool-preview');
  const text = data.preview || '';
  preview.textContent = text + (data.truncated ? '\n…（已截断）' : '');
  if (text) preview.classList.add('has-content');
}

/** tool_exec：模型给完调用参数，工具开始真正执行 */
function markToolExecuting(element, data) {
  const card = findToolCard(element, data);
  if (!card || !card.classList.contains('running')) return;
  const status = card.querySelector('.t-status');
  if (status) status.textContent = '执行中…';
}

/** 第二行灰色小字：结果返回后等待模型继续输出的时间，明确"不计入工具耗时" */
function renderToolWait(card, waitMs) {
  const line = card.querySelector('.tool-wait');
  if (!line) return;
  line.textContent = waitMs > 0
    ? `之后等待模型 ${fmtMs(waitMs)}（不计入工具耗时）`
    : '';
}

/** tool_wait 事件：等待时长要等下一个可见输出出现后才能算出来 */
function updateToolWait(element, data) {
  const card = findToolCard(element, data);
  if (card) renderToolWait(card, data.waitMs);
}

/** 消息底部汇总：本轮 = 模型 + 工具（工具按占用时间并集统计） */
function appendStats(element, data) {
  if (!data || !(data.elapsedMs > 0)) return;
  let box = element.querySelector('.msg-stats');
  if (!box) {
    box = document.createElement('div');
    box.className = 'msg-stats';
    const actions = element.querySelector('.msg-actions');
    if (actions) element.insertBefore(box, actions);
    else element.appendChild(box);
  }
  const elapsed = data.elapsedMs;
  const toolsWall = data.toolsWallMs || 0;
  // 实时事件带 toolsCount，历史消息只有 toolCalls 列表
  const count = data.toolsCount || (Array.isArray(data.toolCalls) ? data.toolCalls.length : 0);
  const modelMs = Math.max(0, elapsed - toolsWall);
  if (!count) {
    box.textContent = `本轮 ${fmtMs(elapsed)}`;
  } else if (toolsWall >= 1000) {
    // 工具占比可感知，写成加减关系（两者都按秒显示，误差 < 0.1s）
    box.textContent = `本轮 ${fmtMs(elapsed)} ＝ 模型 ${fmtMs(modelMs)} ＋ 工具 ${count} 次 ${fmtMs(toolsWall)}`;
  } else {
    // 工具只有几十毫秒时写成等式会出现"22.8s ＝ 22.8s ＋ 67ms"的观感错误，改用括注
    box.textContent = `本轮 ${fmtMs(elapsed)}（工具 ${count} 次共 ${fmtMs(toolsWall)}，其余为模型）`;
  }
  box.title = count
    ? '本轮墙钟总时长；工具耗时取各次调用的并集，并行调用不会重复累加'
    : '本轮墙钟总时长（未调用工具）';
}

/** 编排阶段芯片：agent_start 置 running，agent_result 置 ok/error */
function upsertAgentChip(element, data, phase) {
  const strip = element.querySelector('.agent-strip');
  if (!strip) return;
  const index = Number.isInteger(data.index) ? data.index : strip.children.length;
  const isSummary = data.role === 'summary';
  let chip = strip.querySelector(`[data-agent-index="${index}"]`);
  if (!chip) {
    chip = document.createElement('span');
    chip.className = 'agent-chip';
    chip.dataset.agentIndex = String(index);
    chip.innerHTML = '<span class="dot"></span><span class="chip-label"></span><span class="chip-meta"></span>';
    strip.appendChild(chip);
  }
  chip.classList.remove('running', 'ok', 'error');
  chip.classList.add(phase);
  if (isSummary) chip.classList.add('summary');
  const label = data.agentName || (isSummary ? '汇总' : `Agent ${index + 1}`);
  chip.querySelector('.chip-label').textContent = label;
  const meta = chip.querySelector('.chip-meta');
  if (phase === 'running') meta.textContent = isSummary ? '合并中…' : '执行中…';
  else if (phase === 'ok') meta.textContent = fmtMs(data.durationMs);
  else meta.textContent = data.status === 'timeout' ? '超时' : '失败';
  const tip = [label, '该 Agent 全过程耗时（含多轮模型与工具调用），与单个工具耗时不可比'];
  if (data.error) tip.push(`错误：${data.error}`);
  if (data.preview) tip.push(data.preview);
  chip.title = tip.join('\n');
  scrollToBottom();
}

function showMessageError(element, message) {
  element.classList.add('error');
  element.appendChild(note('⚠ ' + message, true));
}

function note(text, isError) {
  const element = document.createElement('div');
  element.className = 'msg-note' + (isError ? ' error' : '');
  element.textContent = text;
  return element;
}

function addMessageActions(element, text, finished) {
  const actions = element.querySelector('.msg-actions');
  if (!actions || actions.dataset.ready === '1') return;
  actions.dataset.ready = '1';
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.textContent = '复制';
  copy.addEventListener('click', () => copyText(text));
  actions.appendChild(copy);

  if ((element._mapCommands || []).length) {
    const map = document.createElement('button');
    map.type = 'button';
    map.textContent = '地图';
    map.addEventListener('click', () => {
      openEarthWindow();
      replayMapCommands(element._mapCommands || []);
    });
    actions.appendChild(map);
  }

  if (finished && state.lastAssistantEl === element) {
    const regenerate = document.createElement('button');
    regenerate.type = 'button';
    regenerate.textContent = '重新生成';
    regenerate.addEventListener('click', regenerateLast);
    actions.appendChild(regenerate);
  }
}

async function regenerateLast() {
  if (state.streaming || !state.lastUserSeq) return;
  if (!state.lastUserText && !(state.lastUserFileIds || []).length) return;
  clearMap();
  const seq = state.lastUserSeq;
  const text = state.lastUserText;
  // 删除最后一条用户 + 助手消息对应的 DOM
  const userMessages = [...document.querySelectorAll('.msg.user')];
  const lastUser = userMessages[userMessages.length - 1];
  if (lastUser) lastUser.remove();
  if (state.lastAssistantEl) state.lastAssistantEl.remove();
  state.lastAssistantEl = null;
  state.lastUserSeq = seq - 1 > 0 ? seq - 1 : 0;
  await send(text, Math.max(0, seq - 1), state.lastUserFileIds || []);
}

// ===== 附件上传 =====

function initDropZone() {
  const composer = document.querySelector('.composer');
  if (!composer) return;
  let depth = 0;
  composer.addEventListener('dragenter', (event) => {
    event.preventDefault();
    depth += 1;
    composer.classList.add('drag-over');
  });
  composer.addEventListener('dragover', (event) => {
    event.preventDefault();
  });
  composer.addEventListener('dragleave', (event) => {
    event.preventDefault();
    depth = Math.max(0, depth - 1);
    if (depth === 0) composer.classList.remove('drag-over');
  });
  composer.addEventListener('drop', (event) => {
    event.preventDefault();
    depth = 0;
    composer.classList.remove('drag-over');
    [...(event.dataTransfer?.files || [])].forEach(addLocalFile);
  });
}

function addLocalFile(file) {
  if (!file) return;
  if (state.streaming) {
    toast('正在生成中，暂不能上传', false);
    return;
  }
  if (pendingFiles.size >= MAX_FILES) {
    toast(`最多上传 ${MAX_FILES} 个文件`, false);
    return;
  }
  const ext = extensionOf(file.name);
  if (!ALLOWED_EXTENSIONS.has(ext)) {
    toast(`不支持的文件格式：${ext || '未知'}`, false);
    return;
  }
  if (file.size > MAX_FILE_SIZE) {
    toast('单个文件不能超过 20MB', false);
    return;
  }

  const tempId = `tmp_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  pendingFiles.set(tempId, { id: tempId, name: file.name, size: file.size, status: 'uploading' });
  renderAttachments();

  const form = new FormData();
  form.append('file', file);
  uploadFile('/api/chat/files', form)
    .then((data) => {
      if (!pendingFiles.has(tempId)) {
        // 用户在等待期间移除了该文件，立即清理服务端副本
        del(`/api/chat/files/${encodeURIComponent(data.fileId)}`).catch(() => {});
        return;
      }
      pendingFiles.delete(tempId);
      pendingFiles.set(data.fileId, {
        id: data.fileId,
        name: data.name,
        size: data.size,
        chars: data.chars,
        status: 'ready',
      });
      renderAttachments();
    })
    .catch((error) => {
      pendingFiles.delete(tempId);
      renderAttachments();
      toast(error.message || '上传失败', false);
    });
}

async function removeAttachment(id) {
  const item = pendingFiles.get(id);
  pendingFiles.delete(id);
  renderAttachments();
  if (item && item.status === 'ready') {
    try { await del(`/api/chat/files/${encodeURIComponent(id)}`); } catch (_) { /* ignore */ }
  }
}

function renderAttachments() {
  const box = $('attachments');
  if (!box) return;
  box.innerHTML = [...pendingFiles.values()].map((file) => `
    <span class="attachment-chip${file.status === 'uploading' ? ' uploading' : ''}"
          title="${escapeHtml(file.name)}">
      <span class="chip-name">${escapeHtml(file.name)}</span>
      <span class="chip-size">${file.status === 'uploading' ? '上传中…' : formatSize(file.size)}</span>
      <button type="button" class="chip-remove" data-remove="${escapeHtml(file.id)}" title="移除">×</button>
    </span>`).join('');
  box.querySelectorAll('[data-remove]').forEach((button) => {
    button.addEventListener('click', () => removeAttachment(button.dataset.remove));
  });
}

function clearAttachments() {
  pendingFiles.clear();
  renderAttachments();
}

function fileChipHtml(file) {
  return `<span class="file-chip" title="${escapeHtml(file.name || '')}">
    <span class="chip-name">📄 ${escapeHtml(file.name || '文件')}</span>
  </span>`;
}

/** 在助手消息下方追加一个“生成文件”下载卡片（同 fileId 只加一次）。 */
function appendOutput(assistant, file) {
  if (!assistant || !file || !file.fileId) return;
  let box = assistant.querySelector('.msg-outputs');
  if (!box) {
    box = document.createElement('div');
    box.className = 'msg-outputs';
    const body = assistant.querySelector('.msg-body');
    if (body) body.insertAdjacentElement('afterend', box);
    else assistant.appendChild(box);
  }
  if (box.querySelector(`[data-file-id="${file.fileId}"]`)) return;
  box.insertAdjacentHTML('beforeend', outputChipHtml(file));
  scrollToBottom();
}

function outputChipHtml(file) {
  const name = file.name || '导出文件';
  const size = file.size ? formatSize(file.size) : '';
  const href = file.downloadUrl || `/api/chat/files/${encodeURIComponent(file.fileId)}`;
  return `<a class="output-chip" data-file-id="${escapeHtml(file.fileId)}"
    href="${escapeHtml(href)}" download="${escapeHtml(name)}"
    title="点击下载：${escapeHtml(name)}">
    <span class="chip-icon">${fileIcon(name)}</span>
    <span class="chip-name">${escapeHtml(name)}</span>
    ${size ? `<span class="chip-size">${size}</span>` : ''}
    <span class="chip-dl">↓</span>
  </a>`;
}

function fileIcon(name) {
  const ext = extensionOf(name);
  if (ext === '.docx') return '📝';
  if (ext === '.xlsx') return '📊';
  if (ext === '.csv') return '📈';
  if (ext === '.json') return '🧾';
  if (ext === '.md' || ext === '.markdown') return '📄';
  return '📄';
}

function extensionOf(name) {
  const idx = (name || '').lastIndexOf('.');
  return idx >= 0 ? name.slice(idx).toLowerCase() : '';
}

function formatSize(bytes) {
  if (!bytes) return '0 KB';
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text || '');
    toast('已复制', true);
  } catch (_) {
    toast('复制失败', false);
  }
}

function clearEmptyState() {
  const empty = $('emptyState');
  if (empty) empty.remove();
}

function renderEmptyState() {
  const container = $('messages');
  if (container.querySelector('.msg')) return;
  container.innerHTML = `
    <div class="empty-state" id="emptyState">
      <div class="empty-logo">AgentChat</div>
      <div class="empty-desc">基于 AgentScope 的对话助手，支持工具、MCP 与技能</div>
      <div class="suggestions">
        <button type="button" data-prompt="用一句话解释 MCP 协议是什么">用一句话解释 MCP 协议</button>
        <button type="button" data-prompt="帮我写一个 Redis 分布式锁，并解释关键点">写一个 Redis 分布式锁</button>
        <button type="button" data-prompt="总结一下这个项目的整体架构">总结项目架构</button>
        <button type="button" data-prompt="今天北京天气怎么样？">查一下北京天气</button>
      </div>
    </div>`;
  container.querySelectorAll('.suggestions button').forEach((button) => {
    button.addEventListener('click', () => {
      const prompt = button.dataset.prompt;
      $('input').value = prompt;
      autosize();
      send();
    });
  });
}

function scrollToBottom(force) {
  const container = $('messages');
  if (!container) return;
  const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 160;
  if (force || nearBottom) {
    container.scrollTop = container.scrollHeight;
  }
}

function toast(message, ok) {
  const element = $('toast');
  if (!element) return;
  element.textContent = (ok ? '✓ ' : '✕ ') + message;
  element.className = 'toast show' + (ok ? ' ok' : ' error');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.className = 'toast'; }, 2600);
}
