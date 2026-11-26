# 本地运行与排障

## 1. 依赖检查

```powershell
java -version      # 17+
mvn -v             # 3.9+
redis-cli ping     # PONG
redis-cli FT._LIST # 应能看到 idx_knowledge / idx_memory
```

如果是普通 Redis（没有 RediSearch），应用仍能启动，但 `/api/health` 会显示 `redisSearch: missing`，知识库检索不可用。

## 2. 启动

```powershell
$env:AI_API_KEY='sk-xxx'
$env:NACOS_ENABLED='false'
mvn -DskipTests package
java -jar target\AgentScopeDemo-2.0.0.jar
```

启动日志出现 `Started AgentScopeDemoApplication` 后访问 <http://localhost:8091>。

## 3. 验证接口

```powershell
curl.exe http://127.0.0.1:8091/api/health
curl.exe http://127.0.0.1:8091/api/skills
```

SSE 冒烟（PowerShell 需要把 JSON 写入文件，避免引号被转义）：

```powershell
Set-Content -Path body.json -Value '{"content":"你好"}' -Encoding utf8
curl.exe -N -b cookies.txt -c cookies.txt -X POST http://127.0.0.1:8091/api/chat/stream `
  -H "Content-Type: application/json" --data-binary "@body.json"
```

预期：`event:meta` → `event:delta`（或模型鉴权错误时的 `event:error` + `event:done`）。

对话内文件冒烟：

```powershell
# 1. 上传（返回 fileId）
curl.exe -b cookies.txt -c cookies.txt -F "file=@D:\path\demo.pdf" `
  http://127.0.0.1:8091/api/chat/files

# 2. 只发文件，不写问题
Set-Content -Path body.json -Value '{"fileIds":["f_xxxxxxxx"]}' -Encoding utf8
curl.exe -N -b cookies.txt -c cookies.txt -X POST http://127.0.0.1:8091/api/chat/stream `
  -H "Content-Type: application/json" --data-binary "@body.json"
```

## 4. 多 Agent 编排

页面顶部模式开关的「多 Agent」（`mode=orchestrate`）：先由统筹 Agent 产出 JSON 计划，再按计划分派给多个专业 Agent。

- 默认「串行接力」：按计划顺序执行，前一个 Agent 的输出会累积进下一个 Agent 的上下文。
- 勾选「并行+汇总」：各 Agent 同时执行（彼此不可见），全部结束后由主模型合成一段统一答复；开关状态记在浏览器 localStorage。
- 消息上方会显示阶段芯片：`执行中…` → `耗时`；汇总阶段显示「汇总」芯片；失败显示红点和原因。
- 并行相关配置在 `application.yml` 的 `agent.orchestration`：`parallel-max-agents`（默认 6）、`parallel-worker-timeout-seconds`（默认 300）、`summary-enabled`、`summary-per-agent-max-chars` / `summary-total-max-chars` / `summary-temperature`。
- 带附件的消息仍会降级为对话直答（编排的 worker 读不到对话附件），页面会提示本轮降级。

API 触发与页面等价（`parallel` 缺省为串行）：

```powershell
Set-Content -Path body.json -Value '{"mode":"orchestrate","parallel":true,"content":"..."}' -Encoding utf8
curl.exe -N -b cookies.txt -c cookies.txt -X POST http://127.0.0.1:8091/api/chat/stream `
  -H "Content-Type: application/json" --data-binary "@body.json"
```

除 `meta/delta/done` 外，编排还会产生 `agent_start` / `agent_result` 事件（含 `agentName/index/role/status/durationMs/chars/preview`）。

注意：阶段芯片只在生成过程中显示，不写入会话历史；历史消息只保留最终答复。

## 5. 对话内容保存为文件

对话里直接说"把这些数据保存成 Excel / Word / TXT"，模型会调用保存工具生成真实文件，答复下方出现可点击下载的文件卡片。

| 工具 | 产物 | 说明 |
| --- | --- | --- |
| `save_excel_workbook` | `.xlsx` | 入参 `sheetsJson`：`[{"name":"预算","headers":["项目","金额"],"rows":[["A",120]]}]`，表头加粗、冻结首行、列宽自适应 |
| `save_word_document` | `.docx` | 入参为 Markdown：支持 `#` 标题、列表、`| 表格 |`、代码块、**粗体** |
| `save_text_file` | `.txt/.md/.csv/.json` | 纯文本类；CSV 带 UTF-8 BOM，Excel 打开不乱码 |

- 文件与上传附件共用下载接口 `GET /api/chat/files/{id}`（按 userId 校验），同属 `chat.files.ttl-days`（默认 7 天），删除会话时一并清理。
- 生成结果会写进助手消息的 `outputs` 字段，刷新或切换会话后仍能下载；流式过程中通过 `file_created` 事件（`fileId/name/size/ext/downloadUrl`）推给前端。
- 兜底：用户明确说"保存/导出成 Word/TXT/Excel"但模型没调用工具时，收尾会把答复自动转成文件（`chat.files.export-fallback-enabled`，默认开；Excel/CSV 仅当正文含 Markdown 表格时生成）。
- 限制：`chat.files.export-max-mb`（默认 10MB）、`chat.files.export-max-chars`（默认 20 万字符）、Excel 最多 5000 行；`chat.files.export-enabled: false` 可整体关闭。
- 暂不支持 PDF / PPTX。

示例：

```powershell
Set-Content -Path body.json -Value '{"mode":"chat","content":"把这些数据保存成 Excel：项目A 120万，项目B 80万"}' -Encoding utf8
curl.exe -N -b "dsh_uid=u_demo" -X POST http://127.0.0.1:8091/api/chat/stream `
  -H "Content-Type: application/json" --data-binary "@body.json"
```

响应流中会出现 `tool_start`（`save_excel_workbook`）、`tool_end`、`file_created`，最后 `done`。

## 6. 接入 MCP

管理页 → MCP → 连接 Stdio：

- 名称：`stub`
- 命令：`node`
- 参数：`D:\workspace\AgentChat\scripts\mcp-stub\test-mcp-server.js`

连接后该工具的模型名是 `stub__getNetworkTestData`（命名空间前缀）。

页面添加的 MCP 定义会持久化到 `./data/mcp-servers.json`，应用重启后自动恢复连接（`mcp.persist-file` 可改路径；`mcp.servers` yml 配置同名时以文件为准）。

断线自愈：

- 后台每 `mcp.health-check-interval-ms`（默认 30s）对已连接服务做一次 ping 探测；失败即进入重连。
- 重连按指数退避：初始 `mcp.reconnect-initial-delay-ms`（10s），逐次翻倍，封顶 `mcp.reconnect-max-delay-ms`（300s），带 ±20% 抖动。
- 管理页每 10 秒刷新状态（已连接 / 重连中 / 失败），也可点"重连"立即重试（无需等退避）。`mcp.auto-reconnect-enabled: false` 可关闭自动重连，仅保留手动按钮。
- MCP 服务重启（stdio 子进程被杀、SSE 服务端重启）后会在 1 个健康检查周期内被发现并自动重建客户端，无需重新添加。

## 7. 本地技能

在 `skills/` 下新建目录并放入 `SKILL.md`：

```
skills/
└── my-skill/
    └── SKILL.md
```

60 秒内自动生效（或点击管理页的"刷新"）。

## 8. 常见问题

### 启动时出现 `AGENTS.md not found`

AgentScope Harness 会读取 workspace 下的 `AGENTS.md`。可以忽略，或在 `.agentscope/workspace/AGENTS.md` 放一个说明文件。

### Nacos 日志刷屏

设置 `NACOS_ENABLED=false`。如果必须开启，确认 `nacos.server-addr` 可达；连续失败会触发熔断，只告警一次。

### 聊天返回 401

`AI_API_KEY` 无效或为空。检查 `/api/health` 的 `model` 字段。

### 上传后检索不到

- 确认 `/api/health` 的 `redisSearch` 为 `up`；
- 知识库/对话附件的支持格式见 README「对话内文件」；`.doc`、`.pptx`、图片、扫描件不支持；
- 知识库上传是同步索引，大文件需要等待请求返回；对话附件在独立线程池解析，30 秒超时。

### Maven 无法创建 target 目录

Windows 文件权限问题。确保当前用户对项目目录有写权限；必要时用管理员终端执行一次：

```powershell
icacls D:\workspace\AgentChat /grant "$env:USERNAME:(OI)(CI)F" /T
```
