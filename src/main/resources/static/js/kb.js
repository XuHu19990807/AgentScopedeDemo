// 知识库工作台：文档管理 + 分段编辑/启停/排序 + 检索测试
import { get, post, del, upload } from './api.js';
import { escapeHtml } from './markdown.js';
import { toast } from './console.js';

const ACCEPT = '.pdf,.docx,.doc,.pptx,.ppt,.xlsx,.xls,.csv,.tsv,.txt,.md,.markdown,.log,'
  + '.json,.jsonl,.yaml,.yml,.xml,.html,.htm,.sql,.properties,.ini,.conf,'
  + '.sh,.bat,.ps1,.java,.py,.js,.ts,.css';

const state = {
  files: [],
  filter: '',
  selected: null,
  page: 1,
  pageSize: 20,
  query: '',
  chunkPage: null,
  draggingChunk: null,
  draggingFile: null,
};

let root = null;

/* ---------------- 入口 ---------------- */

export async function renderKnowledgeWorkbench(body) {
  root = body;
  root.innerHTML = `
    <div class="kb">
      <section class="kb-side">
        <div class="kb-side-head">
          <input class="cs-input" id="kbDocSearch" placeholder="搜索文档…" autocomplete="off">
          <button class="btn primary btn-sm" id="kbUploadBtn" type="button">＋ 上传</button>
          <input type="file" id="kbFileInput" multiple hidden accept="${ACCEPT}">
        </div>
        <div class="kb-drop" id="kbDrop">拖拽文件到这里上传<br><span class="cs-hint">PDF / Word / Excel / PPT / CSV / HTML / JSON / 文本</span></div>
        <div class="kb-uploads" id="kbUploads"></div>
        <div class="kb-doc-list-head">
          <span>文档 <b id="kbDocCount">0</b></span>
          <span>拖拽左侧手柄可排序</span>
        </div>
        <div class="kb-doc-list" id="kbDocList"><div class="cs-loading">加载中…</div></div>
      </section>
      <section class="kb-main" id="kbMain">
        <div class="kb-main-empty"><b>选择左侧文档</b>查看分段、编辑内容、启停与检索测试</div>
      </section>
    </div>`;

  bindSidebar();
  await loadFiles();
}

function bindSidebar() {
  root.querySelector('#kbDocSearch').addEventListener('input', (event) => {
    state.filter = event.target.value.trim().toLowerCase();
    renderDocList();
  });
  const input = root.querySelector('#kbFileInput');
  const drop = root.querySelector('#kbDrop');
  root.querySelector('#kbUploadBtn').addEventListener('click', () => input.click());
  drop.addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    const files = Array.from(input.files || []);
    input.value = '';
    if (files.length) handleUploads(files);
  });
  ['dragenter', 'dragover'].forEach((type) => drop.addEventListener(type, (event) => {
    event.preventDefault();
    drop.classList.add('over');
  }));
  ['dragleave', 'drop'].forEach((type) => drop.addEventListener(type, (event) => {
    event.preventDefault();
    if (type === 'dragleave' && event.target !== drop) return;
    drop.classList.remove('over');
  }));
  drop.addEventListener('drop', (event) => {
    const files = Array.from(event.dataTransfer?.files || []);
    if (files.length) handleUploads(files);
  });
}

/* ---------------- 文档列表 ---------------- */

async function loadFiles(keepSelection = true) {
  try {
    state.files = (await get('/api/knowledge/files')) || [];
  } catch (error) {
    state.files = [];
    root.querySelector('#kbDocList').innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载失败')}</div>`;
    return;
  }
  if (!keepSelection) state.selected = null;
  if (state.selected && !state.files.some((file) => file.filename === state.selected)) {
    state.selected = null;
  }
  if (!state.selected && state.files.length) {
    state.selected = state.files[0].filename;
  }
  renderDocList();
  if (state.selected) await renderDocMain();
  else root.querySelector('#kbMain').innerHTML = '<div class="kb-main-empty"><b>还没有文档</b>上传文档后即可在这里管理分段与检索</div>';
}

function renderDocList() {
  const list = root.querySelector('#kbDocList');
  const keyword = state.filter;
  const visible = state.files.filter((file) => !keyword || file.filename.toLowerCase().includes(keyword));
  root.querySelector('#kbDocCount').textContent = `${state.files.length}`;
  if (!visible.length) {
    list.innerHTML = `<div class="cs-empty">${state.files.length ? '没有匹配的文档' : '暂无文档'}</div>`;
    return;
  }
  list.innerHTML = visible.map((file) => `
    <div class="kb-doc ${file.filename === state.selected ? 'active' : ''}" data-doc="${escapeHtml(file.filename)}" draggable="true">
      <span class="kb-doc-handle" title="拖拽排序">⋮⋮</span>
      <div class="kb-doc-main">
        <div class="kb-doc-name" title="${escapeHtml(file.filename)}">${escapeHtml(file.filename)}</div>
        <div class="kb-doc-meta">${file.chunkCount || 0} 段${file.disabledCount ? ` · 停用 ${file.disabledCount}` : ''} · ${Math.round((file.fileSize || 0) / 1024)} KB</div>
      </div>
    </div>`).join('');

  list.querySelectorAll('.kb-doc').forEach((row) => {
    row.addEventListener('click', async () => {
      if (state.selected === row.dataset.doc) return;
      state.selected = row.dataset.doc;
      state.page = 1;
      state.query = '';
      renderDocList();
      await renderDocMain();
    });
    row.addEventListener('dragstart', (event) => {
      state.draggingFile = row.dataset.doc;
      row.classList.add('dragging');
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', row.dataset.doc);
    });
    row.addEventListener('dragend', () => {
      state.draggingFile = null;
      row.classList.remove('dragging');
      list.querySelectorAll('.drop-target').forEach((node) => node.classList.remove('drop-target'));
    });
    row.addEventListener('dragover', (event) => {
      if (!state.draggingFile || state.draggingFile === row.dataset.doc) return;
      event.preventDefault();
      list.querySelectorAll('.drop-target').forEach((node) => node.classList.remove('drop-target'));
      row.classList.add('drop-target');
    });
    row.addEventListener('drop', async (event) => {
      event.preventDefault();
      const dragged = state.draggingFile;
      const target = row.dataset.doc;
      row.classList.remove('drop-target');
      if (!dragged || dragged === target) return;
      const order = state.files.map((file) => file.filename).filter((name) => name !== dragged);
      const index = order.indexOf(target);
      order.splice(index < 0 ? order.length : index, 0, dragged);
      await persistFileOrder(order);
    });
  });
}

async function persistFileOrder(order) {
  try {
    state.files = (await post('/api/knowledge/files/order', { filenames: order })) || state.files;
    renderDocList();
    toast('文档顺序已保存');
  } catch (error) {
    toast(error.message || '排序保存失败', 'error');
    await loadFiles();
  }
}

/* ---------------- 上传 ---------------- */

async function handleUploads(files) {
  const container = root.querySelector('#kbUploads');
  for (const file of files) {
    const item = document.createElement('div');
    item.className = 'kb-upload-item';
    item.innerHTML = `
      <div class="kb-up-head"><span class="kb-up-name">${escapeHtml(file.name)}</span><span class="kb-up-status">排队中…</span></div>
      <div class="kb-bar"><i></i></div>`;
    container.appendChild(item);
    const status = item.querySelector('.kb-up-status');
    const bar = item.querySelector('.kb-bar > i');
    const form = new FormData();
    form.append('file', file);
    status.textContent = '上传中…';
    try {
      const data = await upload('/api/knowledge/upload', form, (ratio) => {
        bar.style.width = `${Math.round(Math.max(0.02, ratio) * 100)}%`;
      });
      bar.style.width = '100%';
      if (data && data.success) {
        status.textContent = `完成 · ${data.chunkCount} 段`;
      } else {
        item.classList.add('err');
        status.textContent = (data && data.message) || '失败';
      }
    } catch (error) {
      item.classList.add('err');
      status.textContent = error.message || '上传失败';
    }
  }
  await loadFiles();
  setTimeout(() => {
    container.querySelectorAll('.kb-upload-item').forEach((node) => {
      if (!node.classList.contains('err')) node.remove();
    });
  }, 4000);
}

/* ---------------- 文档主区 ---------------- */

async function renderDocMain() {
  const main = root.querySelector('#kbMain');
  const filename = state.selected;
  if (!filename) return;
  main.innerHTML = '<div class="cs-loading">加载分段…</div>';
  let pageData;
  try {
    pageData = await fetchPage(filename, state.page, state.pageSize, state.query);
  } catch (error) {
    main.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '加载失败')}</div>`;
    return;
  }
  state.chunkPage = pageData;
  const meta = state.files.find((file) => file.filename === filename) || {};

  main.innerHTML = `
    <div class="kb-doc-head">
      <div class="kb-doc-title">
        <h2>${escapeHtml(filename)}</h2>
        <p>
          <span>${pageData.total} 段（启用 ${pageData.enabledCount} / 停用 ${pageData.disabledCount}）</span>
          <span>${Math.round((meta.fileSize || 0) / 1024)} KB</span>
          <span>${meta.hasOriginal ? '原文已留存' : '无原文'}</span>
          <span class="cs-mono">docId ${escapeHtml(pageData.docId || '')}</span>
        </p>
      </div>
      <div class="kb-actions">
        <button class="btn-sm" data-act="rename">重命名</button>
        <button class="btn-sm" data-act="reparse">重新解析</button>
        <button class="btn-sm" data-act="original">原文预览</button>
        <button class="btn-sm danger" data-act="delete">删除</button>
      </div>
    </div>
    <div class="kb-stats">
      <span class="kb-chip">总分段 <b>${pageData.total}</b></span>
      <span class="kb-chip">启用 <b>${pageData.enabledCount}</b></span>
      <span class="kb-chip">停用 <b>${pageData.disabledCount}</b></span>
      <span class="kb-chip">本页 <b>${pageData.items.length}</b></span>
      <span class="kb-chip">第 <b>${pageData.page}</b>/${pageData.pages} 页</span>
    </div>
    <div class="kb-chunk-toolbar">
      <input class="cs-input" id="kbChunkSearch" placeholder="搜索本文件分段内容…" value="${escapeHtml(state.query)}">
      <button class="btn-sm" id="kbChunkSearchBtn">搜索</button>
      <button class="btn-sm" id="kbChunkClear">清除</button>
      <select class="cs-select" id="kbPageSize">
        ${[20, 50, 100].map((size) => `<option value="${size}" ${size === state.pageSize ? 'selected' : ''}>每页 ${size}</option>`).join('')}
      </select>
      <span class="cs-spacer"></span>
      <button class="btn-sm" id="kbChunkRefresh">刷新</button>
    </div>
    <div class="kb-order-flag" id="kbOrderFlag" hidden>顺序有变化，松手后会自动保存并重新向量化</div>
    <div class="kb-chunks" id="kbChunks">
      ${pageData.items.map(chunkCard).join('') || '<div class="cs-empty">没有匹配的分段</div>'}
    </div>
    <div class="kb-pager">
      <button class="btn-sm" id="kbPrev" ${pageData.page <= 1 ? 'disabled' : ''}>上一页</button>
      <span>第 ${pageData.page} / ${pageData.pages} 页</span>
      <button class="btn-sm" id="kbNext" ${pageData.page >= pageData.pages ? 'disabled' : ''}>下一页</button>
    </div>
    <section class="cs-card" style="margin-top:18px">
      <div class="cs-card-head"><h2>检索测试</h2><span class="cs-sub">BM25 + 向量 RRF（hybrid）</span></div>
      <div class="cs-inline">
        <input class="cs-input" id="kbSearchQuery" placeholder="输入问题，验证召回效果…" style="flex:1;min-width:200px">
        <select class="cs-select" id="kbSearchMode">
          <option value="hybrid">混合</option>
          <option value="bm25">BM25</option>
          <option value="vector">向量</option>
        </select>
        <select class="cs-select" id="kbSearchTopK">
          <option value="5">5 条</option>
          <option value="8" selected>8 条</option>
          <option value="15">15 条</option>
        </select>
        <button class="btn primary btn-sm" id="kbSearchBtn">检索</button>
      </div>
      <div class="kb-hits" id="kbSearchHits" style="margin-top:12px"></div>
    </section>`;

  bindDocActions(filename);
  bindChunkList(filename);
  bindSearch();
}

function chunkCard(item) {
  return `
    <div class="kb-chunk ${item.enabled ? '' : 'disabled'}" data-chunk="${escapeHtml(item.chunkId)}" draggable="true">
      <span class="kb-chunk-handle" title="拖拽重排">⋮⋮</span>
      <div class="kb-chunk-main">
        <div class="kb-chunk-head">
          <span class="kb-chunk-idx">#${item.index}</span>
          ${item.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge off">停用</span>'}
          <span class="kb-chunk-path">${escapeHtml(item.headingPath || '（无标题）')}</span>
        </div>
        <div class="kb-chunk-preview">${escapeHtml(item.preview || '')}</div>
        <div class="kb-chunk-foot">
          <span>${item.chars} 字符</span>
          <span>第 ${item.lineStart}-${item.lineEnd} 行</span>
        </div>
      </div>
      <div class="kb-chunk-actions">
        <button class="btn-sm" data-chunk-act="view">详情</button>
        <button class="btn-sm" data-chunk-act="edit">编辑</button>
        <button class="btn-sm ${item.enabled ? 'danger' : ''}" data-chunk-act="toggle">${item.enabled ? '停用' : '启用'}</button>
      </div>
    </div>`;
}

function bindDocActions(filename) {
  const main = root.querySelector('#kbMain');
  main.querySelector('[data-act="rename"]').addEventListener('click', async () => {
    const next = window.prompt('新的文件名', filename);
    if (!next || next.trim() === filename) return;
    try {
      const result = await post(`/api/knowledge/files/${encodeURIComponent(filename)}/rename`, { filename: next.trim() });
      toast('已重命名');
      state.selected = result.filename;
      await loadFiles();
    } catch (error) {
      toast(error.message || '重命名失败', 'error');
    }
  });

  main.querySelector('[data-act="reparse"]').addEventListener('click', async () => {
    if (!window.confirm('重新解析会用原文重新切分并重新向量化，当前对该文件的编辑与停用状态将被覆盖，继续？')) return;
    main.classList.add('busy');
    try {
      const pageData = await post(`/api/knowledge/files/${encodeURIComponent(filename)}/reparse`);
      toast(`已重新解析，${pageData.total} 段`);
      await loadFiles();
    } catch (error) {
      toast(error.message || '重新解析失败', 'error');
    } finally {
      main.classList.remove('busy');
    }
  });

  main.querySelector('[data-act="original"]').addEventListener('click', () => openOriginal(filename));

  main.querySelector('[data-act="delete"]').addEventListener('click', async () => {
    if (!window.confirm(`删除「${filename}」及其全部分段？`)) return;
    try {
      await del(`/api/knowledge/files/${encodeURIComponent(filename)}`);
      toast('已删除');
      state.selected = null;
      await loadFiles(false);
    } catch (error) {
      toast(error.message || '删除失败', 'error');
    }
  });
}

function bindChunkList(filename) {
  const main = root.querySelector('#kbMain');
  const list = main.querySelector('#kbChunks');

  main.querySelector('#kbChunkSearchBtn').addEventListener('click', () => {
    state.query = main.querySelector('#kbChunkSearch').value.trim();
    state.page = 1;
    renderDocMain();
  });
  main.querySelector('#kbChunkSearch').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      state.query = event.target.value.trim();
      state.page = 1;
      renderDocMain();
    }
  });
  main.querySelector('#kbChunkClear').addEventListener('click', () => {
    state.query = '';
    state.page = 1;
    renderDocMain();
  });
  main.querySelector('#kbChunkRefresh').addEventListener('click', () => renderDocMain());
  main.querySelector('#kbPageSize').addEventListener('change', (event) => {
    state.pageSize = Number(event.target.value) || 20;
    state.page = 1;
    renderDocMain();
  });
  main.querySelector('#kbPrev').addEventListener('click', () => {
    if (state.page > 1) {
      state.page -= 1;
      renderDocMain();
    }
  });
  main.querySelector('#kbNext').addEventListener('click', () => {
    if (state.chunkPage && state.page < state.chunkPage.pages) {
      state.page += 1;
      renderDocMain();
    }
  });

  list.querySelectorAll('.kb-chunk').forEach((card) => {
    const chunkId = card.dataset.chunk;
    card.querySelector('[data-chunk-act="view"]').addEventListener('click', () => openChunkDetail(chunkId));
    card.querySelector('[data-chunk-act="edit"]').addEventListener('click', () => openChunkEditor(chunkId, filename));
    card.querySelector('[data-chunk-act="toggle"]').addEventListener('click', async () => {
      const enabled = card.classList.contains('disabled');
      card.classList.add('busy');
      try {
        await post('/api/knowledge/chunk/enabled', { chunkId, enabled });
        toast(enabled ? '已启用并重新向量化' : '已停用');
        await loadFiles();
      } catch (error) {
        toast(error.message || '操作失败', 'error');
      } finally {
        card.classList.remove('busy');
      }
    });

    card.addEventListener('dragstart', (event) => {
      state.draggingChunk = chunkId;
      card.classList.add('dragging');
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', chunkId);
    });
    card.addEventListener('dragend', () => {
      state.draggingChunk = null;
      card.classList.remove('dragging');
      list.querySelectorAll('.drop-target').forEach((node) => node.classList.remove('drop-target'));
    });
    card.addEventListener('dragover', (event) => {
      if (!state.draggingChunk || state.draggingChunk === chunkId) return;
      event.preventDefault();
      list.querySelectorAll('.drop-target').forEach((node) => node.classList.remove('drop-target'));
      card.classList.add('drop-target');
    });
    card.addEventListener('drop', async (event) => {
      event.preventDefault();
      const dragged = state.draggingChunk;
      card.classList.remove('drop-target');
      if (!dragged || dragged === chunkId) return;
      await persistChunkOrder(filename, dragged, chunkId);
    });
  });
}

async function persistChunkOrder(filename, draggedId, targetId) {
  const flag = root.querySelector('#kbOrderFlag');
  if (flag) flag.hidden = false;
  try {
    const ids = await fetchAllChunkIds(filename);
    const order = ids.filter((id) => id !== draggedId);
    const index = order.indexOf(targetId);
    order.splice(index < 0 ? order.length : index, 0, draggedId);
    await post(`/api/knowledge/files/${encodeURIComponent(filename)}/chunks/reorder`, { chunkIds: order });
    toast('分段顺序已保存并重新向量化');
    state.page = 1;
    await loadFiles();
  } catch (error) {
    toast(error.message || '排序失败', 'error');
    await renderDocMain();
  } finally {
    if (flag) flag.hidden = true;
  }
}

async function fetchAllChunkIds(filename) {
  const base = `/api/knowledge/files/${encodeURIComponent(filename)}/chunks`;
  const first = await get(`${base}?page=1&pageSize=100`);
  const ids = (first.items || []).map((item) => item.chunkId);
  if (first.pages > 1) {
    const rest = await Promise.all(
      Array.from({ length: first.pages - 1 }, (_, i) => get(`${base}?page=${i + 2}&pageSize=100`)),
    );
    rest.forEach((pageData) => ids.push(...(pageData.items || []).map((item) => item.chunkId)));
  }
  return ids;
}

function fetchPage(filename, page, pageSize, query) {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (query) params.set('q', query);
  return get(`/api/knowledge/files/${encodeURIComponent(filename)}/chunks?${params.toString()}`);
}

/* ---------------- 检索测试 ---------------- */

function bindSearch() {
  const main = root.querySelector('#kbMain');
  const run = async () => {
    const query = main.querySelector('#kbSearchQuery').value.trim();
    if (!query) return toast('请输入检索内容', 'error');
    const mode = main.querySelector('#kbSearchMode').value;
    const topK = main.querySelector('#kbSearchTopK').value;
    const hits = main.querySelector('#kbSearchHits');
    hits.innerHTML = '<div class="cs-loading">检索中…</div>';
    try {
      const data = await get(`/api/knowledge/search?q=${encodeURIComponent(query)}&mode=${mode}&topK=${topK}`);
      const degraded = data.degraded ? `<div class="cs-hint">降级：${escapeHtml(data.degradedReason || '部分通路不可用')}</div>` : '';
      hits.innerHTML = degraded + ((data.hits || []).map((hit) => `
        <div class="kb-hit">
          <div class="kb-hit-head">
            <span class="badge ${hit.matchedBy === 'bm25' ? 'warn' : 'ok'}">${escapeHtml(hit.matchedBy || '')}</span>
            <span>${escapeHtml(hit.filename)}</span>
            <span>${escapeHtml(hit.location || '')}</span>
            <span class="kb-hit-score">${Number(hit.score || 0).toFixed(4)}</span>
          </div>
          <div class="kb-hit-text">${escapeHtml(hit.content || '')}</div>
        </div>`).join('') || '<div class="cs-empty">没有命中</div>');
      const meta = `耗时 ${data.tookMs}ms · BM25 ${data.bm25Count} · 向量 ${data.vectorCount}`;
      hits.insertAdjacentHTML('afterbegin', `<div class="cs-hint">${escapeHtml(meta)}</div>`);
    } catch (error) {
      hits.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '检索失败')}</div>`;
    }
  };
  main.querySelector('#kbSearchBtn').addEventListener('click', run);
  main.querySelector('#kbSearchQuery').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') run();
  });
}

/* ---------------- 抽屉：详情 / 编辑 / 原文 ---------------- */

function ensureDrawer() {
  let drawer = document.getElementById('kbDrawer');
  if (drawer) return drawer;
  drawer = document.createElement('div');
  drawer.className = 'kb-drawer';
  drawer.id = 'kbDrawer';
  drawer.hidden = true;
  drawer.innerHTML = `
    <div class="kb-drawer-head">
      <h3 id="kbDrawerTitle">详情</h3>
      <button class="btn-sm" id="kbDrawerClose" type="button">关闭</button>
    </div>
    <div class="kb-drawer-body" id="kbDrawerBody"></div>
    <div class="kb-drawer-foot" id="kbDrawerFoot"></div>`;
  document.body.appendChild(drawer);
  drawer.querySelector('#kbDrawerClose').addEventListener('click', closeDrawer);
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && drawer.classList.contains('show')) closeDrawer();
  });
  return drawer;
}

function openDrawer({ title, bodyHtml, footHtml }) {
  const drawer = ensureDrawer();
  drawer.querySelector('#kbDrawerTitle').textContent = title;
  drawer.querySelector('#kbDrawerBody').innerHTML = bodyHtml;
  drawer.querySelector('#kbDrawerFoot').innerHTML = footHtml || '';
  drawer.hidden = false;
  requestAnimationFrame(() => drawer.classList.add('show'));
}

function closeDrawer() {
  const drawer = document.getElementById('kbDrawer');
  if (!drawer) return;
  drawer.classList.remove('show');
  setTimeout(() => {
    drawer.hidden = true;
  }, 180);
}

async function openChunkDetail(chunkId) {
  try {
    const detail = await get(`/api/knowledge/chunk?chunkId=${encodeURIComponent(chunkId)}`);
    openDrawer({
      title: `分段 #${detail.index} · ${detail.filename}`,
      bodyHtml: `
        <div class="cs-kv">
          <div class="cs-kv-row"><span>状态</span><span>${detail.enabled ? '启用' : '停用'}</span></div>
          <div class="cs-kv-row"><span>标题路径</span><span>${escapeHtml(detail.headingPath || '（无）')}</span></div>
          <div class="cs-kv-row"><span>行范围</span><span>第 ${detail.lineStart}-${detail.lineEnd} 行</span></div>
          <div class="cs-kv-row"><span>字符数</span><span>${detail.chars}</span></div>
        </div>
        <div class="cs-hint">分段内容</div>
        <pre class="kb-original">${escapeHtml(detail.content || '')}</pre>
        <div class="cs-hint">父块（检索命中后注入模型的内容）</div>
        <pre class="kb-original">${escapeHtml(detail.parentText || detail.content || '')}</pre>`,
      footHtml: '<button class="btn-sm" data-close>关闭</button>',
    });
    bindCloseButton();
  } catch (error) {
    toast(error.message || '加载失败', 'error');
  }
}

async function openChunkEditor(chunkId, filename) {
  let detail;
  try {
    detail = await get(`/api/knowledge/chunk?chunkId=${encodeURIComponent(chunkId)}`);
  } catch (error) {
    toast(error.message || '加载失败', 'error');
    return;
  }
  openDrawer({
    title: `编辑分段 #${detail.index}`,
    bodyHtml: `
      <div class="kb-edit-meta">
        <span>${escapeHtml(detail.filename)} · ${escapeHtml(detail.headingPath || '（无标题）')}</span>
        <span id="kbEditCount">${(detail.content || '').length} 字符</span>
      </div>
      <textarea class="kb-editor" id="kbEditorText">${escapeHtml(detail.content || '')}</textarea>
      <div class="cs-hint">保存后仅重新向量化该分段，并刷新相邻分段的父块；chunkId 保持不变。</div>`,
    footHtml: `
      <button class="btn-sm" data-close>取消</button>
      <button class="btn primary btn-sm" id="kbEditorSave">保存</button>`,
  });
  bindCloseButton();
  const textarea = document.getElementById('kbEditorText');
  textarea.addEventListener('input', () => {
    document.getElementById('kbEditCount').textContent = `${textarea.value.length} 字符`;
  });
  document.getElementById('kbEditorSave').addEventListener('click', async () => {
    const content = textarea.value.trim();
    if (content.length < 2) return toast('内容太短', 'error');
    if (content.length > 8000) return toast('单段超过 8000 字符上限', 'error');
    try {
      await post('/api/knowledge/chunk/update', { chunkId, content });
      toast('已保存并重新向量化');
      closeDrawer();
      await loadFiles();
    } catch (error) {
      toast(error.message || '保存失败', 'error');
    }
  });
}

async function openOriginal(filename) {
  openDrawer({
    title: `原文预览 · ${filename}`,
    bodyHtml: '<div class="cs-loading">读取中…</div>',
    footHtml: `<button class="btn-sm" data-download>下载</button>
      <button class="btn-sm" data-close>关闭</button>`,
  });
  bindCloseButton();
  const footEl = document.getElementById('kbDrawerFoot');
  if (footEl) {
    footEl.querySelector('[data-download]')?.addEventListener('click', () => downloadOriginal(filename));
  }
  const bodyEl = document.getElementById('kbDrawerBody');
  try {
    const token = localStorage.getItem('agentchat.adminToken');
    const headers = token ? { 'X-Admin-Token': token } : {};
    const res = await fetch(`/api/knowledge/files/${encodeURIComponent(filename)}/original`, { headers });
    if (!res.ok) {
      let message = `HTTP ${res.status}`;
      try {
        const data = await res.json();
        message = data.message || data.error || message;
      } catch (_) { /* ignore */ }
      throw new Error(message);
    }
    const buffer = await res.arrayBuffer();
    bodyEl.innerHTML = `<pre class="kb-original">${escapeHtml(decodeText(buffer))}</pre>`;
  } catch (error) {
    bodyEl.innerHTML = `<div class="cs-error">${escapeHtml(error.message || '读取失败')}</div>`;
  }
}

/** 下载原文：带管理令牌请求后转成 blob 下载，避免远程访问时直链被 AdminGuard 拒绝。 */
async function downloadOriginal(filename) {
  try {
    const token = localStorage.getItem('agentchat.adminToken');
    const headers = token ? { 'X-Admin-Token': token } : {};
    const res = await fetch(`/api/knowledge/files/${encodeURIComponent(filename)}/original`, { headers });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    toast(error.message || '下载失败', 'error');
  }
}

function bindCloseButton() {
  document.querySelectorAll('#kbDrawerFoot [data-close]').forEach((button) => {
    button.addEventListener('click', closeDrawer);
  });
}

function decodeText(buffer) {
  const sample = new Uint8Array(buffer.slice(0, Math.min(buffer.byteLength, 4096)));
  if (sample.some((byte) => byte === 0)) return '（二进制文件，无法文本预览，请下载）';
  try {
    return new TextDecoder('utf-8', { fatal: false }).decode(buffer);
  } catch (_) {
    return '（解码失败，请下载后查看）';
  }
}
