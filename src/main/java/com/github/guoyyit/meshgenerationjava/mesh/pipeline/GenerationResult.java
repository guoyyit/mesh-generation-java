package com.github.guoyyit.meshgenerationjava.mesh.pipeline;

import com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityReport;

/**
 * 最终生成结果：完整可读的网格快照 + 质量报告。
 * qualitySatisfied=false 时报告仍显式列出剩余越界项。
 */
public record GenerationResult(
        MeshSnapshot mesh,
        QualityReport qualityReport,
        int refinementStepsExecuted,
        boolean elementLimitReached,
        long seed) {

    public boolean qualitySatisfied() {
        return qualityReport.satisfied();
    }
}
