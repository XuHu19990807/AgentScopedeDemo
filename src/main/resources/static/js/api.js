// 统一 fetch 封装：JSON 请求、错误归一化
export class ApiError extends Error {
  constructor(message, status) {
    super(message || '请求失败');
    this.name = 'ApiError';
    this.status = status || 0;
  }
}

export async function request(path, options = {}) {
  const { method = 'GET', body, headers = {}, signal } = options;
  const init = { method, headers: { ...headers }, signal };
  const adminToken = localStorage.getItem('agentchat.adminToken');
  if (adminToken) init.headers['X-Admin-Token'] = adminToken;
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError('网络异常，请检查服务是否启动', 0);
  }
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const data = await res.json();
      message = data.error || data.message || message;
    } catch (_) { /* ignore */ }
    throw new ApiError(message, res.status);
  }
  if (res.status === 204) return null;
  const contentType = res.headers.get('content-type') || '';
  return contentType.includes('application/json') ? res.json() : res.text();
}

export const get = (path) => request(path);
export const post = (path, body) => request(path, { method: 'POST', body });
export const put = (path, body) => request(path, { method: 'PUT', body });
export const patch = (path, body) => request(path, { method: 'PATCH', body });
export const del = (path) => request(path, { method: 'DELETE' });

/**
 * multipart 上传（带进度）：用 XHR 才能拿到 upload.onprogress，fetch 不支持上传进度。
 */
export function upload(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', path);
    const adminToken = localStorage.getItem('agentchat.adminToken');
    if (adminToken) xhr.setRequestHeader('X-Admin-Token', adminToken);
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress(event.loaded / event.total);
      };
    }
    xhr.onload = () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch (_) { /* 非 JSON */ }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(data);
      } else {
        reject(new ApiError((data && (data.message || data.error)) || `HTTP ${xhr.status}`, xhr.status));
      }
    };
    xhr.onerror = () => reject(new ApiError('网络异常，请检查服务是否启动', 0));
    xhr.send(formData);
  });
}

/**
 * multipart 上传：不要手动设置 Content-Type，交给浏览器带 boundary。
 */
export async function uploadFile(path, formData) {
  let res;
  try {
    res = await fetch(path, { method: 'POST', body: formData });
  } catch (_) {
    throw new ApiError('网络异常，请检查服务是否启动', 0);
  }
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const data = await res.json();
      message = data.error || data.message || message;
    } catch (_) { /* ignore */ }
    throw new ApiError(message, res.status);
  }
  return res.json();
}
