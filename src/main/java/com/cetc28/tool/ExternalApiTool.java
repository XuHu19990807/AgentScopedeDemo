package com.cetc28.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ExternalApiTool {

    private final RestTemplate restTemplate = new RestTemplate();

    @Tool(description = "调用外部HTTP GET接口获取数据")
    public String callExternalApi(
            @ToolParam(name = "url", description = "API地址") String url) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getBody();
        } catch (Exception e) {
            return "API调用失败: " + e.getMessage();
        }
    }
}
