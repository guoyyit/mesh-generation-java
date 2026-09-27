package com.github.guoyyit.meshgenerationjava.quality;

/** One threshold violation, located at an element centroid. */
public record QualityIssue(int elementId, Metric metric, double value, double threshold,
                           double centroidX, double centroidY, String message) {
}
