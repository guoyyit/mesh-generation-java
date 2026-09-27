package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.mesh.validate.RegionValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionValidatorTest {

    private static PlanarRegion region(List<Point2> outer, List<Point2>... holes) {
        return new PlanarRegion(outer, List.of(holes));
    }

    private static Point2 p(double x, double y) {
        return new Point2(x, y);
    }

    private static MeshErrorCode codeOf(Runnable r) {
        MeshException e = assertThrows(MeshException.class, r::run);
        return e.getErrorCode();
    }

    @Test
    void rejectsTooFewPoints() {
        assertEquals(MeshErrorCode.INVALID_POLYGON,
                codeOf(() -> RegionValidator.validate(
                        region(List.of(p(0, 0), p(1, 0))))));
    }

    @Test
    void rejectsDuplicatePoints() {
        MeshException e = assertThrows(MeshException.class, () -> RegionValidator.validate(
                region(List.of(p(0, 0), p(1, 0), p(1, 0), p(0, 1)))));
        assertEquals(MeshErrorCode.DUPLICATE_POINT, e.getErrorCode());
        assertTrue(e.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsSelfIntersectingBowtie() {
        // 边 e1(0,0)-(2,0) 与 e3(2,2)-(0.5,-1) 在 (1,0) 处相交，且有向面积非零
        assertEquals(MeshErrorCode.SELF_INTERSECTING_BOUNDARY,
                codeOf(() -> RegionValidator.validate(
                        region(List.of(p(0, 0), p(2, 0), p(2, 2), p(0.5, -1), p(0, 2))))));
    }

    @Test
    void rejectsDegenerateCollinearPolygon() {
        assertEquals(MeshErrorCode.DEGENERATE_POLYGON,
                codeOf(() -> RegionValidator.validate(
                        region(List.of(p(0, 0), p(1, 1), p(2, 2))))));
    }

    @Test
    void rejectsHoleOutsideOuter() {
        assertEquals(MeshErrorCode.HOLE_OUTSIDE_BOUNDARY,
                codeOf(() -> RegionValidator.validate(region(
                        List.of(p(0, 0), p(1, 0), p(1, 1), p(0, 1)),
                        List.of(p(5, 5), p(6, 5), p(6, 6))))));
    }

    @Test
    void rejectsHoleCrossingOuterEdge() {
        // L 形外边界；孔顶点全部在内部，但边 (3,1)-(1.5,3) 穿过凹口边界
        assertEquals(MeshErrorCode.INTERSECTING_HOLE,
                codeOf(() -> RegionValidator.validate(region(
                        List.of(p(0, 0), p(4, 0), p(4, 2), p(2, 2), p(2, 4), p(0, 4)),
                        List.of(p(1, 1), p(3, 1), p(1.5, 3))))));
    }

    @Test
    void rejectsHoleTouchingOuterBoundary() {
        assertEquals(MeshErrorCode.INTERSECTING_HOLE,
                codeOf(() -> RegionValidator.validate(region(
                        List.of(p(0, 0), p(4, 0), p(4, 4), p(0, 4)),
                        List.of(p(1, 0), p(2, 0), p(2, 1), p(1, 1))))));
    }

    @Test
    void rejectsOverlappingHoles() {
        assertEquals(MeshErrorCode.INTERSECTING_HOLE,
                codeOf(() -> RegionValidator.validate(region(
                        List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10)),
                        List.of(p(1, 1), p(5, 1), p(5, 5), p(1, 5)),
                        List.of(p(3, 3), p(7, 3), p(7, 7), p(3, 7))))));
    }

    @Test
    void normalizesOrientation() {
        PlanarRegion normalized = RegionValidator.validate(region(
                List.of(p(0, 0), p(0, 4), p(4, 4), p(4, 0)),   // CW 输入
                List.of(p(1, 1), p(1, 2), p(2, 2), p(2, 1)))); // CCW 孔输入
        assertTrue(GeometryUtils.signedArea(normalized.outer()) > 0, "outer must be CCW");
        assertTrue(GeometryUtils.signedArea(normalized.holes().get(0)) < 0, "hole must be CW");
    }

    @Test
    void rejectsInvalidThresholds() {
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new QualityThresholds(61.0, null, null, null)));
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new QualityThresholds(0.0, null, null, null)));
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new QualityThresholds(null, -1.0, null, null)));
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new QualityThresholds(null, null, 2.0, 1.0)));
    }

    @Test
    void rejectsInvalidGenerationParams() {
        PlanarRegion ok = region(List.of(p(0, 0), p(1, 0), p(1, 1), p(0, 1)));
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new GenerationRequest(ok, QualityThresholds.none(), 0L, -1)));
        assertEquals(MeshErrorCode.INVALID_PARAMETER,
                codeOf(() -> new GenerationRequest(ok, QualityThresholds.none(), 0L, 1, 1)));
    }

    @Test
    void acceptsValidRegionWithHole() {
        PlanarRegion normalized = RegionValidator.validate(region(
                List.of(p(0, 0), p(4, 0), p(4, 4), p(0, 4)),
                List.of(p(1, 1), p(1, 2), p(2, 2), p(2, 1))));
        assertEquals(4, normalized.outer().size());
        assertEquals(1, normalized.holes().size());
    }
}
