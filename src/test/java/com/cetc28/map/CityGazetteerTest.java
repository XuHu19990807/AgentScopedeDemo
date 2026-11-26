package com.cetc28.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityGazetteerTest {

    private static CityGazetteer gazetteer;

    @BeforeAll
    static void setUp() {
        gazetteer = new CityGazetteer(new ObjectMapper());
        gazetteer.load();
    }

    @Test
    void loadsCityData() {
        assertTrue(gazetteer.size() >= 100, "城市库应至少包含 100 条");
    }

    @Test
    void resolvesAllNewFirstTierCities() {
        String[] cities = {
                "成都", "重庆", "杭州", "武汉", "苏州", "西安", "南京",
                "长沙", "郑州", "天津", "合肥", "青岛", "东莞", "宁波", "佛山"
        };
        for (String city : cities) {
            CityGazetteer.CityEntry entry = gazetteer.resolve(city);
            assertNotNull(entry, "未解析城市：" + city);
            assertNotNull(entry.getLon());
            assertNotNull(entry.getLat());
        }
    }

    @Test
    void acceptsCitySuffixAndEnglishAlias() {
        assertEquals("杭州", gazetteer.resolve("杭州市").getName());
        assertEquals("杭州", gazetteer.resolve("Hangzhou").getName());
        assertEquals("北京", gazetteer.resolve("Beijing").getName());
    }

    @Test
    void usesProvinceToDisambiguate() {
        assertEquals("杭州", gazetteer.resolve("杭州", "浙江").getName());
        assertNull(gazetteer.resolve("杭州", "江苏"));
    }

    @Test
    void unknownOrAmbiguousCityReturnsNull() {
        assertNull(gazetteer.resolve("不存在的地方"));
        assertNull(gazetteer.resolve(""));
        assertNull(gazetteer.resolve(null));
    }
}
