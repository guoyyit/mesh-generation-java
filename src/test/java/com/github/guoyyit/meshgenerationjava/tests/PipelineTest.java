package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationResult;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.MeshGenerationPipeline;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.PipelineHook;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityReport;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTest {

    private static GenerationResult run(PlanarRegion region, QualityThresholds t,
                                        long seed, int maxSteps) {
        return MeshGenerationPipeline.run(
                new GenerationRequest(region, t, seed, maxSteps), PipelineHook.NOOP);
    }

    @Test
    void maxEdgeLengthThresholdIsEnforced() {
        PlanarRegion region = MeshTestSupport.square(4.0);
        QualityThresholds t = new QualityThresholds(null, 0.5, null, null);
        GenerationResult result = run(region, t, 0L, 30);
        assertTrue(result.qualitySatisfied(),
                "violations must be empty: " + result.qualityReport().violations());
        assertTrue(result.qualityReport().maxEdgeLength() <= 0.5 + 1e-12);
        MeshTestSupport.assertConsistent(result.mesh());
        MeshTestSupport.assertAreaConserved(
                com.github.guoyyit.meshgenerationjava.mesh.model.Mesh.fromSnapshot(result.mesh()),
                region);
    }

    @Test
    void minAngleThresholdDrivesRefinement() {
        PlanarRegion region = MeshTestSupport.square(4.0);
        QualityThresholds t = new QualityThresholds(30.0, null, null, null);
        GenerationResult result = run(region, t, 0L, 30);
        assertTrue(result.qualitySatisfied(),
                "violations: " + result.qualityReport().violations());
        assertTrue(result.qualityReport().minAngleDeg() >= 30.0 - 1e-9);
    }

    @Test
    void minAngleThirtyDegreesAchievableWithHole() {
        // 圆心插入 + 翻边使 30° 阈值在带孔区域上真实可达
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(30.0, null, null, null);
        GenerationResult result = run(region, t, 42L, 30);
        assertTrue(result.qualitySatisfied(),
                "violations: " + result.qualityReport().violations());
        assertTrue(result.qualityReport().minAngleDeg() >= 30.0 - 1e-9);
        MeshTestSupport.assertConsistent(result.mesh());
        MeshTestSupport.assertBoundaryPreserved(
                com.github.guoyyit.meshgenerationjava.mesh.model.Mesh.fromSnapshot(result.mesh()),
                region);
    }

    @Test
    void combinedThresholdsAllSatisfied() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(25.0, 1.0, null, 0.8);
        GenerationResult result = run(region, t, 7L, 40);
        assertTrue(result.qualitySatisfied(),
                "violations: " + result.qualityReport().violations());
        QualityReport q = result.qualityReport();
        assertTrue(q.minAngleDeg() >= 25.0 - 1e-9);
        assertTrue(q.maxEdgeLength() <= 1.0 + 1e-9);
        assertTrue(q.maxArea() <= 0.8 + 1e-9);
        MeshTestSupport.assertConsistent(result.mesh());
    }

    @Test
    void coarsenRevertsInsertionEvents() {
        // 角度细化产生圆心插入事件；整网粗化应回滚到三角化（含翻边前）规模
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(30.0, null, null, null);
        GenerationResult refined = run(region, t, 42L, 30);
        assertTrue(refined.qualitySatisfied());

        com.github.guoyyit.meshgenerationjava.mesh.model.Mesh mesh =
                com.github.guoyyit.meshgenerationjava.mesh.model.Mesh.fromSnapshot(refined.mesh());
        int reverted = com.github.guoyyit.meshgenerationjava.mesh.refine.MeshCoarsener.coarsen(
                mesh, null, QualityThresholds.none());
        assertTrue(reverted > 0);
        MeshTestSupport.assertConsistent(mesh);
        MeshTestSupport.assertBoundaryPreserved(mesh, region);
        // 回滚到耳切三角化的单元数（翻边也被逆序回滚）
        com.github.guoyyit.meshgenerationjava.mesh.model.Mesh earClipOnly =
                com.github.guoyyit.meshgenerationjava.mesh.triangulate.EarClippingTriangulator
                        .triangulate(
                                com.github.guoyyit.meshgenerationjava.mesh.validate.RegionValidator
                                        .validate(region), 42L);
        assertEquals(earClipOnly.elements().size(), mesh.elements().size());
    }

    @Test
    void maxAreaThresholdIsEnforced() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(null, null, null, 0.25);
        GenerationResult result = run(region, t, 3L, 40);
        assertTrue(result.qualitySatisfied(),
                "violations: " + result.qualityReport().violations());
        assertTrue(result.qualityReport().maxArea() <= 0.25 + 1e-12);
        MeshTestSupport.assertConsistent(result.mesh());
    }

    @Test
    void impossibleThresholdIsReportedNotSilenced() {
        PlanarRegion region = MeshTestSupport.square(2.0);
        // 最小角 59 度对二分细分不可达：必须显式报告越界而不是静默通过
        QualityThresholds t = new QualityThresholds(59.0, null, null, null);
        GenerationResult result = run(region, t, 0L, 6);
        assertFalse(result.qualitySatisfied());
        assertFalse(result.qualityReport().violations().isEmpty());
        // 越界记录必须带定位信息
        var v = result.qualityReport().violations().get(0);
        assertEquals("minAngle", v.metric());
        assertTrue(v.actual() < v.bound());
    }

    @Test
    void sameSeedSameResultAcrossRuns() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(25.0, 1.0, null, null);
        GenerationResult a = run(region, t, 99L, 20);
        GenerationResult b = run(region, t, 99L, 20);
        assertEquals(a.mesh(), b.mesh(), "mesh snapshots must be identical");
        assertEquals(a.qualityReport(), b.qualityReport());
        assertEquals(a.refinementStepsExecuted(), b.refinementStepsExecuted());
    }

    @Test
    void differentSeedsSatisfySameConstraints() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(null, 1.0, null, null);
        for (long seed = 0; seed < 5; seed++) {
            GenerationResult r = run(region, t, seed, 30);
            assertTrue(r.qualitySatisfied(), "seed " + seed + " violations: "
                    + r.qualityReport().violations());
            MeshTestSupport.assertConsistent(r.mesh());
        }
    }

    @Test
    void cancellationStopsPipeline() {
        PlanarRegion region = MeshTestSupport.square(4.0);
        QualityThresholds t = new QualityThresholds(null, 0.05, null, null);
        PipelineHook cancelAfterTriangulation = new PipelineHook() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void afterStep(int step, com.github.guoyyit.meshgenerationjava.mesh.model.Mesh mesh) {
                throw new CancellationException("stop");
            }
        };
        assertThrows(CancellationException.class, () -> MeshGenerationPipeline.run(
                new GenerationRequest(region, t, 0L, 30), cancelAfterTriangulation));
    }

    @Test
    void elementLimitStopsRefinementAndReports() {
        PlanarRegion region = MeshTestSupport.square(4.0);
        QualityThresholds t = new QualityThresholds(null, 0.01, null, null);
        GenerationResult result = MeshGenerationPipeline.run(
                new GenerationRequest(region, t, 0L, 50, 64), PipelineHook.NOOP);
        assertTrue(result.elementLimitReached());
        assertFalse(result.qualitySatisfied(), "unmet thresholds must remain visible");
        assertTrue(result.mesh().elements().size() <= 64 + 4);
        MeshTestSupport.assertConsistent(result.mesh());
    }

    @Test
    void resumeFromCheckpointMatchesFreshRun() {
        PlanarRegion region = MeshTestSupport.squareWithHole(4.0, 2.0);
        QualityThresholds t = new QualityThresholds(null, 0.8, null, null);
        GenerationRequest request = new GenerationRequest(region, t, 77L, 20);

        // 完整跑一遍
        GenerationResult fresh = MeshGenerationPipeline.run(request, PipelineHook.NOOP);

        // 模拟：跑到第 2 步崩溃（保留检查点），再从检查点恢复
        final GenerationResult[] checkpointHolder = new GenerationResult[1];
        final int[] lastStep = new int[1];
        final com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot[] snap =
                new com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot[1];
        PipelineHook crashAtTwo = new PipelineHook() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void afterStep(int step, com.github.guoyyit.meshgenerationjava.mesh.model.Mesh mesh) {
                lastStep[0] = step;
                snap[0] = mesh.snapshot();
                if (step >= 1) {
                    throw new SimulatedCrash();
                }
            }
        };
        assertThrows(SimulatedCrash.class,
                () -> MeshGenerationPipeline.run(request, crashAtTwo));

        GenerationResult resumed = MeshGenerationPipeline.resume(
                request, snap[0], lastStep[0], PipelineHook.NOOP);

        assertEquals(fresh.mesh(), resumed.mesh(),
                "resumed run must equal uninterrupted run (no repeated/skipped steps)");
        assertEquals(fresh.refinementStepsExecuted(), resumed.refinementStepsExecuted());
    }

    private static final class SimulatedCrash extends RuntimeException {
    }
}
