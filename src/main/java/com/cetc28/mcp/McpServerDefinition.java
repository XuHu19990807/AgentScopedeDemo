package com.cetc28.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server 连接定义 — 描述"如何连接"，与运行时连接状态（ManagedServer）分离。
 *
 * 持久化到 ./data/mcp-servers.json 的即为本对象列表；
 * 应用重启后据此自动重连，运行期健康检查失败后也据此重建客户端。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerDefinition {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_SSE = "sse";

    /** 定义来源 */
    public static final String SOURCE_UI = "ui";
    public static final String SOURCE_YML = "yml";
    public static final String SOURCE_NACOS = "nacos";

    private String name;
    /** stdio | sse */
    private String transport;
    /** stdio：启动命令（须在白名单内） */
    private String command;
    /** stdio：命令参数 */
    private List<String> args = new ArrayList<>();
    /** sse：服务地址 */
    private String url;
    private boolean enabled = true;
    /** ui | yml | nacos；仅 ui 来源写回持久化文件 */
    private String source = SOURCE_UI;

    public McpServerDefinition() {
    }

    public static McpServerDefinition stdio(String name, String command, List<String> args, String source) {
        McpServerDefinition def = new McpServerDefinition();
        def.name = name;
        def.transport = TRANSPORT_STDIO;
        def.command = command;
        def.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
        def.source = source;
        return def;
    }

    public static McpServerDefinition sse(String name, String url, String source) {
        McpServerDefinition def = new McpServerDefinition();
        def.name = name;
        def.transport = TRANSPORT_SSE;
        def.url = url;
        def.source = source;
        return def;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args == null ? new ArrayList<>() : args; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /** 连接目标摘要（供 UI 展示）：SSE 显示 URL，stdio 显示命令+参数 */
    public String targetSummary() {
        if (TRANSPORT_SSE.equals(transport)) {
            return url == null ? "" : url;
        }
        StringBuilder sb = new StringBuilder(command == null ? "" : command);
        if (args != null) {
            for (String a : args) sb.append(' ').append(a);
        }
        return sb.toString();
    }

    /** 基本合法性：name 非空且 transport 对应的必填字段齐备 */
    public boolean isValid() {
        if (name == null || name.isBlank()) return false;
        if (TRANSPORT_STDIO.equals(transport)) return command != null && !command.isBlank();
        if (TRANSPORT_SSE.equals(transport)) return url != null && !url.isBlank();
        return false;
    }
}
