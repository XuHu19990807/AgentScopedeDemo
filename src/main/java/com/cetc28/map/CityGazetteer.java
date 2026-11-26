package com.cetc28.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地城市坐标库。模型只负责给出城市名，坐标一律由这里解析，避免模型编造经纬度。
 *
 * <p>数据文件 {@code classpath:data/cities.json}，字段：name/province/lon/lat/level/aliases。
 * 支持“杭州市/杭州/Beijing”等常见写法；同名城市可用省份消歧（如省份参数传“浙江”）。
 */
@Component
public class CityGazetteer {

    private static final Logger log = LoggerFactory.getLogger(CityGazetteer.class);

    private final ObjectMapper mapper;
    private final Map<String, List<CityEntry>> byName = new HashMap<>();
    private final Map<String, List<CityEntry>> byAlias = new HashMap<>();
    private volatile int total = 0;

    public CityGazetteer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        byName.clear();
        byAlias.clear();
        total = 0;
        try {
            ClassPathResource resource = new ClassPathResource("data/cities.json");
            if (!resource.exists()) {
                log.warn("城市坐标库不存在：classpath:data/cities.json，地图城市解析将不可用");
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                List<CityEntry> entries = mapper.readValue(in, new TypeReference<List<CityEntry>>() {
                });
                if (entries == null) return;
                for (CityEntry entry : entries) {
                    if (entry == null || isBlank(entry.getName())
                            || entry.getLon() == null || entry.getLat() == null) {
                        continue;
                    }
                    index(entry);
                    total++;
                }
                log.info("城市坐标库加载完成：{} 条", total);
            }
        } catch (Exception e) {
            log.warn("城市坐标库加载失败：{}", e.getMessage());
            byName.clear();
            byAlias.clear();
            total = 0;
        }
    }

    private void index(CityEntry entry) {
        add(byName, normalize(entry.getName()), entry);
        if (entry.getAliases() != null) {
            for (String alias : entry.getAliases()) {
                if (!isBlank(alias)) add(byAlias, normalize(alias), entry);
            }
        }
    }

    private void add(Map<String, List<CityEntry>> index, String key, CityEntry entry) {
        if (key.isEmpty()) return;
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
    }

    public CityEntry resolve(String name) {
        return resolve(name, null);
    }

    /**
     * @param name     城市名/别名，允许带“市”
     * @param province 可选省份，用于同名消歧
     * @return 唯一命中返回条目；未命中或多义返回 null
     */
    public CityEntry resolve(String name, String province) {
        String key = normalize(name);
        if (key.isEmpty()) return null;

        List<CityEntry> candidates = byName.get(key);
        if (candidates == null || candidates.isEmpty()) {
            candidates = byAlias.get(key);
        }
        if (candidates == null || candidates.isEmpty()) return null;

        String provinceKey = normalizeProvince(province);
        if (!provinceKey.isEmpty()) {
            List<CityEntry> filtered = candidates.stream()
                    .filter(e -> provinceMatches(e.getProvince(), provinceKey))
                    .toList();
            if (filtered.isEmpty()) return null;
            candidates = filtered;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public int size() {
        return total;
    }

    private boolean provinceMatches(String entryProvince, String queryProvince) {
        String entryKey = normalizeProvince(entryProvince);
        if (entryKey.isEmpty()) return false;
        return entryKey.equals(queryProvince)
                || entryKey.contains(queryProvince)
                || queryProvince.contains(entryKey);
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return stripSuffix(value);
    }

    private String normalizeProvince(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return stripSuffix(value);
    }

    private String stripSuffix(String value) {
        String result = value;
        for (String suffix : List.of("特别行政区", "自治区", "省", "市")) {
            if (result.length() > suffix.length() && result.endsWith(suffix)) {
                return result.substring(0, result.length() - suffix.length());
            }
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityEntry {
        private String name;
        private String province;
        private Double lon;
        private Double lat;
        /** municipality | new-first-tier | capital | prefecture | international */
        private String level;
        private List<String> aliases;
    }
}
