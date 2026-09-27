package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invalid input is rejected with a classified reason and never crashes the service. */
class ValidationTest {

    private final MeshGenerator generator = new MeshGenerator();

    private static ErrorCode expectReject(Region region, MeshGenerationOptions options) {
        MeshValidationException ex = assertThrows(MeshValidationException.class,
                () -> new MeshGenerator().generate(region, options));
        return ex.getCode();
    }

    private static MeshGenerationOptions defaults() {
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setMinAngleDeg(3.0);
        return options;
    }

    @Test
    void selfIntersectingBoundaryRejected() {
        Region bowtie = new Region(List.of(
                new Point2D(0, 0), new Point2D(2, 2),
                new Point2D(2, 0), new Point2D(0, 2)), List.of());
        assertEquals(ErrorCode.SELF_INTERSECTING_BOUNDARY, expectReject(bowtie, defaults()));
    }

    @Test
    void duplicatePointsRejected() {
        Region dup = new Region(List.of(
                new Point2D(0, 0), new Point2D(1, 0), new Point2D(1, 1),
                new Point2D(0, 0)), List.of());
        assertEquals(ErrorCode.DUPLICATE_POINT, expectReject(dup, defaults()));
    }

    @Test
    void degenerateLoopRejected() {
        Region collinear = new Region(List.of(
                new Point2D(0, 0), new Point2D(1, 0), new Point2D(2, 0)), List.of());
        assertEquals(ErrorCode.DEGENERATE_GEOMETRY, expectReject(collinear, defaults()));
    }

    @Test
    void tooFewPointsRejected() {
        Region line = new Region(List.of(new Point2D(0, 0), new Point2D(1, 0)), List.of());
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(line, defaults()));
    }

    @Test
    void nonFiniteCoordinateRejected() {
        Region nan = new Region(List.of(
                new Point2D(0, 0), new Point2D(Double.NaN, 0), new Point2D(0, 1)), List.of());
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(nan, defaults()));
    }

    @Test
    void holeOutsideOuterRejected() {
        Region region = new Region(List.of(
                new Point2D(0, 0), new Point2D(2, 0), new Point2D(2, 2), new Point2D(0, 2)),
                List.of(List.of(new Point2D(5, 5), new Point2D(6, 5), new Point2D(5, 6))));
        assertEquals(ErrorCode.HOLE_OUTSIDE_BOUNDARY, expectReject(region, defaults()));
    }

    @Test
    void holeTouchingOuterRejected() {
        Region region = new Region(List.of(
                new Point2D(0, 0), new Point2D(4, 0), new Point2D(4, 4), new Point2D(0, 4)),
                List.of(List.of(new Point2D(1, 0), new Point2D(2, 0), new Point2D(1.5, 1))));
        assertEquals(ErrorCode.HOLE_INTERSECTS_BOUNDARY, expectReject(region, defaults()));
    }

    @Test
    void overlappingHolesRejected() {
        Region region = new Region(List.of(
                new Point2D(0, 0), new Point2D(6, 0), new Point2D(6, 6), new Point2D(0, 6)),
                List.of(
                        List.of(new Point2D(1, 1), new Point2D(3, 1), new Point2D(2, 3)),
                        List.of(new Point2D(2, 2), new Point2D(4, 2), new Point2D(3, 4))));
        assertEquals(ErrorCode.OVERLAPPING_HOLES, expectReject(region, defaults()));
    }

    @Test
    void illegalOptionsRejected() {
        Region square = new Region(List.of(
                new Point2D(0, 0), new Point2D(1, 0), new Point2D(1, 1), new Point2D(0, 1)),
                List.of());
        MeshGenerationOptions bad1 = defaults();
        bad1.setMaxEdgeLength(-1.0);
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(square, bad1));

        MeshGenerationOptions bad2 = defaults();
        bad2.setMinAngleDeg(70.0);
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(square, bad2));

        MeshGenerationOptions bad3 = defaults();
        bad3.setInteriorJitter(1.5);
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(square, bad3));

        MeshGenerationOptions bad4 = defaults();
        bad4.setMaxRefinementSteps(0);
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(square, bad4));

        MeshGenerationOptions bad5 = defaults();
        bad5.setTimeoutMillis(-5);
        assertEquals(ErrorCode.INVALID_PARAMETER, expectReject(square, bad5));
    }

    @Test
    void unachievableQualityFailsLoudlyWhenRequested() {
        Region square = new Region(List.of(
                new Point2D(0, 0), new Point2D(1, 0), new Point2D(1, 1), new Point2D(0, 1)),
                List.of());
        MeshGenerationOptions options = defaults();
        options.setMinAngleDeg(59.0); // impossible for a triangulated square
        options.setFailOnQualityViolation(true);
        assertEquals(ErrorCode.QUALITY_VIOLATION, expectReject(square, options));
    }

    @Test
    void serviceSurvivesBadInput() {
        // A burst of invalid requests must not affect subsequent valid ones.
        Region bad = new Region(List.of(
                new Point2D(0, 0), new Point2D(2, 2),
                new Point2D(2, 0), new Point2D(0, 2)), List.of());
        for (int i = 0; i < 3; i++) {
            assertThrows(MeshValidationException.class, () -> generator.generate(bad, defaults()));
        }
        Region good = new Region(List.of(
                new Point2D(0, 0), new Point2D(1, 0), new Point2D(1, 1), new Point2D(0, 1)),
                List.of());
        assertTrue(generator.generate(good, defaults()).mesh().checkConsistency().isEmpty());
    }
}
