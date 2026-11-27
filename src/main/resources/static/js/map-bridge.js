import { on, emit } from './bus.js';
import { userId } from './auth.js';

/**
 * 问答页与独立数字地球窗口之间的 BroadcastChannel 桥。
 *
 * 地球窗口是独立页面（/earth/index.html），不是 iframe：
 * - 同一 origin、同一浏览器 profile 下用 BroadcastChannel 同步。
 * - 地球晚打开时通过 hello/sync 握手补齐当前地图指令。
 * - 没有地球窗口时只保存状态，等窗口打开后再同步。
 */

const CHANNEL_NAME = 'agentchat-earth-v1';
const CHAT_ID_KEY_PREFIX = 'agentchat.earthChatId:';
const EARTH_URL = '/earth/index.html';
// 地球静态资源更新后改这个值：已打开的地球窗口会在下次点击时自动重载到新构建。
const EARTH_BUILD = 'plot-v1a';

/** 地球会话按用户隔离：同一浏览器切换账号不会复用上一个用户的地球会话。 */
function chatIdKey() {
  return CHAT_ID_KEY_PREFIX + userId();
}

function earthWindowName() {
  return 'agentchat-earth-' + userId();
}

let channel = null;
let chatId = null;
let earthWindow = null;
let conversationId = null;
let lastCommands = [];
let currentTitle = '数字地球';
let currentCount = 0;
let selectHandler = null;

function randomId() {
  try {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
  } catch (_) {
    /* ignore */
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function ensureChatId() {
  if (chatId) return chatId;
  try {
    chatId = sessionStorage.getItem(chatIdKey());
    if (!chatId) {
      chatId = 'c_' + randomId();
      sessionStorage.setItem(chatIdKey(), chatId);
    }
  } catch (_) {
    chatId = 'c_' + randomId();
  }
  return chatId;
}

function supported() {
  return typeof window !== 'undefined' && typeof window.BroadcastChannel === 'function';
}

function send(type, payload) {
  if (!channel) return false;
  try {
    channel.postMessage({
      channel: CHANNEL_NAME,
      from: 'chat',
      chatId,
      type,
      payload: payload === undefined ? null : payload,
      ts: Date.now(),
    });
    return true;
  } catch (error) {
    console.warn('[earth] 发送跨窗口消息失败：', error);
    return false;
  }
}

function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

function syncPayload() {
  return {
    theme: currentTheme(),
    conversationId,
    commands: lastCommands.slice(),
    title: currentTitle,
    count: currentCount,
  };
}

function requestSync() {
  send('sync', syncPayload());
}

function updateButton() {
  const button = document.getElementById('btnMap');
  if (!button) return;
  const detail = currentCount > 0 ? ` · ${currentCount} 个标注` : '';
  button.title = `打开数字地球窗口${detail}`;
}

function notify(message, ok) {
  emit('ui:toast', { message, ok: ok !== false });
}

function onMessage(event) {
  const data = event && event.data;
  if (!data || data.channel !== CHANNEL_NAME || data.from !== 'earth') return;
  if (data.chatId && chatId && data.chatId !== chatId) return;

  const payload = data.payload && typeof data.payload === 'object' ? data.payload : {};

  switch (data.type) {
    case 'hello':
    case 'ready':
      requestSync();
      break;
    case 'rendered': {
      if (payload.title) currentTitle = String(payload.title);
      if (Number.isFinite(payload.count)) currentCount = payload.count;
      updateButton();
      break;
    }
    case 'select': {
      const feature = payload.feature || payload;
      if (feature && selectHandler) selectHandler(feature);
      break;
    }
    case 'mode':
      break;
    case 'bye':
      updateButton();
      break;
    case 'error':
      notify(payload.message || '数字地球加载失败，请检查离线资源。', false);
      break;
    default:
      break;
  }
}

function openChannel() {
  if (!supported()) {
    console.warn('[earth] 当前浏览器不支持 BroadcastChannel，数字地球将无法与问答页同步。');
    return;
  }
  try {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.addEventListener('message', onMessage);
    // 主动打招呼：地球已经打开时，它会回 hello，随后收到 sync。
    send('hello');
  } catch (error) {
    console.warn('[earth] 初始化跨窗口通道失败：', error);
    channel = null;
  }
}

export function initEarthBridge() {
  ensureChatId();
  openChannel();

  document.getElementById('btnMap')?.addEventListener('click', () => {
    openEarthWindow();
  });

  on('theme:changed', (theme) => send('theme', { theme }));

  window.addEventListener('beforeunload', () => {
    send('bye');
    try { channel?.close(); } catch (_) { /* ignore */ }
  });

  updateButton();
}

/**
 * 打开独立数字地球窗口；重复点击时聚焦已有窗口。
 * @returns {boolean} 是否成功打开/聚焦
 */
export function openEarthWindow() {
  ensureChatId();
  const url = new URL(EARTH_URL, window.location.origin);
  url.searchParams.set('chatId', chatId);
  url.searchParams.set('v', EARTH_BUILD);

  let opened = null;
  try {
    // 先按窗口名拿到已有窗口（没有就开一个空白窗口），再决定是否导航。
    // 直接 window.open(url, name) 在部分 Chromium 上会只聚焦旧窗口、不加载新 URL。
    opened = window.open(
      '',
      earthWindowName(),
      'popup=yes,width=1000,height=1000,left=0,top=0,resizable=yes,scrollbars=no',
    );
  } catch (error) {
    console.warn('[earth] 打开数字地球窗口失败：', error);
  }

  if (!opened) {
    notify('浏览器阻止了数字地球窗口，请手动打开 /earth/index.html', false);
    return false;
  }

  earthWindow = opened;
  let sameBuild = false;
  try {
    const current = new URL(opened.location.href);
    sameBuild = current.pathname === '/earth/index.html' &&
      current.searchParams.get('v') === EARTH_BUILD &&
      current.searchParams.get('chatId') === chatId;
  } catch (_) {
    sameBuild = false;
  }

  if (!sameBuild) {
    try {
      opened.location.replace(url.toString());
    } catch (error) {
      console.warn('[earth] 加载数字地球窗口失败：', error);
    }
  }

  try { earthWindow.focus(); } catch (_) { /* ignore */ }
  return true;
}

/** 保存并广播一条地图指令。 */
export function applyMapCommand(command) {
  if (!command || typeof command !== 'object') return;
  const type = String(command.type || '').toLowerCase();

  if (type === 'replace') {
    lastCommands = [command];
    currentTitle = command.title ? String(command.title) : '地图标注';
    currentCount = Array.isArray(command.features) ? command.features.length : 0;
  } else if (type === 'view') {
    lastCommands.push(command);
    if (command.title) currentTitle = String(command.title);
  } else if (type === 'clear') {
    lastCommands = [];
    currentTitle = '数字地球';
    currentCount = 0;
  } else {
    lastCommands.push(command);
  }

  send('command', { command });
  updateButton();
}

/** 清空当前标注；地球窗口存在时同时广播。 */
export function clearMap() {
  lastCommands = [];
  currentTitle = '数字地球';
  currentCount = 0;
  send('clear');
  updateButton();
}

/** 历史回放：先清空，再按顺序重放。 */
export function replayMapCommands(commands) {
  if (!Array.isArray(commands) || !commands.length) {
    clearMap();
    return;
  }

  lastCommands = commands.slice();
  currentTitle = '数字地球';
  currentCount = 0;

  for (const command of commands) {
    if (!command || typeof command !== 'object') continue;
    const type = String(command.type || '').toLowerCase();
    if (type === 'replace' || (type === 'view' && command.title)) {
      currentTitle = command.title ? String(command.title) : '地图标注';
    }
    if (type === 'replace') {
      currentCount = Array.isArray(command.features) ? command.features.length : 0;
    } else if (type === 'clear') {
      currentTitle = '数字地球';
      currentCount = 0;
    }
  }

  send('clear');
  for (const command of commands) {
    if (command && typeof command === 'object') send('command', { command });
  }
  updateButton();
}

export function setMapConversation(id) {
  conversationId = id || null;
  send('conversation', { conversationId });
}

export function onMapSelect(handler) {
  selectHandler = typeof handler === 'function' ? handler : null;
}

/**
 * 切换/退出用户时重置地球会话：清空指令、关闭旧窗口、丢弃旧 chatId。
 * 新用户下次点击"数字地球"会拿到全新的 chatId，历史标注不会串到另一个账号。
 */
export function resetEarthSession() {
  try {
    send('clear');
  } catch (_) {
    /* ignore */
  }
  lastCommands = [];
  conversationId = null;
  currentTitle = '数字地球';
  currentCount = 0;
  chatId = null;
  try {
    sessionStorage.removeItem(chatIdKey());
  } catch (_) {
    /* ignore */
  }
  try {
    earthWindow?.close();
  } catch (_) {
    /* ignore */
  }
  earthWindow = null;
  updateButton();
}
