# mesh-generation-java

二维平面区域的非结构三角网格生成服务。输入为带内外边界的平面区域定义，输出为
节点坐标、单元拓扑与边界标记，可直接用于后续数值计算（有限元/有限体积等）。

## 功能概览

- **初始网格生成**：外边界 + 任意数量内边界（洞）的平面区域，经洞桥接 + 耳切法
  三角化，边界由网格边精确保持，无悬挂节点、无重叠单元（面积守恒可验证）。
- **质量检测**：按边长、角度、面积阈值检测低质量单元，返回单元 id 与质心定位；
  阈值真实生效，越界必报，可选 `failOnQualityViolation` 直接失败。
- **局部细分/粗化**：Rivara 最长边二分细分（共享边两侧同时分裂，不产生悬挂边）；
  安全边塌缩粗化（只塌缩内部边，边界端点不动，拒绝翻转/重复单元）。操作后边界、
  拓扑一致性、单元 CCW 朝向均保持。
- **并发任务**：多区域/多参数集合并发生成，任务相互隔离；支持协作式取消与超时，
  结果仅在完整成功时原子提交，取消/超时/崩溃不留半成品。
- **重启恢复**：任务与结果落盘（临时文件 + 原子移动）。重启后 `recover()` 将
  PENDING/RUNNING 任务标记为 INTERRUPTED，与已提交结果严格区分；`resubmit()`
  从头重跑（同种子结果一致，细分步骤不重复不跳过）。
- **输入校验**：自相交边界、重复点、退化环、洞越界/相交、非法参数均以可区分的
  错误码拒绝；异常输入不会使服务崩溃。
- **可复现性**：固定随机种子 + 相同输入 ⇒ 输出逐项一致；不同种子允许不同，
  但均满足质量与拓扑约束。

## 构建与测试

```bash
mvn clean package   # 构建并运行全部测试
mvn spring-boot:run # 启动服务（默认 8080 端口）
```

## 输入/输出格式

### 区域定义（Region）

```json
{
  "outer": [[0,0],[4,0],[4,4],[0,4]],
  "holes": [[[1,1],[3,1],[3,3],[1,3]]]
}
```

- `outer`：外边界顶点（顺/逆时针均可，自动规范为 CCW）。
- `holes`：内边界数组，每个洞必须严格位于外边界内且互不重叠。

### 网格输出（MeshDto）

```json
{
  "nodes":         [{"id":0,"x":0.0,"y":0.0}, ...],
  "elements":      [[0,1,4], ...],
  "boundaryEdges": [{"a":0,"b":1,"marker":0}, ...]
}
```

- 节点/单元 id 从 0 连续编号；单元三元组为 CCW 顺序。
- `boundaryEdges.marker`：0 = 外边界，1..n = 第 n 个洞。边界边随细分在边界段上
  二分，标记继承，边界几何精确保持。

## 质量指标与阈值

对每个三角形单元计算：

| 指标 | 定义 | 阈值参数 | 违例判定 |
|---|---|---|---|
| `MIN_ANGLE` | 最小内角（度） | `minAngleDeg` ∈ (0, 60) | 值 < 阈值 |
| `MAX_EDGE_LENGTH` | 最长边长 | `maxEdgeLength` > 0 | 值 > 阈值 |
| `MIN_AREA` | 单元面积 | `minArea` ≥ 0 | 值 < 阈值 |

- 违例判定含 1e-9 相对容差，恰在阈值上的值不因浮点噪声误报；真实越界必报。
- 每次违例产出一条 `QualityIssue{elementId, metric, value, threshold, centroidX,
  centroidY, message}`，按 (elementId, metric) 排序，可据此定位问题单元。
- 细分优先满足边长约束；角度约束按血统深度上限（8 代）尝试——对二分法不可修复的
  自相似针状单元，停止空转并如实上报，绝不静默忽略。
- `failOnQualityViolation=true` 时，任一违例使生成以 `QUALITY_VIOLATION` 失败。

## 恢复语义

- 任务状态机：`PENDING → RUNNING → COMPLETED / FAILED / CANCELLED`，另有
  `INTERRUPTED` 表示进程停止时仍在 PENDING/RUNNING 的任务。
- 结果提交为两阶段：先原子写结果文件，再翻状态为 COMPLETED。崩溃只可能留下
  “非 COMPLETED + 孤儿结果文件”，恢复后该结果**不会**被当作最终结果返回。
- `MeshTaskService.recover()`：扫描存储，把 PENDING/RUNNING 标记为 INTERRUPTED
  （返回标记数量）；COMPLETED 任务及其结果原样保留、完整可读。
- `resubmit(id)`：仅接受 INTERRUPTED/FAILED/CANCELLED 任务，按存储的请求
  （区域 + 参数 + 种子）从头重跑，同种子结果逐项一致。

## 可配置项

### 生成参数（MeshGenerationOptions，可按任务设置）

| 参数 | 默认 | 说明 |
|---|---|---|
| `seed` | 42 | 随机种子（Steiner 点抖动）；同种子同输入结果一致 |
| `maxEdgeLength` | 0（不约束） | 目标最大边长，>0 时由细分强制满足 |
| `minAngleDeg` | 20 | 最小内角阈值，∈ (0, 60) |
| `minArea` | 0 | 最小面积阈值 |
| `interiorJitter` | 0.25 | Steiner 点抖动幅度（占局部边长比例），∈ [0, 1) |
| `maxRefinementSteps` | 10000 | 细分步数预算，耗尽后剩余违例如实上报 |
| `failOnQualityViolation` | false | 有违例时以 QUALITY_VIOLATION 失败 |
| `timeoutMillis` | 0（用服务默认） | 单任务超时 |

### 服务配置（application.properties）

| 键 | 默认 | 说明 |
|---|---|---|
| `mesh.task.store-dir` | `./mesh-store` | 任务/结果持久化目录 |
| `mesh.task.worker-threads` | 4 | 并发工作线程数 |
| `mesh.task.default-timeout-millis` | 60000 | 默认任务超时 |

## REST API

| 方法/路径 | 说明 |
|---|---|
| `POST /api/mesh/generate` | 同步生成；body `{region, options}`，返回 `{mesh, issues, qualitySatisfied}` |
| `POST /api/tasks` | 提交异步任务，返回 `{taskId}` |
| `GET /api/tasks/{id}` | 查询任务状态/错误信息 |
| `GET /api/tasks/{id}/result` | 获取已提交网格（未完成返回 409） |
| `DELETE /api/tasks/{id}` | 协作式取消 |

错误响应统一为 `{"errorCode": "...", "message": "..."}`：

- 400：`DUPLICATE_POINT`、`SELF_INTERSECTING_BOUNDARY`、`DEGENERATE_GEOMETRY`、
  `HOLE_OUTSIDE_BOUNDARY`、`HOLE_INTERSECTS_BOUNDARY`、`OVERLAPPING_HOLES`、
  `INVALID_PARAMETER`、`QUALITY_VIOLATION`
- 404：`TASK_NOT_FOUND`；409：`TASK_NOT_COMPLETED`
- 任务级失败（不返回 HTTP 错误，记录在任务上）：`TASK_CANCELLED`、`TASK_TIMEOUT`、
  `GENERATION_FAILED`

## 库级用法

```java
Region region = new Region(outer, holes);
MeshGenerationOptions options = new MeshGenerationOptions();
options.setSeed(42L);
options.setMaxEdgeLength(0.5);
GenerationResult result = new MeshGenerator().generate(region, options);
Mesh mesh = result.mesh();                 // 可直接使用
List<QualityIssue> issues = result.issues();

// 局部粗化
new MeshCoarsener().coarsen(mesh, 0.3, 10);

// 序列化
String json = new MeshSerializer().toJson(mesh);
```

## 测试覆盖

`src/test/java/.../tests/` 下 34 个用例：

- `BoundaryPreservationTest`：边界精确保持、无悬挂节点、面积守恒、含洞/凹区域
- `QualityCheckTest`：三类指标检测、定位信息、阈值边界值、非法阈值
- `RefineCoarsenTest`：细分满足边长、粗化减密度、边界/朝向/一致性保持、再细分
- `ConcurrencyTest`：8 任务并发隔离（与单线程结果一致）、取消、超时
- `RecoveryTest`：已提交结果重启后完整可读、INTERRUPTED 可区分、孤儿结果不认领、
  resubmit 从头重跑结果一致
- `ValidationTest`：11 类非法输入分类拒绝、服务不被异常输入击垮
- `DeterminismTest`：同种子逐项一致、异种子均有效、历史结论可复现
- `ApiIntegrationTest`：REST 端到端（生成、错误分类、异步任务生命周期、404）
