import { initChat } from './chat.js';
import { initSessions } from './sessions.js';
import { initConsole, openConsole } from './console.js';
import { initEarthBridge, resetEarthSession } from './map-bridge.js';
import { get } from './api.js';
import { on, emit } from './bus.js';
import { initAuth, authState, onAuthChange, logout, loginPath } from './auth.js';
import { getItem, setItem, resetUserSurface } from './ui-store.js';

const THEME_KEY = 'theme';

function initTheme() {
  const saved = getItem(THEME_KEY, 'light');
  if (saved === 'dark') document.documentElement.dataset.theme = 'dark';
  const button = document.getElementById('btnTheme');
  updateThemeIcon();
  emit('theme:changed', document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
  button?.addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme === 'dark';
    document.documentElement.dataset.theme = dark ? 'light' : 'dark';
    setItem(THEME_KEY, dark ? 'light' : 'dark');
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

/* ---------------- 当前用户入口 ---------------- */

function initUserChip() {
  const chip = document.getElementById('userChip');
  const button = document.getElementById('userMenuBtn');
  const menu = document.getElementById('userMenu');
  if (!chip || !button || !menu) return;

  const render = () => {
    const state = authState();
    const name = document.getElementById('userName');
    const avatar = document.getElementById('userAvatar');
    const meta = document.getElementById('userMeta');
    const label = state.authenticated
      ? (state.displayName || state.loginName || state.userId)
      : '未登录';
    if (name) name.textContent = label;
    if (avatar) avatar.textContent = (label || 'U').slice(0, 1).toUpperCase();
    chip.title = state.authenticated
      ? `当前用户：${label}（${state.source === 'sso' ? '大系统' : '本地账号'}）`
      : '当前为匿名访问，登录后可保存会话与偏好';
    chip.classList.toggle('admin', !!state.admin);
    if (meta) {
      meta.innerHTML = '';
      const lines = [];
      if (state.authenticated) lines.push(`账号：${state.loginName || state.userId}`);
      if (state.roles && state.roles.length) lines.push(`角色：${state.roles.join('、')}`);
      if (state.domains && state.domains.length) lines.push(`知识域：${state.domains.join('、')}`);
      if (!state.authenticated) lines.push('未登录：会话与偏好仅保存在当前浏览器');
      lines.forEach((text) => {
        const div = document.createElement('div');
        div.textContent = text;
        meta.appendChild(div);
      });
    }
  };

  const close = () => { menu.hidden = true; };
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    menu.hidden = !menu.hidden;
  });
  document.addEventListener('click', (event) => {
    if (!menu.hidden && !menu.contains(event.target) && event.target !== button) close();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') close();
  });

  menu.querySelectorAll('[data-action]').forEach((item) => {
    item.addEventListener('click', async () => {
      const action = item.dataset.action;
      close();
      if (action === 'profile') openConsole('profile');
      else if (action === 'memory') openConsole('memory');
      else if (action === 'admin') openConsole(authState().admin ? 'users' : 'overview');
      else if (action === 'login') location.href = loginPath();
      else if (action === 'logout') {
        resetUserSurface();
        resetEarthSession();
        await logout({ redirect: false });
        location.reload();
      }
    });
  });

  render();
  onAuthChange(render);
}

/* ---------------- 健康状态 ---------------- */

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
      `DM: ${health.dm || 'unknown'}`,
      `用户体系: ${health.identity || 'unknown'}`,
    ].join(' · ');
    badge.textContent = up ? '在线' : '降级';
  } catch (_) {
    badge.className = 'health-badge warn';
    badge.textContent = '离线';
    badge.title = '无法连接服务';
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  try {
    await initAuth();
  } catch (error) {
    console.warn('认证初始化失败，按匿名模式继续', error);
  }
  initEarthBridge();
  initTheme();
  initChat();
  initSessions();
  initConsole();
  initMobile();
  initUserChip();
  refreshHealth();
  setInterval(refreshHealth, 30000);

  // 会话过期：清掉界面残留并跳登录页（登录页会带上返回地址）
  on('auth:expired', () => {
    resetUserSurface();
    resetEarthSession();
    if (document.getElementById('userChip')) {
      location.replace(loginPath());
    }
  });

  // 管理员改了自己的角色/域：重新拉一次 /api/auth/me，让菜单与权限即时刷新
  on('auth:refresh', async () => {
    const { fetchMe } = await import('./auth.js');
    await fetchMe();
  });
});
