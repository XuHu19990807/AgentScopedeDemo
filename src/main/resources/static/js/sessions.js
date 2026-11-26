import { get, del, patch } from './api.js';
import { emit, on } from './bus.js';
import { escapeHtml } from './markdown.js';

let conversations = [];
let activeId = null;
let query = '';

export function initSessions() {
  document.getElementById('btnNew').addEventListener('click', () => {
    activeId = null;
    render();
    emit('conversation:new');
  });

  document.getElementById('convSearch').addEventListener('input', (event) => {
    query = event.target.value.trim().toLowerCase();
    render();
  });

  document.getElementById('convList').addEventListener('click', async (event) => {
    const item = event.target.closest('.conv-item');
    if (!item) return;
    const id = item.dataset.id;
    if (event.target.closest('.conv-del')) {
      event.stopPropagation();
      if (!confirm('确认删除此对话？')) return;
      try {
        await del(`/api/conversations/${id}`);
        if (activeId === id) {
          activeId = null;
          emit('conversation:new');
        }
        await load();
      } catch (error) {
        toast(error.message || '删除失败', false);
      }
      return;
    }
    activeId = id;
    render();
    emit('conversation:open', id);
  });

  document.getElementById('convList').addEventListener('dblclick', async (event) => {
    const item = event.target.closest('.conv-item');
    if (!item) return;
    const id = item.dataset.id;
    const current = item.querySelector('.conv-title').textContent;
    const title = prompt('重命名对话', current === '新对话' ? '' : current);
    if (title === null) return;
    const trimmed = title.trim();
    if (!trimmed) return;
    try {
      await patch(`/api/conversations/${id}`, { title: trimmed });
      await load();
    } catch (error) {
      toast(error.message || '重命名失败', false);
    }
  });

  on('conversation:changed', async (id) => {
    activeId = id;
    await load();
  });
  on('conversation:active', (id) => {
    activeId = id;
    render();
  });
  on('chat:finished', () => { load(); });

  load();
}

export async function load() {
  try {
    conversations = await get('/api/conversations');
    render();
  } catch (_) {
    // 启动早期 Redis 可能未就绪，静默重试
  }
}

function render() {
  const list = document.getElementById('convList');
  if (!list) return;

  const filtered = conversations.filter((item) => {
    if (!query) return true;
    return (item.title || '新对话').toLowerCase().includes(query);
  });

  if (!filtered.length) {
    list.innerHTML = `<div class="conv-empty">${query ? '没有匹配的对话' : '暂无对话，开始提问吧'}</div>`;
    return;
  }

  const groups = new Map();
  for (const conversation of filtered) {
    const label = groupLabel(conversation.updatedAt);
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(conversation);
  }

  const parts = [];
  for (const [label, items] of groups) {
    parts.push(`<div class="conv-group-label">${label}</div>`);
    for (const item of items) {
      const title = item.title && item.title.trim() ? item.title : '新对话';
      const active = item.id === activeId ? ' active' : '';
      parts.push(`
        <div class="conv-item${active}" data-id="${escapeHtml(item.id)}" title="${escapeHtml(title)}">
          <span class="conv-title">${escapeHtml(title)}</span>
          <button type="button" class="conv-del" title="删除">×</button>
        </div>`);
    }
  }
  list.innerHTML = parts.join('');
}

function groupLabel(timestamp) {
  if (!timestamp) return '更早';
  const date = new Date(timestamp);
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (date.getTime() >= startOfToday) return '今天';
  if (date.getTime() >= startOfToday - 86400000) return '昨天';
  if (date.getTime() >= startOfToday - 7 * 86400000) return '最近 7 天';
  return '更早';
}

function toast(message, ok) {
  const element = document.getElementById('toast');
  if (!element) return;
  element.textContent = (ok ? '✓ ' : '✕ ') + message;
  element.className = 'toast show' + (ok ? ' ok' : ' error');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.className = 'toast'; }, 2600);
}
