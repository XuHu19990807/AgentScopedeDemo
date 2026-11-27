// 认证态：大系统 SSO、本地账号登录、当前用户缓存与事件。
// 设计目标：页面加载时先用 URL / postMessage / Cookie 里的凭据换取会话，
// 再渲染界面；所有业务请求只依赖 Cookie 或内存中的 sessionToken。
import { emit } from './bus.js';

const DEFAULT_STATE = {
  loaded: false,
  authenticated: false,
  userId: 'anonymous',
  loginName: '',
  displayName: '',
  roles: [],
  domains: [],
  admin: false,
  source: 'anonymous',
  sessionToken: null,
  loginRequired: false,
  ssoEnabled: false,
  embedOrigins: [],
};

let state = { ...DEFAULT_STATE };
const listeners = new Set();

export function authState() {
  return state;
}

export function onAuthChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function sessionToken() {
  return state.sessionToken || null;
}

export function userId() {
  return state.userId || 'anonymous';
}

export function isAuthenticated() {
  return !!state.authenticated;
}

export function isAdmin() {
  return !!state.admin;
}

export function loginRequired() {
  return !!state.loginRequired;
}

export function displayName() {
  return state.displayName || state.loginName || (state.authenticated ? state.userId : '未登录');
}

function apply(data) {
  state = {
    ...DEFAULT_STATE,
    ...state,
    ...data,
    loaded: true,
    userId: data.userId || data.user_id || 'anonymous',
    roles: data.roles || [],
    domains: data.domains || [],
    admin: !!data.admin,
    authenticated: !!data.authenticated,
    loginRequired: !!data.loginRequired,
    sessionToken: data.sessionToken || null,
  };
  listeners.forEach((listener) => {
    try {
      listener(state);
    } catch (error) {
      console.warn('auth listener failed', error);
    }
  });
  emit('auth:changed', state);
  return state;
}

export function loginPath(extra = '') {
  const redirect = location.pathname + location.search + location.hash + (extra || '');
  return `/login?redirect=${encodeURIComponent(redirect)}`;
}

/** 从 URL 取大系统带过来的 SSO 票据（?sso= / ?ssoToken= / #sso=）。 */
function tokenFromUrl() {
  const params = new URLSearchParams(location.search || '');
  const direct = params.get('sso') || params.get('ssoToken') || params.get('token');
  if (direct) return direct.trim();
  const hash = (location.hash || '').replace(/^#/, '');
  if (hash) {
    const hashParams = new URLSearchParams(hash);
    const fromHash = hashParams.get('sso') || hashParams.get('ssoToken');
    if (fromHash) return fromHash.trim();
  }
  return '';
}

function stripTokenFromUrl() {
  try {
    const url = new URL(location.href);
    ['sso', 'ssoToken', 'token'].forEach((key) => url.searchParams.delete(key));
    if (/#/.test(location.href) && /(?:sso|ssoToken)=/.test(location.hash)) {
      const hashParams = new URLSearchParams(location.hash.replace(/^#/, ''));
      hashParams.delete('sso');
      hashParams.delete('ssoToken');
      const rest = hashParams.toString();
      url.hash = rest ? `#${rest}` : '';
    }
    history.replaceState(null, '', url.pathname + url.search + url.hash);
  } catch (_) {
    /* ignore */
  }
}

async function rawJson(path, init = {}) {
  const headers = { ...(init.headers || {}) };
  const token = state.sessionToken;
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  let data = null;
  try {
    data = await res.json();
  } catch (_) {
    data = null;
  }
  return { ok: res.ok, status: res.status, data };
}

/** 用 SSO 票据换会话（服务端验签，前端不解析票据内容）。 */
export async function exchangeSso(token) {
  if (!token) return false;
  const { ok, data } = await rawJson('/api/auth/sso/exchange', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });
  if (!ok || !data) return false;
  const me = await fetchMe();
  return !!(me && me.authenticated);
}

export async function fetchMe() {
  const { ok, status, data } = await rawJson('/api/auth/me', { method: 'GET' });
  if (status === 401) return null;
  if (!ok || !data) return null;
  return apply(data);
}

/**
 * 初始化认证态。返回值即当前认证态。
 * 解析顺序：URL 票据 → postMessage 票据（iframe 由宿主推送）→ 已有会话 Cookie。
 */
export async function initAuth({ redirect = true } = {}) {
  let me = null;
  const urlToken = tokenFromUrl();
  if (urlToken) {
    const ok = await exchangeSso(urlToken);
    stripTokenFromUrl();
    if (!ok) console.warn('SSO 票据校验失败，将回退到已有会话或登录页');
  }

  me = await fetchMe();
  if (!me) {
    // 网络/服务异常：保持匿名，避免把用户弹到登录页
    apply({ ...DEFAULT_STATE, loaded: true });
    startEmbedBridge();
    return state;
  }
  if (!me.authenticated && me.loginRequired && redirect && !isLoginPage()) {
    location.replace(loginPath());
    return me;
  }
  startEmbedBridge();
  return me;
}

function isLoginPage() {
  return /\/login(\.html)?$/.test(location.pathname);
}

/** iframe 嵌入：向宿主公告就绪，并支持宿主推送 SSO 票据或登出。 */
function startEmbedBridge() {
  const embedded = window.self !== window.top;
  window.addEventListener('message', async (event) => {
    // 只接受宿主窗口（父页面）的消息，避免同页其它窗口伪造指令
    if (embedded && event.source !== window.parent) return;
    const data = event.data;
    if (!data || typeof data !== 'object') return;
    const type = data.type || data.event;
    if (!type) return;
    try {
      if (type === 'agentchat:sso' && typeof data.token === 'string' && data.token.trim()) {
        const ok = await exchangeSso(data.token.trim());
        if (ok) location.reload();
      } else if (type === 'agentchat:logout') {
        await logout({ redirect: false });
        if (embedded) return;
        location.replace(loginPath());
      } else if (type === 'agentchat:ping') {
        postToParent({ type: 'agentchat:pong' });
      }
    } catch (error) {
      console.warn('处理宿主消息失败', error);
    }
  });
  if (embedded) {
    postToParent({ type: 'agentchat:ready' });
    postToParent({ type: 'agentchat:request-sso' });
  }
}

function postToParent(payload) {
  try {
    window.parent.postMessage(payload, '*');
  } catch (_) {
    /* ignore */
  }
}

export async function login(username, password) {
  const { ok, status, data } = await rawJson('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!ok) {
    const message = (data && (data.error || data.message)) || `登录失败（HTTP ${status}）`;
    const error = new Error(message);
    error.status = status;
    throw error;
  }
  const me = await fetchMe();
  return me || data;
}

export async function logout({ redirect = true } = {}) {
  try {
    await rawJson('/api/auth/logout', { method: 'POST' });
  } catch (_) {
    /* ignore */
  }
  state = { ...DEFAULT_STATE, loaded: true };
  listeners.forEach((listener) => listener(state));
  emit('auth:changed', state);
  if (redirect) location.replace(loginPath());
}

/** 业务请求收到 401 时调用：广播事件，由 app.js 统一跳登录页。 */
export function notifyExpired() {
  emit('auth:expired');
}
