package com.github.guoyyit.meshgenerationjava.mesh.quality;

/**
 * 一条质量越界记录，携带定位信息，绝不静默丢弃。
 */
public record QualityViolation(
        long elementId,
        String metric,
        double actual,
        double bound,
        double centroidX,
        double centroidY) {
}
