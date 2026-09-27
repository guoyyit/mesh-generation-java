package com.github.guoyyit.meshgenerationjava.generator;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.quality.QualityIssue;

import java.util.List;

/** Output of a generation run: the mesh plus an honest quality report. */
public record GenerationResult(Mesh mesh, List<QualityIssue> issues, int steinerPointsInserted,
                               int bisections, boolean qualitySatisfied) {
}
