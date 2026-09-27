package com.github.guoyyit.meshgenerationjava.mesh.pipeline;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;

import java.util.Map;

/**
 * 一次网格生成的完整输入。maxRefinementSteps 与 maxElements 限制自适应细化规模。
 */
public record GenerationRequest(
        PlanarRegion region,
        QualityThresholds thresholds,
        long seed,
        int maxRefinementSteps,
        int maxElements) {

    public static final int MAX_STEPS_LIMIT = 200;
    public static final int MIN_MAX_ELEMENTS = 4;
    public static final int DEFAULT_MAX_STEPS = 30;
    public static final int DEFAULT_MAX_ELEMENTS = 100_000;

    public GenerationRequest {
        if (region == null) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER, "region is required");
        }
        if (thresholds == null) {
            thresholds = QualityThresholds.none();
        }
        if (maxRefinementSteps < 0 || maxRefinementSteps > MAX_STEPS_LIMIT) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER,
                    "maxRefinementSteps must be in [0, " + MAX_STEPS_LIMIT + "]",
                    Map.of("maxRefinementSteps", maxRefinementSteps));
        }
        if (maxElements < MIN_MAX_ELEMENTS) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER,
                    "maxElements must be >= " + MIN_MAX_ELEMENTS,
                    Map.of("maxElements", maxElements));
        }
    }

    public GenerationRequest(PlanarRegion region, QualityThresholds thresholds,
                             long seed, int maxRefinementSteps) {
        this(region, thresholds, seed, maxRefinementSteps, DEFAULT_MAX_ELEMENTS);
    }
}
