package com.github.guoyyit.meshgenerationjava.mesh.quality;

/**
 * 单个单元的质量度量。
 *
 * @param elementId    单元 id
 * @param area         面积
 * @param edgeLengths  三条边长
 * @param anglesDeg    三个内角（度）
 * @param minAngleDeg  最小内角（度）
 * @param maxEdgeLength 最大边长
 * @param radiusRatio  2*r_in/r_circ，等边三角形为 1，越接近 0 越差
 * @param centroidX    形心 x（定位信息）
 * @param centroidY    形心 y（定位信息）
 */
public record ElementQuality(
        long elementId,
        double area,
        double[] edgeLengths,
        double[] anglesDeg,
        double minAngleDeg,
        double maxEdgeLength,
        double radiusRatio,
        double centroidX,
        double centroidY) {
}
