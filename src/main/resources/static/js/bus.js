// 极简事件总线：解耦 chat / sessions / admin 模块
const listeners = new Map();

export function on(event, handler) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(handler);
  return () => listeners.get(event)?.delete(handler);
}

export function emit(event, payload) {
  const set = listeners.get(event);
  if (!set) return;
  for (const handler of [...set]) {
    try { handler(payload); } catch (e) { console.error('[bus]', event, e); }
  }
}
