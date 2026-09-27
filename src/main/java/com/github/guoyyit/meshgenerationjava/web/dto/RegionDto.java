package com.github.guoyyit.meshgenerationjava.web.dto;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 区域请求体：outer 为 [[x,y],...]，holes 为 [[[x,y],...],...]（可空）。
 */
public record RegionDto(double[][] outer, double[][][] holes) {

    public PlanarRegion toRegion() {
        if (outer == null) {
            throw new MeshException(MeshErrorCode.INVALID_POLYGON, "outer boundary is required");
        }
        List<Point2> outerPoints = toPoints(outer, "outer");
        List<List<Point2>> holeList = new ArrayList<>();
        if (holes != null) {
            for (int h = 0; h < holes.length; h++) {
                holeList.add(toPoints(holes[h], "holes[" + h + "]"));
            }
        }
        return new PlanarRegion(outerPoints, holeList);
    }

    private static List<Point2> toPoints(double[][] raw, String name) {
        if (raw == null) {
            throw new MeshException(MeshErrorCode.INVALID_POLYGON,
                    "boundary " + name + " is null", Map.of("boundary", name));
        }
        List<Point2> points = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            double[] p = raw[i];
            if (p == null || p.length != 2) {
                throw new MeshException(MeshErrorCode.INVALID_POLYGON,
                        "boundary point must be [x, y]",
                        Map.of("boundary", name, "vertexIndex", i));
            }
            points.add(new Point2(p[0], p[1]));
        }
        return points;
    }
}
