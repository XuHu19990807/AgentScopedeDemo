import { initChat } from './chat.js';
import { initSessions } from './sessions.js';
import { initConsole } from './console.js';
import { initEarthBridge } from './map-bridge.js';
import { get } from './api.js';
import { on, emit } from './bus.js';

function initTheme() {
  const saved = localStorage.getItem('agentchat.theme');
  if (saved === 'dark') document.documentElement.dataset.theme = 'dark';
  const button = document.getElementById('btnTheme');
  updateThemeIcon();
  emit('theme:changed', document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
  button?.addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme === 'dark';
    document.documentElement.dataset.theme = dark ? 'light' : 'dark';
    localStorage.setItem('agentchat.theme', dark ? 'light' : 'dark');
    updateThemeIcon();
    emit('theme:changed', dark ? 'light' : 'dark');
  });
}

function updateThemeIcon() {
  const button = document.getElementById('btnTheme');
  if (!button) return;
  button.textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';
  button.title = document.documentElement.dataset.theme === 'dark' ? '切换到浅色' : '切换到深色';
}

function initMobile() {
  const sidebar = document.getElementById('sidebar');
  const mask = document.getElementById('sidebarMask');
  const open = () => { sidebar?.classList.add('open'); mask?.classList.add('show'); };
  const close = () => { sidebar?.classList.remove('open'); mask?.classList.remove('show'); };
  document.getElementById('btnMenu')?.addEventListener('click', open);
  mask?.addEventListener('click', close);
  on('conversation:open', close);
  on('conversation:new', close);
}

async function refreshHealth() {
  const badge = document.getElementById('healthBadge');
  if (!badge) return;
  try {
    const health = await get('/api/health');
    const up = health.redis === 'up';
    badge.className = 'health-badge ' + (up ? 'ok' : 'warn');
    badge.title = [
      `Redis: ${health.redis}`,
      `检索: ${health.redisSearch}`,
      `向量库: ${health.vectorStore || 'milvus'} / ${health.milvus || 'unknown'}`,
      `模型: ${health.model}`,
      `Nacos: ${health.nacos}`,
      `MCP: ${health.mcpServers}`,
    ].join(' · ');
    badge.textContent = up ? '在线' : '降级';
  } catch (_) {
    badge.className = 'health-badge warn';
    badge.textContent = '离线';
    badge.title = '无法连接服务';
  }
}

document.addEventListener('DOMContentLoaded', () => {
  initEarthBridge();
  initTheme();
  initChat();
  initSessions();
  initConsole();
  initMobile();
  refreshHealth();
  setInterval(refreshHealth, 30000);
});
