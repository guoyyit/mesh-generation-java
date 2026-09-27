package com.github.guoyyit.meshgenerationjava.mesh.error;

/**
 * 网格生成过程中所有可区分的失败原因。
 *
 * <p>错误码分类原则：输入问题（VALIDATION_*）一律拒绝且不产生任何输出；
 * 任务控制类（TASK_*）反映任务生命周期状态；内部一致性失败（INTERNAL_*）
 * 表示算法不变量被破坏，同样不得产生半成品结果。
 */
public enum MeshErrorCode {

    /** 外边界点数不足（少于 3 个）或区域退化。 */
    INVALID_POLYGON("invalid-polygon", 400),

    /** 边界中出现坐标完全相同的重复点。 */
    DUPLICATE_POINT("duplicate-point", 400),

    /** 边界自相交（非相邻边出现交叉或重叠）。 */
    SELF_INTERSECTING_BOUNDARY("self-intersecting-boundary", 400),

    /** 多边形面积为零（共线退化）。 */
    DEGENERATE_POLYGON("degenerate-polygon", 400),

    /** 内孔未完全位于外边界内部。 */
    HOLE_OUTSIDE_BOUNDARY("hole-outside-boundary", 400),

    /** 内孔之间或内孔与外边界相交/接触。 */
    INTERSECTING_HOLE("intersecting-hole", 400),

    /** 生成参数非法（阈值、步长、种子等）。 */
    INVALID_PARAMETER("invalid-parameter", 400),

    /** 生成过程中出现面积为零或朝向错误的退化单元。 */
    DEGENERATE_ELEMENT("degenerate-element", 500),

    /** 最终网格不满足内部一致性（悬挂边、边界缺失等）。 */
    INCONSISTENT_MESH("inconsistent-mesh", 500),

    /** 单元数超过安全上限，无法继续细化。 */
    ELEMENT_LIMIT_EXCEEDED("element-limit-exceeded", 500),

    /** 任务不存在。 */
    TASK_NOT_FOUND("task-not-found", 404),

    /** 任务当前状态不允许该操作（如对未完成任务取结果）。 */
    TASK_STATE_CONFLICT("task-state-conflict", 409),

    /** 任务被用户取消。 */
    TASK_CANCELLED("task-cancelled", 409),

    /** 任务执行超时。 */
    TASK_TIMEOUT("task-timeout", 409),

    /** 进程重启时任务处于未完成状态且未配置自动恢复。 */
    TASK_INTERRUPTED("task-interrupted", 409),

    /** 持久化/恢复阶段出现的 IO 错误。 */
    PERSISTENCE_ERROR("persistence-error", 500),

    /** 请求体无法解析。 */
    MALFORMED_REQUEST("malformed-request", 400),

    /** 未分类的内部错误。 */
    INTERNAL_ERROR("internal-error", 500);

    private final String code;
    private final int httpStatus;

    MeshErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
