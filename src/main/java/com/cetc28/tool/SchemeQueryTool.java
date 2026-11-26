package com.cetc28.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 方案查询工具 — 查询方案序号和方案详细信息
 */
@Component
public class SchemeQueryTool {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @Tool(description = "根据方案名称查询网络规划方案的序号（yaxh），用于后续查询方案详细信息")
    public String findByFileName(
            @ToolParam(name = "fileName", description = "方案名称（如 北京-上海网络规划方案）") String fileName) {
        try {
            String url = "http://localhost:8091/findByPage?fileName="
                    + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            Map<String, Object> data = objectMapper.readValue(response.getBody(), Map.class);

            if (data != null && Integer.valueOf(1).equals(data.get("res"))) {
                Map<String, Object> obj = (Map<String, Object>) data.get("obj");
                if (obj != null) {
                    List<Map<String, Object>> result = (List<Map<String, Object>>) obj.get("result");
                    if (result != null && !result.isEmpty()) {
                        String yaxh = String.valueOf(result.get(0).get("yaxh"));
                        return String.format("方案名称: %s\n方案序号(yaxh): %s", fileName, yaxh);
                    }
                }
            }
            return "查询失败: 响应格式异常";
        } catch (Exception e) {
            return "查询方案序号失败: " + e.getMessage();
        }
    }
}
