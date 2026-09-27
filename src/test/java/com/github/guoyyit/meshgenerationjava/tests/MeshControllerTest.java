package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mesh.store-dir=target/test-store-rest",
                "mesh.worker-threads=2",
                "mesh.task-timeout=PT30S"
        })
class MeshControllerTest {

    static {
        // 上下文创建前清理历史存储，保证恢复逻辑不拾取旧任务
        try {
            Path dir = Path.of("target/test-store-rest");
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
    }

    @Autowired
    private TestRestTemplate rest;

    private static Map<String, Object> squareWithHoleRequest(long seed) {
        return Map.of(
                "region", Map.of(
                        "outer", new double[][]{{0, 0}, {4, 0}, {4, 4}, {0, 4}},
                        "holes", new double[][][]{{{1, 1}, {1, 3}, {3, 3}, {3, 1}}}),
                "thresholds", Map.of("maxEdgeLength", 1.0),
                "seed", seed,
                "maxRefinementSteps", 30);
    }

    private String submitAndWait(Map<String, Object> request) {
        ResponseEntity<Map> created = rest.postForEntity("/api/mesh/tasks", request, Map.class);
        assertEquals(HttpStatus.ACCEPTED, created.getStatusCode());
        String id = (String) created.getBody().get("taskId");
        assertNotNull(id);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Map status = rest.getForObject("/api/mesh/tasks/" + id, Map.class);
            String s = (String) status.get("status");
            if (!"PENDING".equals(s) && !"RUNNING".equals(s)) {
                assertEquals("COMPLETED", s, () -> "task failed: " + status);
                return id;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("task did not complete in time: " + id);
    }

    @Test
    void submitAndFetchResult() {
        String id = submitAndWait(squareWithHoleRequest(7L));
        ResponseEntity<Map> response = rest.getForEntity("/api/mesh/tasks/" + id + "/result", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = response.getBody();
        assertNotNull(body);
        Map mesh = (Map) body.get("mesh");
        List<?> nodes = (List<?>) mesh.get("nodes");
        List<?> elements = (List<?>) mesh.get("elements");
        List<?> boundaryEdges = (List<?>) mesh.get("boundaryEdges");
        assertFalse(nodes.isEmpty());
        assertFalse(elements.isEmpty());
        assertTrue(boundaryEdges.size() >= 8, "outer + hole boundary edges must be present");
        Map quality = (Map) body.get("qualityReport");
        assertTrue(((List<?>) quality.get("violations")).isEmpty(),
                "violations must be empty: " + quality);
        assertTrue((Double) quality.get("maxEdgeLength") <= 1.0 + 1e-9);
        // 边界标记：同时存在外边界(0)与孔(1)
        assertTrue(boundaryEdges.stream().anyMatch(e -> ((Map) e).get("boundaryId").equals(0)));
        assertTrue(boundaryEdges.stream().anyMatch(e -> ((Map) e).get("boundaryId").equals(1)));
    }

    @Test
    void deterministicForSameSeed() {
        String a = submitAndWait(squareWithHoleRequest(42L));
        String b = submitAndWait(squareWithHoleRequest(42L));
        Map ra = rest.getForObject("/api/mesh/tasks/" + a + "/result", Map.class);
        Map rb = rest.getForObject("/api/mesh/tasks/" + b + "/result", Map.class);
        assertEquals(ra.get("mesh"), rb.get("mesh"));
    }

    @Test
    void rejectsSelfIntersectingBoundary() {
        Map<String, Object> request = Map.of(
                "region", Map.of("outer",
                        new double[][]{{0, 0}, {2, 0}, {2, 2}, {0.5, -1}, {0, 2}}));
        ResponseEntity<Map> response = rest.postForEntity("/api/mesh/tasks", request, Map.class);
        // 校验在任务执行线程内完成：任务应进入 FAILED 且错误码可区分
        if (response.getStatusCode() == HttpStatus.ACCEPTED) {
            String id = (String) response.getBody().get("taskId");
            Map status = waitTerminal(id);
            assertEquals("FAILED", status.get("status"));
            assertEquals(MeshErrorCode.SELF_INTERSECTING_BOUNDARY.getCode(), status.get("errorCode"));
        } else {
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals(MeshErrorCode.SELF_INTERSECTING_BOUNDARY.getCode(),
                    response.getBody().get("code"));
        }
    }

    @Test
    void rejectsDuplicatePoints() {
        Map<String, Object> request = Map.of(
                "region", Map.of("outer",
                        new double[][]{{0, 0}, {1, 0}, {1, 0}, {0, 1}}));
        ResponseEntity<Map> response = rest.postForEntity("/api/mesh/tasks", request, Map.class);
        if (response.getStatusCode() == HttpStatus.ACCEPTED) {
            Map status = waitTerminal((String) response.getBody().get("taskId"));
            assertEquals("FAILED", status.get("status"));
            assertEquals(MeshErrorCode.DUPLICATE_POINT.getCode(), status.get("errorCode"));
        } else {
            assertEquals(MeshErrorCode.DUPLICATE_POINT.getCode(), response.getBody().get("code"));
        }
    }

    @Test
    void rejectsHoleOutside() {
        Map<String, Object> request = Map.of(
                "region", Map.of(
                        "outer", new double[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                        "holes", new double[][][]{{{5, 5}, {6, 5}, {6, 6}}}));
        ResponseEntity<Map> response = rest.postForEntity("/api/mesh/tasks", request, Map.class);
        if (response.getStatusCode() == HttpStatus.ACCEPTED) {
            Map status = waitTerminal((String) response.getBody().get("taskId"));
            assertEquals("FAILED", status.get("status"));
            assertEquals(MeshErrorCode.HOLE_OUTSIDE_BOUNDARY.getCode(), status.get("errorCode"));
        } else {
            assertEquals(MeshErrorCode.HOLE_OUTSIDE_BOUNDARY.getCode(),
                    response.getBody().get("code"));
        }
    }

    @Test
    void rejectsInvalidThreshold() {
        Map<String, Object> request = Map.of(
                "region", Map.of("outer", new double[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}),
                "thresholds", Map.of("minAngleDeg", 70.0));
        ResponseEntity<Map> response = rest.postForEntity("/api/mesh/tasks", request, Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MeshErrorCode.INVALID_PARAMETER.getCode(), response.getBody().get("code"));
    }

    @Test
    void rejectsMalformedJson() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.postForEntity("/api/mesh/tasks",
                new HttpEntity<>("{not json", headers), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MeshErrorCode.MALFORMED_REQUEST.getCode(), response.getBody().get("code"));
    }

    @Test
    void unknownTaskReturns404() {
        ResponseEntity<Map> response = rest.getForEntity("/api/mesh/tasks/does-not-exist", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(MeshErrorCode.TASK_NOT_FOUND.getCode(), response.getBody().get("code"));
    }

    @Test
    void resultOfUnfinishedTaskIsConflict() {
        Map<String, Object> heavy = Map.of(
                "region", Map.of("outer", new double[][]{{0, 0}, {4, 0}, {4, 4}, {0, 4}}),
                "thresholds", Map.of("maxEdgeLength", 0.01),
                "seed", 0,
                "maxRefinementSteps", 60,
                "maxElements", 300000);
        ResponseEntity<Map> created = rest.postForEntity("/api/mesh/tasks", heavy, Map.class);
        String id = (String) created.getBody().get("taskId");
        ResponseEntity<Map> result = rest.getForEntity("/api/mesh/tasks/" + id + "/result", Map.class);
        // 任务要么仍在跑（409），要么恰好已完成（200）——但绝不允许半成品
        if (result.getStatusCode() == HttpStatus.CONFLICT) {
            assertEquals(MeshErrorCode.TASK_STATE_CONFLICT.getCode(), result.getBody().get("code"));
        } else {
            assertEquals(HttpStatus.OK, result.getStatusCode());
        }
        // 清理
        rest.postForEntity("/api/mesh/tasks/" + id + "/cancel", null, Map.class);
    }

    @Test
    void cancelEndpointWorks() {
        Map<String, Object> heavy = Map.of(
                "region", Map.of("outer", new double[][]{{0, 0}, {4, 0}, {4, 4}, {0, 4}}),
                "thresholds", Map.of("maxEdgeLength", 0.005),
                "seed", 0,
                "maxRefinementSteps", 60,
                "maxElements", 500000);
        ResponseEntity<Map> created = rest.postForEntity("/api/mesh/tasks", heavy, Map.class);
        String id = (String) created.getBody().get("taskId");
        Map status = waitTerminalAfterCancel(id);
        assertTrue("CANCELLED".equals(status.get("status")) || "COMPLETED".equals(status.get("status")),
                "expected CANCELLED (or COMPLETED if it finished first): " + status);
    }

    @Test
    void refineAndCoarsenEndpoints() {
        Map<String, Object> request = Map.of(
                "region", Map.of("outer", new double[][]{{0, 0}, {2, 0}, {2, 2}, {0, 2}}),
                "seed", 1,
                "maxRefinementSteps", 0);
        String id = submitAndWait(request);
        Map base = rest.getForObject("/api/mesh/tasks/" + id + "/result", Map.class);
        Map baseMesh = (Map) base.get("mesh");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) baseMesh.get("elements");
        int baseCount = elements.size();
        List<Long> ids = elements.stream().map(e -> ((Number) e.get("id")).longValue()).toList();

        ResponseEntity<Map> refined = rest.postForEntity("/api/mesh/tasks/" + id + "/refine",
                Map.of("elementIds", ids, "levels", 2), Map.class);
        assertEquals(HttpStatus.OK, refined.getStatusCode());
        int refinedCount = ((List<?>) ((Map) refined.getBody().get("mesh")).get("elements")).size();
        assertTrue(refinedCount > baseCount);

        ResponseEntity<Map> coarsened = rest.postForEntity("/api/mesh/tasks/" + id + "/coarsen",
                Map.of(), Map.class);
        assertEquals(HttpStatus.OK, coarsened.getStatusCode());
        int coarsenedCount = ((List<?>) ((Map) coarsened.getBody().get("mesh")).get("elements")).size();
        assertEquals(baseCount, coarsenedCount);
    }

    private Map waitTerminal(String id) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Map status = rest.getForObject("/api/mesh/tasks/" + id, Map.class);
            String s = (String) status.get("status");
            if (!"PENDING".equals(s) && !"RUNNING".equals(s)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("task did not reach terminal state: " + id);
    }

    private Map waitTerminalAfterCancel(String id) {
        ResponseEntity<Map> cancelResponse = rest.postForEntity(
                "/api/mesh/tasks/" + id + "/cancel", null, Map.class);
        assertTrue(cancelResponse.getStatusCode().is2xxSuccessful()
                || cancelResponse.getStatusCode() == HttpStatus.CONFLICT);
        return waitTerminal(id);
    }
}
