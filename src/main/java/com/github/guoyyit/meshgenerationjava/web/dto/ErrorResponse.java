package com.github.guoyyit.meshgenerationjava.web.dto;

import java.util.Map;

/**
 * 统一错误响应：错误码 + 可读信息 + 结构化明细。
 */
public record ErrorResponse(String code, String message, Map<String, Object> details) {
}
