/**
 * 数字地球独立窗口与 AgentChat 问答页之间的 BroadcastChannel 通道。
 *
 * 地球既是独立页面，也可能是由问答页打开的窗口：
 * - 打开时携带 ?chatId=xxx；手动打开时没有 chatId，收到第一个聊天消息后绑定。
 * - 启动后持续发送 hello，直到收到 sync；这样无论聊天页先开还是地球先开都能同步。
 * - 只处理 from=chat 且 chatId 匹配的消息；不依赖 window.parent。
 */

const CHANNEL_NAME = 'agentchat-earth-v1';
const HELLO_INTERVAL = 2000;
const HELLO_MAX_TRIES = 10;

let channel = null;
let handler = null;
let boundChatId = null;
let synced = false;
let helloTimer = null;
let helloTries = 0;
let lastHelloPayload = null;

function fromQuery () {
  try {
    return new URLSearchParams(window.location.search).get('chatId') || null;
  } catch (error) {
    return null;
  }
}

boundChatId = fromQuery();

export function getChatId () {
  return boundChatId;
}

export function supported () {
  return typeof window !== 'undefined' && typeof window.BroadcastChannel === 'function';
}

function ensureChannel () {
  if (channel) return channel;
  if (!supported()) return null;
  try {
    channel = new BroadcastChannel(CHANNEL_NAME);
  } catch (error) {
    console.warn('[earth] 初始化跨窗口通道失败：', error);
    channel = null;
  }
  return channel;
}

function stopHello () {
  if (helloTimer) {
    clearInterval(helloTimer);
    helloTimer = null;
  }
}

function sendRaw (type, payload) {
  const bc = ensureChannel();
  if (!bc) return false;
  try {
    bc.postMessage({
      channel: CHANNEL_NAME,
      from: 'earth',
      chatId: boundChatId,
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

/**
 * 发送消息到问答页。
 * @param {string} type
 * @param {*} [payload]
 * @returns {boolean}
 */
export function post (type, payload) {
  return sendRaw(type, payload);
}

/** 主动握手；payload 可携带 mode/capabilities，便于聊天页显示状态。 */
export function hello (payload) {
  if (payload !== undefined) lastHelloPayload = payload;
  sendRaw('hello', lastHelloPayload);
}

function startHelloRetry () {
  if (synced || helloTimer) return;
  hello(lastHelloPayload);
  helloTimer = setInterval(() => {
    if (synced || helloTries >= HELLO_MAX_TRIES) {
      stopHello();
      return;
    }
    helloTries += 1;
    hello(lastHelloPayload);
  }, HELLO_INTERVAL);
}

/**
 * 接收问答页消息。
 * @param {(type: string, payload: *) => void} callback
 * @returns {() => void} 取消监听
 */
export function onMessage (callback) {
  handler = typeof callback === 'function' ? callback : null;
  const bc = ensureChannel();

  if (!bc) {
    console.warn('[earth] 当前浏览器不支持 BroadcastChannel，数字地球将以独立窗口运行。');
    return function noop () {};
  }

  const listener = function (event) {
    const data = event && event.data;
    if (!data || data.channel !== CHANNEL_NAME || data.from !== 'chat') return;

    if (boundChatId && data.chatId && data.chatId !== boundChatId) return;
    if (!boundChatId && data.chatId) boundChatId = String(data.chatId);

    if (data.type === 'sync') {
      synced = true;
      stopHello();
    } else if (data.type === 'hello') {
      // 回答 hello，促使聊天页下发 sync；聊天页刷新后也能重新对齐。
      hello(lastHelloPayload);
    }

    if (handler) {
      try {
        handler(data.type, data.payload);
      } catch (error) {
        console.error('[earth] 处理问答页消息失败：', error);
      }
    }
  };

  bc.addEventListener('message', listener);
  startHelloRetry();

  return function unsubscribe () {
    try { bc.removeEventListener('message', listener); } catch (error) { /* ignore */ }
  };
}

/** 地球窗口关闭前释放通道。 */
export function close () {
  stopHello();
  if (channel) {
    try { channel.close(); } catch (error) { /* ignore */ }
    channel = null;
  }
  handler = null;
}

export default { post, onMessage, hello, close, getChatId, supported };
