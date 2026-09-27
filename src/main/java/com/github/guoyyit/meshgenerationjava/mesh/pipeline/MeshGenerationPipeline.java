package com.github.guoyyit.meshgenerationjava.mesh.pipeline;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityChecker;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityReport;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityViolation;
import com.github.guoyyit.meshgenerationjava.mesh.refine.CircumcenterInserter;
import com.github.guoyyit.meshgenerationjava.mesh.refine.MeshRefiner;
import com.github.guoyyit.meshgenerationjava.mesh.triangulate.DelaunayFlipper;
import com.github.guoyyit.meshgenerationjava.mesh.triangulate.EarClippingTriangulator;
import com.github.guoyyit.meshgenerationjava.mesh.validate.RegionValidator;

import java.util.List;

/**
 * 网格生成主流程（纯计算，不涉及线程与持久化）：
 * 输入校验 -> 约束三角化 -> 基于质量阈值的自适应最长边细分 -> 最终一致性校验。
 *
 * <p>恢复语义：每完成一轮细化即由 {@link PipelineHook#afterStep} 落盘检查点；
 * 崩溃重启后从最近检查点继续，细化轮次编号连续，不重复也不跳过。
 */
public final class MeshGenerationPipeline {

    private MeshGenerationPipeline() {
    }

    public static GenerationResult run(GenerationRequest request, PipelineHook hook) {
        hook.checkCancelled();
        Mesh mesh = EarClippingTriangulator.triangulate(
                RegionValidator.validate(request.region()), request.seed());
        DelaunayFlipper.improve(mesh);
        verifyConsistency(mesh, "after triangulation");
        hook.afterStep(-1, mesh);
        return refine(request, mesh, 0, hook);
    }

    /** 从检查点恢复：跳过已完成的 0..checkpointStep 轮，直接继续。 */
    public static GenerationResult resume(GenerationRequest request, MeshSnapshot checkpoint,
                                          int checkpointStep, PipelineHook hook) {
        hook.checkCancelled();
        RegionValidator.validate(request.region());
        Mesh mesh = Mesh.fromSnapshot(checkpoint);
        verifyConsistency(mesh, "resumed checkpoint");
        int startStep = checkpointStep + 1;
        return refine(request, mesh, startStep, hook);
    }

    private static GenerationResult refine(GenerationRequest request, Mesh mesh,
                                           int startStep, PipelineHook hook) {
        QualityThresholds thresholds = request.thresholds();
        int step = startStep;
        boolean limitReached = false;

        while (step < request.maxRefinementSteps()) {
            hook.checkCancelled();
            QualityReport report = QualityChecker.evaluate(mesh, thresholds);
            if (report.violations().isEmpty()) {
                break;
            }
            int processed = 0;
            int splitsThisStep = 0;
            int insertsThisStep = 0;
            for (QualityViolation v : report.violations()) {
                if ((++processed & 0x3FF) == 0) {
                    hook.checkCancelled();
                }
                if (mesh.elements().size() >= request.maxElements()) {
                    limitReached = true;
                    break;
                }
                long id = v.elementId();
                if (!mesh.hasElement(id)) {
                    continue;
                }
                if ("minAngle".equals(v.metric())) {
                    // 角度越界：优先圆心插入（Ruppert/Chew），失败退回最长边二分
                    String action = CircumcenterInserter.tryImprove(mesh, id);
                    if (!"none".equals(action)) {
                        insertsThisStep++;
                    } else if (mesh.hasElement(id)) {
                        splitsThisStep += MeshRefiner.bisect(mesh, id);
                    }
                } else {
                    splitsThisStep += MeshRefiner.bisect(mesh, id);
                }
            }
            int flipsThisStep = DelaunayFlipper.improve(mesh);
            verifyConsistency(mesh, "after refinement step " + step);
            hook.afterStep(step, mesh);
            step++;
            if (limitReached || (splitsThisStep == 0 && flipsThisStep == 0 && insertsThisStep == 0)) {
                // 单元数超限，或剩余越界已无法通过二分/插入/翻边改进：
                // 停止细化，剩余越界在最终报告中显式列出
                break;
            }
        }

        QualityReport finalReport = QualityChecker.evaluate(mesh, thresholds);
        verifyConsistency(mesh, "final mesh");
        int rounds = Math.max(step, 0);
        return new GenerationResult(mesh.snapshot(), finalReport, rounds, limitReached, request.seed());
    }

    private static void verifyConsistency(Mesh mesh, String phase) {
        List<String> problems = mesh.findInconsistencies();
        if (!problems.isEmpty()) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                    "mesh consistency check failed " + phase + ": " + problems.get(0),
                    java.util.Map.of("phase", phase, "problemCount", problems.size()));
        }
    }
}
