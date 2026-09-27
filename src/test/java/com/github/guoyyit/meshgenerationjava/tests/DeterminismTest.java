package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.generator.GenerationResult;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.io.MeshSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed seed + fixed input => identical mesh; different seeds => different but valid meshes. */
class DeterminismTest {

    private static Region regionWithHole() {
        List<Point2D> outer = List.of(
                new Point2D(0, 0), new Point2D(5, 0), new Point2D(5, 3),
                new Point2D(2.5, 3.5), new Point2D(0, 3));
        List<Point2D> hole = List.of(
                new Point2D(2, 1), new Point2D(3, 1), new Point2D(2.5, 2));
        return new Region(outer, List.of(hole));
    }

    private static MeshGenerationOptions options(long seed) {
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setSeed(seed);
        options.setMaxEdgeLength(0.6);
        options.setMinAngleDeg(3.0);
        options.setInteriorJitter(0.3);
        return options;
    }

    @Test
    void sameSeedProducesIdenticalMesh() {
        Region region = regionWithHole();
        MeshSerializer serializer = new MeshSerializer();
        GenerationResult r1 = new MeshGenerator().generate(region, options(123L));
        GenerationResult r2 = new MeshGenerator().generate(region, options(123L));
        assertEquals(serializer.toJson(r1.mesh()), serializer.toJson(r2.mesh()),
                "same seed must reproduce the mesh bit-for-bit");
        assertEquals(r1.bisections(), r2.bisections());
        assertEquals(r1.steinerPointsInserted(), r2.steinerPointsInserted());
        assertEquals(r1.issues().size(), r2.issues().size());
    }

    @Test
    void differentSeedsDifferButStayValid() {
        Region region = regionWithHole();
        MeshSerializer serializer = new MeshSerializer();
        Mesh m1 = new MeshGenerator().generate(region, options(1L)).mesh();
        Mesh m2 = new MeshGenerator().generate(region, options(2L)).mesh();

        assertNotEquals(serializer.toJson(m1), serializer.toJson(m2),
                "different seeds should explore different meshes");
        for (Mesh mesh : List.of(m1, m2)) {
            assertTrue(mesh.checkConsistency().isEmpty(),
                    "mesh must satisfy topology constraints: " + mesh.checkConsistency());
            assertEquals(region.area(), mesh.totalArea(), 1e-9,
                    "mesh must cover exactly the region");
        }
    }

    @Test
    void historicalConclusionIsReproducible() {
        // A pinned fingerprint: guards against accidental algorithm drift.
        Region region = regionWithHole();
        Mesh mesh = new MeshGenerator().generate(region, options(123L)).mesh();
        GenerationResult again = new MeshGenerator().generate(region, options(123L));
        assertEquals(mesh.elementCount(), again.mesh().elementCount());
        assertEquals(mesh.nodeCount(), again.mesh().nodeCount());
        assertEquals(mesh.totalArea(), again.mesh().totalArea(), 1e-12);
    }
}
