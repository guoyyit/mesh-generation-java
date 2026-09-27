package com.github.guoyyit.meshgenerationjava.mesh.quality;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;

/**
 * 单元质量阈值。值为 null 表示该项不检查。
 * 所有阈值在构造时校验，非法值直接以 INVALID_PARAMETER 拒绝。
 *
 * @param minAngleDeg   最小内角下限（度），合法范围 (0, 60]
 * @param maxEdgeLength 最大边长上限，必须为正
 * @param minArea       单元面积下限，必须为正
 * @param maxArea       单元面积上限，必须为正且大于 minArea
 */
public record QualityThresholds(
        Double minAngleDeg,
        Double maxEdgeLength,
        Double minArea,
        Double maxArea) {

    public static QualityThresholds none() {
        return new QualityThresholds(null, null, null, null);
    }

    public QualityThresholds {
        if (minAngleDeg != null && (minAngleDeg <= 0.0 || minAngleDeg > 60.0 || !Double.isFinite(minAngleDeg))) {
            throw invalid("minAngleDeg", minAngleDeg, "must be in (0, 60]");
        }
        if (maxEdgeLength != null && (maxEdgeLength <= 0.0 || !Double.isFinite(maxEdgeLength))) {
            throw invalid("maxEdgeLength", maxEdgeLength, "must be positive and finite");
        }
        if (minArea != null && (minArea <= 0.0 || !Double.isFinite(minArea))) {
            throw invalid("minArea", minArea, "must be positive and finite");
        }
        if (maxArea != null && (maxArea <= 0.0 || !Double.isFinite(maxArea))) {
            throw invalid("maxArea", maxArea, "must be positive and finite");
        }
        if (minArea != null && maxArea != null && minArea >= maxArea) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER,
                    "minArea must be smaller than maxArea",
                    java.util.Map.of("minArea", minArea, "maxArea", maxArea));
        }
    }

    private static MeshException invalid(String name, Object value, String rule) {
        return new MeshException(MeshErrorCode.INVALID_PARAMETER,
                "invalid quality threshold " + name + ": " + rule,
                java.util.Map.of("parameter", name, "value", String.valueOf(value)));
    }

    public boolean isEmpty() {
        return minAngleDeg == null && maxEdgeLength == null && minArea == null && maxArea == null;
    }
}
