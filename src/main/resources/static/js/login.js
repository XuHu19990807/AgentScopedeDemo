// 登录页：本地账号登录；若已登录或 URL 携带 SSO 票据，则直接回跳。
import { fetchMe, login, exchangeSso } from './auth.js';

const params = new URLSearchParams(location.search);
const redirectTarget = safeRedirect(params.get('redirect'));

function safeRedirect(value) {
  if (!value) return '/';
  // 只允许站内绝对路径，避免开放重定向
  if (!value.startsWith('/') || value.startsWith('//')) return '/';
  return value;
}

function setNotice(text) {
  const el = document.getElementById('loginNotice');
  if (!el) return;
  el.textContent = text;
  el.hidden = !text;
}

function setError(text) {
  const el = document.getElementById('loginError');
  if (!el) return;
  el.textContent = text;
  el.hidden = !text;
}

async function bootstrap() {
  const back = document.getElementById('backLink');
  if (back) back.href = redirectTarget;

  // 已登录：直接回跳（避免重复登录）
  const me = await fetchMe();
  if (me && me.authenticated) {
    location.replace(redirectTarget);
    return;
  }
  if (me && me.loginRequired === false) {
    // 服务端允许匿名：仍展示登录页，但提示可直接使用
    setNotice('当前服务允许匿名使用，登录后会话与记忆会绑定到你的账号。');
  }
  if (me && me.ssoEnabled) {
    const hint = document.getElementById('ssoHint');
    if (hint) hint.hidden = false;
  }
  const ssoToken = params.get('sso') || params.get('ssoToken') || params.get('token');
  if (ssoToken) {
    setNotice('正在通过大系统单点登录…');
    const ok = await exchangeSso(ssoToken);
    if (ok) {
      location.replace(redirectTarget);
      return;
    }
    setError('单点登录票据无效或已过期，请重新从大系统进入，或使用账号密码登录。');
  }
}

bootstrap().catch(() => {
  setError('初始化失败，请刷新重试。');
});

document.getElementById('loginForm')?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submit = document.getElementById('loginSubmit');
  const username = document.getElementById('loginName')?.value?.trim() || '';
  const password = document.getElementById('loginPassword')?.value || '';
  setError('');
  if (!username || !password) {
    setError('请输入账号与密码');
    return;
  }
  if (submit) submit.disabled = true;
  try {
    await login(username, password);
    sessionStorage.removeItem('agentchat.loginFail');
    location.replace(redirectTarget);
  } catch (error) {
    setError(error.message || '登录失败');
  } finally {
    if (submit) submit.disabled = false;
  }
});
