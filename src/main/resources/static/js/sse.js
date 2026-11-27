// SSE 客户端：支持 CRLF、多行 data、注释心跳、AbortController
import { sessionToken, notifyExpired } from './auth.js';

export async function streamChat(payload, handlers = {}, signal) {
  const headers = { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' };
  const token = sessionToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal,
    credentials: 'same-origin',
  });

  if (!res.ok) {
    if (res.status === 401) notifyExpired();
    let message = `HTTP ${res.status}`;
    try {
      const data = await res.json();
      message = data.error || data.message || message;
    } catch (_) { /* ignore */ }
    const error = new Error(message);
    error.status = res.status;
    throw error;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventType = '';
  let dataLines = [];

  const dispatch = () => {
    if (!dataLines.length) {
      eventType = '';
      return;
    }
    const raw = dataLines.join('\n');
    dataLines = [];
    let data = raw;
    try {
      data = JSON.parse(raw);
    } catch (_) { /* 非 JSON 数据原样传递 */ }
    const type = eventType || 'message';
    eventType = '';
    handlers.onEvent?.(type, data);
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index;
      while ((index = buffer.indexOf('\n')) !== -1) {
        let line = buffer.slice(0, index);
        buffer = buffer.slice(index + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);

        if (line === '') {
          dispatch();
        } else if (line.startsWith(':')) {
          handlers.onPing?.();
        } else if (line.startsWith('event:')) {
          eventType = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^ /, ''));
        }
      }
    }
    dispatch();
  } finally {
    try { reader.releaseLock(); } catch (_) { /* ignore */ }
  }
}
