package com.github.guoyyit.meshgenerationjava.web.dto;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;

/**
 * 创建生成任务请求体。seed 缺省为 0；maxRefinementSteps 缺省取管线默认值。
 */
public record CreateTaskRequest(
        RegionDto region,
        ThresholdsDto thresholds,
        Long seed,
        Integer maxRefinementSteps,
        Integer maxElements) {

    public GenerationRequest toGenerationRequest(int defaultMaxElements) {
        if (region == null) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER, "region is required");
        }
        return new GenerationRequest(
                region.toRegion(),
                thresholds == null ? com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds.none()
                        : thresholds.toThresholds(),
                seed == null ? 0L : seed,
                maxRefinementSteps == null ? GenerationRequest.DEFAULT_MAX_STEPS : maxRefinementSteps,
                maxElements == null ? defaultMaxElements : maxElements);
    }
}
