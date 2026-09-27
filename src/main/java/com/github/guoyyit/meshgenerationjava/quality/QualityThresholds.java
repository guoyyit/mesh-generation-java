package com.github.guoyyit.meshgenerationjava.quality;

import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

/** Quality thresholds; a metric is only enforced when its threshold is active. */
public class QualityThresholds {

    private double minAngleDeg = 20.0;
    private double maxEdgeLength = Double.POSITIVE_INFINITY;
    private double minArea = 0.0;

    public double getMinAngleDeg() {
        return minAngleDeg;
    }

    public void setMinAngleDeg(double minAngleDeg) {
        this.minAngleDeg = minAngleDeg;
    }

    public double getMaxEdgeLength() {
        return maxEdgeLength;
    }

    public void setMaxEdgeLength(double maxEdgeLength) {
        this.maxEdgeLength = maxEdgeLength;
    }

    public double getMinArea() {
        return minArea;
    }

    public void setMinArea(double minArea) {
        this.minArea = minArea;
    }

    /** Rejects illegal threshold combinations. */
    public void validate() {
        if (Double.isNaN(minAngleDeg) || minAngleDeg <= 0.0 || minAngleDeg >= 60.0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "minAngleDeg must be in (0, 60), got " + minAngleDeg);
        }
        if (Double.isNaN(maxEdgeLength) || maxEdgeLength <= 0.0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "maxEdgeLength must be positive, got " + maxEdgeLength);
        }
        if (Double.isNaN(minArea) || minArea < 0.0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "minArea must be >= 0, got " + minArea);
        }
    }
}
