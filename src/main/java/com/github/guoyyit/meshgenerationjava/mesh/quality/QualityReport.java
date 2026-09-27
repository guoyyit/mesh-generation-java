package com.github.guoyyit.meshgenerationjava.mesh.quality;

import java.util.List;

/**
 * 整网质量报告。violations 非空时 satisfied=false，
 * 调用方必须显式处理（越界不得被静默忽略）。
 */
public record QualityReport(
        int elementCount,
        double minAngleDeg,
        double maxEdgeLength,
        double minArea,
        double maxArea,
        double minRadiusRatio,
        List<QualityViolation> violations) {

    public boolean satisfied() {
        return violations.isEmpty();
    }
}
