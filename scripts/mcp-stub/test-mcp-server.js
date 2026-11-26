// 最小 MCP stdio 服务器 — 用于验证 AgentScopeDemo 的 MCP 集成
// 实现 MCP 协议核心方法: initialize / tools/list / tools/call
const readline = require('readline');
const rl = readline.createInterface({ input: process.stdin, terminal: false });

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

rl.on('line', (line) => {
  let req;
  try { req = JSON.parse(line); } catch (e) { return; }
  const { id, method, params } = req;

  switch (method) {
    case 'initialize':
      send({
        jsonrpc: '2.0', id,
        result: {
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'test-mcp-server', version: '1.0.0' }
        }
      });
      break;

    case 'notifications/initialized':
      break; // 无需响应

    case 'tools/list':
      send({
        jsonrpc: '2.0', id,
        result: {
          tools: [
            {
              name: 'getNetworkTestData',
              description: '返回测试网络数据，用于验证 MCP 工具调用链路',
              inputSchema: {
                type: 'object',
                properties: {
                  topic: { type: 'string', description: '查询主题' }
                },
                required: []
              }
            }
          ]
        }
      });
      break;

    case 'tools/call':
      const { name, arguments: args } = params || {};
      send({
        jsonrpc: '2.0', id,
        result: {
          content: [
            { type: 'text', text: `✅ MCP工具[${name}]调用成功！参数=${JSON.stringify(args || {})}。这是来自 test-mcp-server 的测试返回数据。` }
          ],
          isError: false
        }
      });
      break;

    default:
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
  }
});

process.stderr.write('test-mcp-server started (stdio)\n');
