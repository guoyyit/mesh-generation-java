package com.github.guoyyit.meshgenerationjava.web.dto;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;

import java.util.List;

/**
 * 局部细分请求体：对指定单元细分 levels 轮。
 */
public record RefineRequest(List<Long> elementIds, Integer levels) {

    public List<Long> ids() {
        if (elementIds == null || elementIds.isEmpty()) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER,
                    "elementIds must be a non-empty list");
        }
        return elementIds;
    }

    public int levelCount() {
        return levels == null ? 1 : levels;
    }
}
