package com.github.guoyyit.meshgenerationjava.web.dto;

import java.util.List;

/**
 * 粗化请求体：elementIds 为空/null 表示整网粗化。
 */
public record CoarsenRequest(List<Long> elementIds) {
}
