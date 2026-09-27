package com.github.guoyyit.meshgenerationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end REST checks: generation, error classification, async task lifecycle. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SQUARE_REGION = """
            {"outer":[[0,0],[2,0],[2,2],[0,2]],"holes":[]}
            """;

    private static String requestBody(String region, String options) {
        return "{\"region\":" + region + ",\"options\":" + options + "}";
    }

    @Test
    void generateReturnsUsableMesh() throws Exception {
        MvcResult result = mvc.perform(post("/api/mesh/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(SQUARE_REGION,
                                "{\"seed\":3,\"maxEdgeLength\":0.7,\"minAngleDeg\":3}")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode mesh = body.get("mesh");
        assertTrue(mesh.get("nodes").size() >= 4);
        assertFalse(mesh.get("elements").isEmpty());
        assertFalse(mesh.get("boundaryEdges").isEmpty());
        // Boundary markers of a hole-free region are all 0.
        for (JsonNode edge : mesh.get("boundaryEdges")) {
            assertEquals(0, edge.get("marker").asInt());
        }
        assertTrue(body.has("issues"));
        assertTrue(body.has("qualitySatisfied"));
    }

    @Test
    void invalidRegionYieldsClassified400() throws Exception {
        String bowtie = "{\"outer\":[[0,0],[2,2],[2,0],[0,2]],\"holes\":[]}";
        MvcResult result = mvc.perform(post("/api/mesh/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(bowtie, "{}")))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("SELF_INTERSECTING_BOUNDARY", body.get("errorCode").asText());
    }

    @Test
    void asyncTaskLifecycle() throws Exception {
        MvcResult submitted = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(SQUARE_REGION,
                                "{\"seed\":9,\"maxEdgeLength\":0.8,\"minAngleDeg\":3}")))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = mapper.readTree(submitted.getResponse().getContentAsString())
                .get("taskId").asText();

        // Poll until completed.
        String status = "";
        for (int i = 0; i < 200; i++) {
            MvcResult polled = mvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            status = mapper.readTree(polled.getResponse().getContentAsString())
                    .get("status").asText();
            if ("COMPLETED".equals(status)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("COMPLETED", status);

        MvcResult meshResult = mvc.perform(get("/api/tasks/" + taskId + "/result"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mesh = mapper.readTree(meshResult.getResponse().getContentAsString());
        assertFalse(mesh.get("elements").isEmpty());
    }

    @Test
    void unknownTaskYields404() throws Exception {
        MvcResult result = mvc.perform(get("/api/tasks/does-not-exist"))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("TASK_NOT_FOUND", body.get("errorCode").asText());
    }
}
