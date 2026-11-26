// Markdown 渲染：marked + DOMPurify + highlight.js + 代码复制按钮
const marked = window.marked;
const DOMPurify = window.DOMPurify;
const hljs = window.hljs;

if (marked && marked.setOptions) {
  marked.setOptions({ gfm: true, breaks: true, headerIds: false, mangle: false });
}

export function renderMarkdown(text) {
  if (!text) return '';
  const html = marked ? marked.parse(text) : escapeHtml(text);
  if (!DOMPurify) return html;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
  });
}

export function enhance(root) {
  if (!root) return;
  root.querySelectorAll('a[href]').forEach((a) => {
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
  });

  root.querySelectorAll('pre').forEach((pre) => {
    if (pre.dataset.enhanced === '1') return;
    pre.dataset.enhanced = '1';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'code-copy';
    button.textContent = '复制';
    button.addEventListener('click', async () => {
      const code = pre.querySelector('code');
      try {
        await navigator.clipboard.writeText(code ? code.innerText : pre.innerText);
        button.textContent = '已复制';
      } catch (_) {
        button.textContent = '复制失败';
      }
      setTimeout(() => { button.textContent = '复制'; }, 1200);
    });
    pre.appendChild(button);
  });

  if (hljs) {
    root.querySelectorAll('pre code').forEach((code) => {
      if (code.dataset.highlighted) return;
      try { hljs.highlightElement(code); } catch (_) { /* ignore */ }
    });
  }
}

export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
