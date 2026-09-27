package com.github.guoyyit.meshgenerationjava.generator;

import com.github.guoyyit.meshgenerationjava.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

/** Knobs for mesh generation. Same seed + same input => identical output. */
public class MeshGenerationOptions {

    private long seed = 42L;
    /** Target maximum edge length; <= 0 disables the constraint. */
    private double maxEdgeLength = 0.0;
    private double minAngleDeg = 20.0;
    private double minArea = 0.0;
    /** Jitter of interior Steiner points, as a fraction of local spacing (0 disables). */
    private double interiorJitter = 0.25;
    private int maxRefinementSteps = 10_000;
    private boolean failOnQualityViolation = false;
    /** Per-task timeout; 0 means the service default applies. */
    private long timeoutMillis = 0;

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public double getMaxEdgeLength() {
        return maxEdgeLength;
    }

    public void setMaxEdgeLength(double maxEdgeLength) {
        this.maxEdgeLength = maxEdgeLength;
    }

    public double getMinAngleDeg() {
        return minAngleDeg;
    }

    public void setMinAngleDeg(double minAngleDeg) {
        this.minAngleDeg = minAngleDeg;
    }

    public double getMinArea() {
        return minArea;
    }

    public void setMinArea(double minArea) {
        this.minArea = minArea;
    }

    public double getInteriorJitter() {
        return interiorJitter;
    }

    public void setInteriorJitter(double interiorJitter) {
        this.interiorJitter = interiorJitter;
    }

    public int getMaxRefinementSteps() {
        return maxRefinementSteps;
    }

    public void setMaxRefinementSteps(int maxRefinementSteps) {
        this.maxRefinementSteps = maxRefinementSteps;
    }

    public boolean isFailOnQualityViolation() {
        return failOnQualityViolation;
    }

    public void setFailOnQualityViolation(boolean failOnQualityViolation) {
        this.failOnQualityViolation = failOnQualityViolation;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** Rejects illegal parameter values with classified errors. */
    public void validate() {
        if (Double.isNaN(maxEdgeLength) || maxEdgeLength < 0.0
                || Double.isInfinite(maxEdgeLength)) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "maxEdgeLength must be >= 0 (0 disables), got " + maxEdgeLength);
        }
        if (Double.isNaN(minArea) || minArea < 0.0 || Double.isInfinite(minArea)) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "minArea must be >= 0, got " + minArea);
        }
        if (Double.isNaN(interiorJitter) || interiorJitter < 0.0 || interiorJitter >= 1.0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "interiorJitter must be in [0, 1), got " + interiorJitter);
        }
        if (maxRefinementSteps <= 0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "maxRefinementSteps must be > 0, got " + maxRefinementSteps);
        }
        if (timeoutMillis < 0) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "timeoutMillis must be >= 0, got " + timeoutMillis);
        }
        toQualityThresholds().validate();
    }

    public QualityThresholds toQualityThresholds() {
        QualityThresholds thresholds = new QualityThresholds();
        thresholds.setMinAngleDeg(minAngleDeg);
        thresholds.setMaxEdgeLength(maxEdgeLength > 0.0 ? maxEdgeLength : Double.POSITIVE_INFINITY);
        thresholds.setMinArea(minArea);
        return thresholds;
    }
}
