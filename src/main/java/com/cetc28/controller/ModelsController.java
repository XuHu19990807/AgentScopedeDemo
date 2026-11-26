package com.cetc28.controller;

import com.cetc28.modelconfig.ModelConfig;
import com.cetc28.modelconfig.ModelConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话侧的模型目录（只读）：前端用它渲染顶栏的模型切换器。
 *
 * <p>刻意不暴露 base-url / 密钥，也不需要 {@code X-Admin-Token}，因为普通用户就要能切换。
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelConfigStore store;

    public ModelsController(ModelConfigStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list() {
        ModelConfig config = store.current();
        String defaultId = config.defaultChat().map(p -> p.id()).orElse(null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("defaultId", defaultId);
        view.put("models", config.toPublicModels());
        return view;
    }
}
