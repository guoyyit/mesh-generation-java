package com.github.guoyyit.meshgenerationjava.mesh.model;

import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;

import java.util.List;

/**
 * 带内外边界的平面区域：outer 为外边界，holes 为内孔边界。
 * 边界点序在校验阶段统一规整为：外边界 CCW、内孔 CW。
 */
public record PlanarRegion(List<Point2> outer, List<List<Point2>> holes) {

    public PlanarRegion {
        outer = List.copyOf(outer);
        holes = List.copyOf(holes.stream().map(List::copyOf).toList());
    }

    /** 规整后的区域：边界方向统一（外 CCW、孔 CW）。 */
    public PlanarRegion normalized() {
        List<Point2> out = outer;
        if (com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils.signedArea(out) < 0) {
            out = new java.util.ArrayList<>(out);
            java.util.Collections.reverse(out);
        }
        List<List<Point2>> hs = new java.util.ArrayList<>();
        for (List<Point2> hole : holes) {
            List<Point2> h = hole;
            if (com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils.signedArea(h) > 0) {
                h = new java.util.ArrayList<>(h);
                java.util.Collections.reverse(h);
            }
            hs.add(h);
        }
        return new PlanarRegion(out, hs);
    }
}
