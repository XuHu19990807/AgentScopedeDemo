// 用户隔离的浏览器存储：所有 UI 偏好都挂到当前用户 id 下，
// 避免同一浏览器切换账号后把上一个用户的主题/模型/地球状态带过去。
import { userId } from './auth.js';

const PREFIX = 'agentchat.u:';
const LEGACY_KEYS = {
  theme: 'agentchat.theme',
  model: 'agentchat.model',
  orchestrateParallel: 'agentchat.orchestrateParallel',
};

/** 旧版本未隔离的键：仅在匿名用户首次访问时迁移，避免把上一用户的偏好带给真实账号。 */
function migrateLegacy(name) {
  const legacyKey = LEGACY_KEYS[name];
  if (!legacyKey) return null;
  if (!userId().startsWith('u_')) return null;
  const value = localStorage.getItem(legacyKey);
  if (value === null) return null;
  localStorage.setItem(scopedKey(name), value);
  localStorage.removeItem(legacyKey);
  return value;
}

export function scopedKey(name) {
  return `${PREFIX}${userId()}:${name}`;
}

export function getRawItem(name) {
  const key = scopedKey(name);
  let value = localStorage.getItem(key);
  if (value === null) {
    const legacy = migrateLegacy(name);
    if (legacy !== null) value = legacy;
  }
  return value;
}

export function getItem(name, fallback = null) {
  const value = getRawItem(name);
  return value === null ? fallback : value;
}

export function setItem(name, value) {
  if (value === null || value === undefined) {
    removeItem(name);
    return;
  }
  localStorage.setItem(scopedKey(name), String(value));
}

export function removeItem(name) {
  localStorage.removeItem(scopedKey(name));
}

/** 清空当前用户的全部 UI 偏好（退出登录 / 切换账号时调用）。 */
export function clearScope(uid = userId()) {
  const prefix = `${PREFIX}${uid}:`;
  const keys = [];
  for (let i = 0; i < localStorage.length; i += 1) {
    const key = localStorage.key(i);
    if (key && key.startsWith(prefix)) keys.push(key);
  }
  keys.forEach((key) => localStorage.removeItem(key));
}

/** 退出/切换账号：清掉当前页面残留的对话 DOM、附件选择与地球会话 id。 */
export function resetUserSurface() {
  clearScope();
  try {
    sessionStorage.removeItem('agentchat.earthChatId');
  } catch (_) {
    /* ignore */
  }
  const messages = document.getElementById('messages');
  if (messages) messages.innerHTML = '';
  const attachments = document.getElementById('attachments');
  if (attachments) attachments.innerHTML = '';
  const input = document.getElementById('input');
  if (input) input.value = '';
  const title = document.getElementById('convTitle');
  if (title) title.textContent = '新对话';
}
