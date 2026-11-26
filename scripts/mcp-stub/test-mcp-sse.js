// 最小 MCP HTTP+SSE 服务器 — 验证 AgentScopeDemo 的 MCP SSE 集成
// 协议要点:
//   1. GET /mcp/sse → SSE 流, 发送 endpoint 事件
//   2. POST /mcp/messages → 返回 202 Accepted, 实际 JSON-RPC 响应通过 SSE 流下发
const http = require('http');
const crypto = require('crypto');
const PORT = process.env.MCP_PORT || 8081;

let sessions = new Map(); // sessionId -> SSE response

function sseSend(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // ---- SSE 端点 (SDK 默认 /sse) ----
  if (url.pathname === '/sse' && req.method === 'GET') {
    const sessionId = crypto.randomUUID();
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });
    // endpoint 事件用绝对 URL，SDK 直接使用
    res.write(`event: endpoint\ndata: http://localhost:${PORT}/mcp/messages?sessionId=${sessionId}\n\n`);
    sessions.set(sessionId, res);
    process.stderr.write(`[SSE] client connected: ${sessionId}\n`);
    req.on('close', () => { sessions.delete(sessionId); process.stderr.write(`[SSE] client closed: ${sessionId}\n`); });
    return;
  }

  // ---- 消息端点 ----
  if (url.pathname === '/mcp/messages' && req.method === 'POST') {
    const sessionId = url.searchParams.get('sessionId');
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      let rpc;
      try { rpc = JSON.parse(body); } catch (e) { res.writeHead(400); res.end('bad json'); return; }
      const { id, method, params } = rpc;

      // 通知 (notifications/*): 无响应, 仅返回 202
      if (method && method.startsWith('notifications/')) {
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end();
        process.stderr.write(`[SSE] notification ${method} acked (no response)\n`);
        return;
      }

      let result;
      if (method === 'initialize') {
        result = {
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'test-mcp-sse', version: '1.0.0' }
        };
      } else if (method === 'tools/list') {
        result = {
          tools: [{
            name: 'getNetworkTestData',
            description: '返回测试网络数据，用于验证 MCP 工具调用链路',
            inputSchema: {
              type: 'object',
              properties: { topic: { type: 'string', description: '查询主题' } },
              required: []
            }
          }]
        };
      } else if (method === 'tools/call') {
        const { name, arguments: args } = params || {};
        result = {
          content: [{ type: 'text', text: `✅ MCP工具[${name}]调用成功！参数=${JSON.stringify(args || {})}。这是来自 test-mcp-sse 的测试返回数据。` }],
          isError: false
        };
      } else {
        result = { error: { code: -32601, message: 'not found: ' + method } };
      }

      // 202 Accepted (HTTP+SSE 协议: POST body 不直接返回响应)
      res.writeHead(202, { 'Content-Type': 'application/json' });
      res.end();

      // 实际响应通过 SSE 流下发
      const sseRes = sessions.get(sessionId);
      if (sseRes) {
        sseSend(sseRes, 'message', { jsonrpc: '2.0', id, result });
        process.stderr.write(`[SSE] responded to method=${method} id=${id}\n`);
      } else {
        process.stderr.write(`[SSE] WARN no session ${sessionId} for method=${method}\n`);
      }
    });
    return;
  }

  res.writeHead(404); res.end('not found');
});

server.listen(PORT, () => process.stderr.write(`test-mcp-sse listening on http://localhost:${PORT}\n`));
