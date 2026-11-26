package com.cetc28.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WeatherTool {

    private static final Map<String, String> WEATHER_CACHE = new ConcurrentHashMap<>();

    static {
        WEATHER_CACHE.put("北京", "晴, 25°C, 湿度45%, 空气质量良");
        WEATHER_CACHE.put("上海", "多云, 28°C, 湿度60%, 空气质量优");
        WEATHER_CACHE.put("广州", "阵雨, 32°C, 湿度80%, 空气质量轻度污染");
        WEATHER_CACHE.put("深圳", "阴天, 30°C, 湿度70%, 空气质量良");
        WEATHER_CACHE.put("杭州", "晴, 26°C, 湿度50%, 空气质量优");
    }

    @Tool(description = "获取指定城市的天气信息")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称，如 北京、上海") String city) {
        String weather = WEATHER_CACHE.getOrDefault(city, city + "天气信息暂不可用");
        return String.format("城市：%s，天气：%s", city, weather);
    }
}
