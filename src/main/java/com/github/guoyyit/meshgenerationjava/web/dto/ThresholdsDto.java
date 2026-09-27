package com.github.guoyyit.meshgenerationjava.web.dto;

import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;

/**
 * 质量阈值请求体：字段缺省表示该项不检查。
 */
public record ThresholdsDto(Double minAngleDeg, Double maxEdgeLength,
                            Double minArea, Double maxArea) {

    public QualityThresholds toThresholds() {
        if (minAngleDeg == null && maxEdgeLength == null && minArea == null && maxArea == null) {
            return QualityThresholds.none();
        }
        return new QualityThresholds(minAngleDeg, maxEdgeLength, minArea, maxArea);
    }
}
