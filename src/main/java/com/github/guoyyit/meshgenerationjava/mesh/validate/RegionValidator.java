package com.github.guoyyit.meshgenerationjava.mesh.validate;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平面区域输入校验。所有拒绝均通过 {@link MeshException} 抛出可区分错误码，
 * 绝不静默忽略问题点。
 */
public final class RegionValidator {

    private RegionValidator() {
    }

    /** 校验区域并返回方向规整后的区域（外 CCW、内孔 CW）。 */
    public static PlanarRegion validate(PlanarRegion raw) {
        if (raw == null || raw.outer() == null) {
            throw new MeshException(MeshErrorCode.INVALID_POLYGON, "outer boundary is missing");
        }
        List<Point2> outer = raw.outer();
        if (outer.size() < 3) {
            throw new MeshException(MeshErrorCode.INVALID_POLYGON,
                    "outer boundary needs at least 3 points",
                    Map.of("pointCount", outer.size()));
        }
        for (List<Point2> hole : raw.holes()) {
            if (hole == null || hole.size() < 3) {
                throw new MeshException(MeshErrorCode.INVALID_POLYGON,
                        "hole boundary needs at least 3 points");
            }
        }
        rejectNonFinite(0, outer);
        checkDuplicates(0, outer);
        rejectDegenerate(0, outer);
        rejectSelfIntersection(0, outer);

        for (int h = 0; h < raw.holes().size(); h++) {
            List<Point2> hole = raw.holes().get(h);
            int boundaryId = h + 1;
            rejectNonFinite(boundaryId, hole);
            checkDuplicates(boundaryId, hole);
            rejectDegenerate(boundaryId, hole);
            rejectSelfIntersection(boundaryId, hole);
        }

        // 规整方向后再做跨边界检查，保证点序假设一致。
        PlanarRegion normalized = raw.normalized();
        List<Point2> nOuter = normalized.outer();

        for (int h = 0; h < normalized.holes().size(); h++) {
            List<Point2> hole = normalized.holes().get(h);
            int boundaryId = h + 1;
            for (int i = 0; i < hole.size(); i++) {
                Point2 p = hole.get(i);
                int loc = GeometryUtils.pointInPolygon(p, nOuter);
                if (loc == -1) {
                    throw new MeshException(MeshErrorCode.HOLE_OUTSIDE_BOUNDARY,
                            "hole vertex is outside the outer boundary",
                            Map.of("holeIndex", h, "vertexIndex", i, "x", p.x(), "y", p.y()));
                }
                if (loc == 0) {
                    throw new MeshException(MeshErrorCode.INTERSECTING_HOLE,
                            "hole vertex lies on the outer boundary",
                            Map.of("holeIndex", h, "vertexIndex", i, "x", p.x(), "y", p.y()));
                }
            }
            if (edgeIntersectsBoundary(hole, nOuter, h, 0, false)) {
                throw new MeshException(MeshErrorCode.INTERSECTING_HOLE,
                        "hole edge intersects or touches the outer boundary",
                        Map.of("holeIndex", h));
            }
        }
        for (int h1 = 0; h1 < normalized.holes().size(); h1++) {
            for (int h2 = h1 + 1; h2 < normalized.holes().size(); h2++) {
                if (edgeIntersectsBoundary(normalized.holes().get(h1),
                        normalized.holes().get(h2), h1, h2, true)) {
                    throw new MeshException(MeshErrorCode.INTERSECTING_HOLE,
                            "holes intersect or touch each other",
                            Map.of("holeA", h1, "holeB", h2));
                }
            }
            if (holesOverlap(normalized, h1)) {
                throw new MeshException(MeshErrorCode.INTERSECTING_HOLE,
                        "holes overlap without edge intersection (one contains another)",
                        Map.of("holeIndex", h1));
            }
        }
        return normalized;
    }

    private static void rejectNonFinite(int boundaryId, List<Point2> polygon) {
        for (int i = 0; i < polygon.size(); i++) {
            Point2 p = polygon.get(i);
            if (p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
                throw new MeshException(MeshErrorCode.INVALID_POLYGON,
                        "boundary contains non-finite or null coordinate",
                        Map.of("boundaryId", boundaryId, "vertexIndex", i));
            }
        }
    }

    private static void checkDuplicates(int boundaryId, List<Point2> polygon) {
        Map<Point2, Integer> seen = new HashMap<>();
        for (int i = 0; i < polygon.size(); i++) {
            Point2 p = polygon.get(i);
            for (Map.Entry<Point2, Integer> e : seen.entrySet()) {
                if (e.getKey().sameCoordinates(p)) {
                    throw new MeshException(MeshErrorCode.DUPLICATE_POINT,
                            "duplicate coordinates on the same boundary",
                            Map.of("boundaryId", boundaryId,
                                    "firstVertexIndex", e.getValue(), "secondVertexIndex", i,
                                    "x", p.x(), "y", p.y()));
                }
            }
            seen.put(p, i);
        }
    }

    private static void rejectDegenerate(int boundaryId, List<Point2> polygon) {
        double area = Math.abs(GeometryUtils.signedArea(polygon));
        if (area < GeometryUtils.MIN_POLYGON_AREA) {
            throw new MeshException(MeshErrorCode.DEGENERATE_POLYGON,
                    "polygon has (near) zero signed area",
                    Map.of("boundaryId", boundaryId, "area", area));
        }
    }

    private static void rejectSelfIntersection(int boundaryId, List<Point2> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Point2 a = polygon.get(i);
            Point2 b = polygon.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                if (j == i || j == (i + 1) % n || i == (j + 1) % n) {
                    continue;
                }
                Point2 c = polygon.get(j);
                Point2 d = polygon.get((j + 1) % n);
                if (GeometryUtils.segmentsTouch(a, b, c, d)) {
                    throw new MeshException(MeshErrorCode.SELF_INTERSECTING_BOUNDARY,
                            "non-adjacent boundary edges intersect or touch",
                            Map.of("boundaryId", boundaryId,
                                    "edgeIndexA", i, "edgeIndexB", j));
                }
            }
        }
    }

    private static boolean edgeIntersectsBoundary(List<Point2> polyA, List<Point2> polyB,
                                                  int idA, int idB, boolean useDetail) {
        int n = polyA.size();
        int m = polyB.size();
        for (int i = 0; i < n; i++) {
            Point2 a = polyA.get(i);
            Point2 b = polyA.get((i + 1) % n);
            for (int j = 0; j < m; j++) {
                Point2 c = polyB.get(j);
                Point2 d = polyB.get((j + 1) % m);
                if (GeometryUtils.segmentsTouch(a, b, c, d)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 孔互相包含：边不相交但一个孔完全落在另一个孔内（环必须互不包含）。 */
    private static boolean holesOverlap(PlanarRegion region, int h) {
        Point2 probe = region.holes().get(h).get(0);
        for (int other = 0; other < region.holes().size(); other++) {
            if (other == h) {
                continue;
            }
            if (GeometryUtils.pointInPolygon(probe, region.holes().get(other)) == 1) {
                return true;
            }
        }
        return false;
    }
}
