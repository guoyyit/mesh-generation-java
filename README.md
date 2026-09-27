# mesh-generation-java

二维平面区域的**非结构三角形网格生成、质量检测与自适应细分/粗化**服务。
输入为带外边界与内孔边界的平面区域，输出节点坐标、单元拓扑与边界标记，
结果网格通过内部一致性校验，可直接供后续数值计算使用。

- Java 17 / Spring Boot 3.4，无第三方几何库依赖
- 并发任务执行、取消与超时、进程重启恢复
- 文件原子持久化，相同输入 + 相同随机种子结果逐项一致

## 1. 快速开始

```bash
mvn spring-boot:run
```

```bash
curl -X POST http://localhost:8080/api/mesh/tasks \
  -H 'Content-Type: application/json' -d '{
  "region": {
    "outer": [[0,0],[4,0],[4,4],[0,4]],
    "holes": [[[1,1],[1,3],[3,3],[3,1]]]
  },
  "thresholds": { "minAngleDeg": 25, "maxEdgeLength": 1.0 },
  "seed": 42,
  "maxRefinementSteps": 30
}'
# -> 202 {"taskId":"...","status":"PENDING"}
```

轮询 `GET /api/mesh/tasks/{id}`，`COMPLETED` 后取结果：

```bash
curl http://localhost:8080/api/mesh/tasks/{id}/result
```

结果结构：

```jsonc
{
  "mesh": {
    "nodes":        [{ "id": 0, "x": 0.0, "y": 0.0, "boundaryId": 0 }],
    "elements":     [{ "id": 0, "n0": 0, "n1": 1, "n2": 2 }],
    "boundaryEdges":[{ "a": 0, "b": 1, "boundaryId": 0 }],
    "splitEvents":  [ /* 细分历史，供粗化回滚 */ ]
  },
  "qualityReport": {
    "elementCount": 12,
    "minAngleDeg": 30.0, "maxEdgeLength": 0.95,
    "minArea": ..., "maxArea": ..., "minRadiusRatio": ...,
    "violations": []
  },
  "refinementStepsExecuted": 4,
  "elementLimitReached": false,
  "seed": 42
}
```

约定：

- 所有单元顶点恒为**逆时针（CCW）**朝向；
- `boundaryId = 0` 外边界，`1..k` 内孔边界，内部节点 `-1`；
- 边界边为沿边界正向的有向边（外边界 CCW、内孔 CW）。

## 2. 算法与不变量

| 阶段 | 实现 |
|---|---|
| 输入校验 | 自相交、重复点、退化多边形、孔在边界外/相交/包含、参数非法，逐类拒绝 |
| 初始三角化 | 内孔可见桥接 + 耳切法；边界边逐条登记，边界由网格边**精确**保持 |
| 三角化后处理 | 内部边局部 Delaunay 翻边（max-min 角度准则），消除耳切产生的狭长三角形 |
| 自适应细分 | 角度越界：Ruppert/Chew 外接圆圆心插入（Bowyer-Watson 空腔重剖 + 边界侵占时中点二分边界边），不可插入时退回 Rivara 最长边二分；边长/面积越界：Rivara 最长边二分。共享边两侧同时二分，**无悬挂节点/悬挂边** |
| 粗化 | 细分、圆心插入、翻边统一记入操作日志，严格按 id 逆序回滚；仅回滚子单元仍在、中点私有、旧边无冲突且质量达标的事件 |
| 一致性 | 每个阶段后校验：每条内部边恰 2 个邻单元、边界边恰 1 个邻单元、无重叠、无零面积、无孤立节点、全 CCW |

不变量由单元面积总和 == 区域面积（外边界面积 − 内孔面积）做强校验（见测试），
任何阶段破坏不变量都会以 `INCONSISTENT_MESH` 显式失败，绝不输出半成品。

## 3. 质量指标与阈值

每个单元计算：

- **面积** `area`
- **三条边长** `edgeLengths` 及 `maxEdgeLength`
- **三个内角（度）** `anglesDeg` 及 `minAngleDeg`
- **半径比** `radiusRatio = 2·r_in/r_circum`，等边三角形为 1，越小越差
- **形心坐标**（越界记录的定位信息）

阈值（字段缺省表示该项不检查）：

| 字段 | 含义 | 合法范围 |
|---|---|---|
| `minAngleDeg` | 最小内角下限 | (0, 60] |
| `maxEdgeLength` | 最大边长上限 | > 0 |
| `minArea` | 单元面积下限（粗化守卫） | > 0 |
| `maxArea` | 单元面积上限（驱动细分） | > 0，且 `minArea < maxArea` |

语义：

1. 生成阶段按阈值做自适应细分：角度越界走圆心插入（实践上 30–35° 可达，
   受 Ruppert/Chew 方法理论极限约束），边长/面积越界走最长边二分，
   直到无越界、达到 `maxRefinementSteps` 或 `maxElements` 上限；
2. **阈值真实生效**：上限触发或剩余越界无法继续改进时任务仍可完成，但
   `qualityReport.violations` 逐条列出剩余越界项（单元 id、指标名、实际值、
   阈值、形心坐标），`violations` 非空即 `satisfied=false`，越界不会被静默忽略；
3. 粗化以阈值为守卫：回滚会导致单元越界时，该次回滚被拒绝；
4. 非法阈值在请求阶段即返回 `invalid-parameter`。

## 4. 局部细分与粗化（REST）

```bash
# 对指定单元细分 2 轮
curl -X POST http://localhost:8080/api/mesh/tasks/{id}/refine \
  -H 'Content-Type: application/json' \
  -d '{"elementIds":[0,1], "levels":2}'

# 粗化指定单元相关的可回滚细分；body 为 {} 表示整网粗化
curl -X POST http://localhost:8080/api/mesh/tasks/{id}/coarsen \
  -H 'Content-Type: application/json' -d '{"elementIds":[0]}'
```

- 仅允许在 `COMPLETED` 任务上执行，按任务加锁；
- 操作后同样通过一致性与边界保持校验，并原子重写 `result.json`；
- 边界边被二分后产生的中点严格共线，粗化合并半边后边界几何不变。

## 5. 并发、取消与超时

- 工作线程池大小 `mesh.worker-threads`（默认 4），任务间对象与磁盘目录完全隔离；
- 管线在每轮细化及每 1024 次二分处响应中断；
- `POST /api/mesh/tasks/{id}/cancel`：排队中任务直接取消，运行中任务中断后落 `CANCELLED`；
- `mesh.task-timeout`：超时由调度器中断，落 `TIMEOUT`；
- 取消/超时任务**绝不写结果文件**，`GET .../result` 返回 409；
- 结果写入顺序为「原子写 result.json → 删除 checkpoint.json → 状态置 COMPLETED」。

## 6. 重启恢复语义

存储目录布局（`mesh.store-dir`，默认 `./mesh-store`）：

```
{storeDir}/{taskId}/task.json        # 任务元数据与状态
{storeDir}/{taskId}/checkpoint.json  # 最近完成步骤的完整检查点
{storeDir}/{taskId}/result.json      # 最终结果（只在全部计算结束后出现）
```

所有文件均为「临时文件 + 原子 rename」，读到的文件要么完整要么不存在。

| 重启时状态 | 恢复行为 |
|---|---|
| `COMPLETED` + result.json | 直接可见、完整可读 |
| `COMPLETED` 但无 result.json | 标记 `FAILED`（异常不一致） |
| `RUNNING/PENDING` + result.json | 结果已提交、状态未落盘 → 定稿 `COMPLETED` |
| `RUNNING/PENDING` + checkpoint.json | 从检查点续跑（`mesh.auto-resume=true`） |
| `RUNNING/PENDING` 无检查点 | 从三角化重新开始（确定性，无细化步骤被重复或跳过） |
| `FAILED/CANCELLED/TIMEOUT` | 保持终态 |
| `mesh.auto-resume=false` | 未完成任务标记 `INTERRUPTED`，可取错误码 `task-interrupted` |

- 检查点粒度为**完整细化轮次**：`step=-1` 表示三角化完成；之后每轮完成后落盘；
- 恢复时从 `step+1` 继续。崩溃发生在某轮写入检查点之前，则从上个检查点重跑该轮——
  由于 (输入, 种子) 完全确定，重跑产生相同状态，**不重复也不跳过步骤**；
- `checkpoint.json` 是中间状态，`result.json` 才是最终结果，二者严格区分；
  未完成任务永远读不到 `result.json`，中间状态不会被当作最终结果。

## 7. 可配置项

`application.properties`（前缀 `mesh.`）：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `mesh.store-dir` | `./mesh-store` | 任务/检查点/结果持久化目录 |
| `mesh.worker-threads` | `4` | 并发生成线程数 |
| `mesh.task-timeout` | `PT5M` | 单任务超时（ISO-8601，`0` 表示不限） |
| `mesh.auto-resume` | `true` | 重启后是否自动恢复未完成任务 |
| `mesh.max-elements` | `100000` | 单任务单元数硬上限，防止失控细化 |

请求级可配置：`seed`、`maxRefinementSteps`（0–200，默认 30）、
`maxElements`（不超过服务端硬上限语义的最小值）、各项质量阈值。

## 8. 确定性

- 固定种子 + 相同输入，生成与细分结果（节点、单元、边界边、质量报告、细化轮数）逐项一致；
- 种子影响耳切扫描起点，不同种子可得到不同网格，但都必须满足拓扑与质量约束；
- 所有遍历顺序按 id 稳定排序，不依赖 HashMap 遍历序；
- 细分子单元顺序、最长边等长取舍均有确定性规则。

## 9. 错误码

| code | HTTP | 含义 |
|---|---|---|
| `invalid-polygon` | 400 | 边界点数不足 / 非有限坐标 / 空 |
| `duplicate-point` | 400 | 边界存在坐标完全相同的重复点 |
| `self-intersecting-boundary` | 400 | 边界自相交或非相邻边接触 |
| `degenerate-polygon` | 400 | 多边形面积为零 |
| `hole-outside-boundary` | 400 | 内孔顶点位于外边界之外 |
| `intersecting-hole` | 400 | 内孔与外边界相交/接触，或孔相互交叠 |
| `invalid-parameter` | 400 | 阈值/步数/种子/单元数非法 |
| `malformed-request` | 400 | 请求体无法解析或 Content-Type 不支持 |
| `task-not-found` | 404 | 任务不存在 |
| `task-state-conflict` | 409 | 状态不允许该操作（如取未完成任务结果、重复取消） |
| `task-cancelled` | 409 | 任务被用户取消 |
| `task-timeout` | 409 | 任务超时 |
| `task-interrupted` | 409 | 重启且关闭自动恢复 |
| `degenerate-element` / `inconsistent-mesh` / `element-limit-exceeded` / `persistence-error` / `internal-error` | 500 | 内部失败；不产生无效输出 |

校验类失败同样使任务进入 `FAILED`（可通过任务状态中的 `errorCode` 区分原因），
异常输入不会导致服务崩溃。

## 10. 构建与测试

```bash
mvn test          # 全部 57 个用例
```

测试覆盖：

- 边界保持、面积守恒、CCW 朝向、无悬挂边（`TriangulationTest`、`MeshTestSupport`）
- 全部输入错误分类与非法参数（`RegionValidatorTest`）
- 边长/角度/面积阈值生效、越界不静默、上限报告（`PipelineTest`）
- 局部细分与粗化、质量守卫、粗细过渡协调性（`RefineCoarsenTest`）
- 并发隔离、排队取消、运行中取消、超时、服务存活（`MeshTaskManagerTest`）
- 重启恢复：已提交结果可读、检查点续跑与基线逐项一致、
  未完成状态可区分、提交瞬间崩溃定稿（`MeshTaskManagerTest`）
- REST 全链路、错误码与响应格式、细分粗化接口（`MeshControllerTest`）
- 同种子确定性、不同种子有效性（上述各测试）

## 11. 包结构

```
mesh/
  error/        错误码与统一异常
  geometry/     Point2、方向/相交/外接圆等几何谓词
  model/        节点/单元/边界边/网格（含邻接维护与一致性检查）
  validate/     区域校验
  triangulate/  耳切三角化（孔桥接）、Delaunay 翻边
  quality/      质量度量、阈值、越界报告
  refine/       Rivara 二分、圆心插入、统一操作日志粗化
  pipeline/     生成主流程、检查点恢复
service/        任务管理、并发、取消超时、文件存储、配置
web/            REST 控制器、DTO、全局异常处理
```
