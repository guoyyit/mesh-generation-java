package com.github.guoyyit.meshgenerationjava.task;

import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.geometry.Region;

/** Persisted task record: request, status and error (if any). */
public class MeshTask {

    private String id;
    private TaskStatus status;
    private Region region;
    private MeshGenerationOptions options;
    private String error;
    private String errorCode;
    private long createdAt;
    private long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public MeshGenerationOptions getOptions() {
        return options;
    }

    public void setOptions(MeshGenerationOptions options) {
        this.options = options;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
