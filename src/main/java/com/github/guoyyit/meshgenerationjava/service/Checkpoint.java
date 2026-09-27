package com.github.guoyyit.meshgenerationjava.service;

import com.github.guoyyit.meshgenerationjava.mesh.model.MeshSnapshot;

/**
 * 细化步骤检查点：step=-1 表示三角化刚完成、尚未细化。
 */
public record Checkpoint(int step, MeshSnapshot mesh) {
}
