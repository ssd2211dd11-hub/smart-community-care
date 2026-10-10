package org.imnu.smartcommunitycare.common.api;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查。公开接口，用于确认后端与数据库是否可用。
 *
 * <p>刻意做成"真检查"而不是返回固定字符串：固定字符串只能证明
 * Tomcat 活着，证明不了数据库连得上。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthCheckController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health-check")
    public ApiResponse<String> healthCheck() {
        Integer db = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        // 用双参数版本，明确表达"有业务数据返回"
        return ApiResponse.success("服务与数据库正常", "探测值=" + db);
    }
}
