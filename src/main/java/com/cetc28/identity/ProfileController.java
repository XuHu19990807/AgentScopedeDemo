package com.cetc28.identity;

import com.cetc28.chat.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前用户的画像 / 偏好查询（非管理接口，按请求身份隔离）。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserProfileService profileService;

    public ProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public Map<String, Object> profile(HttpServletRequest request) {
        return profileService.profileOf(UserContext.from(request));
    }

    @DeleteMapping("/topics/{keyword}")
    public Map<String, Object> deleteTopic(@PathVariable String keyword, HttpServletRequest request) {
        String userId = UserContext.from(request);
        profileService.deleteTopic(userId, keyword);
        return Map.of("ok", true);
    }
}
