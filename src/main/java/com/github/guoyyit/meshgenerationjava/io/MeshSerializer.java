package com.github.guoyyit.meshgenerationjava.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.meshgenerationjava.core.BoundaryEdge;
import com.github.guoyyit.meshgenerationjava.core.Element;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.core.MeshNode;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON (de)serialization of meshes. Output uses compact, contiguous ids so the
 * result can be consumed directly by downstream numerical solvers.
 */
public class MeshSerializer {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toJson(Mesh mesh) {
        try {
            return mapper.writeValueAsString(toDto(mesh));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Mesh fromJson(String json) {
        try {
            return fromDto(mapper.readValue(json, MeshDto.class));
        } catch (IOException e) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "malformed mesh json: " + e.getMessage());
        }
    }

    public MeshDto toDto(Mesh mesh) {
        Mesh compact = mesh.compacted();
        MeshDto dto = new MeshDto();
        dto.nodes = new ArrayList<>();
        for (MeshNode n : compact.nodes()) {
            NodeDto nd = new NodeDto();
            nd.id = n.id();
            nd.x = n.x();
            nd.y = n.y();
            dto.nodes.add(nd);
        }
        dto.elements = new ArrayList<>();
        for (Element e : compact.elements()) {
            dto.elements.add(new int[]{e.getN0(), e.getN1(), e.getN2()});
        }
        dto.boundaryEdges = new ArrayList<>();
        for (BoundaryEdge be : compact.boundaryEdges()) {
            EdgeDto ed = new EdgeDto();
            ed.a = be.a();
            ed.b = be.b();
            ed.marker = be.marker();
            dto.boundaryEdges.add(ed);
        }
        return dto;
    }

    public Mesh fromDto(MeshDto dto) {
        if (dto == null || dto.nodes == null || dto.elements == null || dto.boundaryEdges == null) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "mesh dto requires nodes, elements and boundaryEdges");
        }
        Mesh mesh = new Mesh();
        for (NodeDto n : dto.nodes) {
            mesh.addNode(n.x, n.y);
        }
        for (int[] tri : dto.elements) {
            if (tri == null || tri.length != 3) {
                throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                        "element must have exactly 3 nodes");
            }
            mesh.addElement(tri[0], tri[1], tri[2]);
        }
        for (EdgeDto e : dto.boundaryEdges) {
            mesh.addBoundaryEdge(e.a, e.b, e.marker);
        }
        List<String> problems = mesh.checkConsistency();
        if (!problems.isEmpty()) {
            throw new MeshValidationException(ErrorCode.DEGENERATE_GEOMETRY,
                    "deserialized mesh is inconsistent: " + problems.get(0));
        }
        return mesh;
    }

    /** Wire format: nodes/elements/boundaryEdges with contiguous ids. */
    public static class MeshDto {
        public List<NodeDto> nodes;
        public List<int[]> elements;
        public List<EdgeDto> boundaryEdges;
    }

    public static class NodeDto {
        public int id;
        public double x;
        public double y;
    }

    public static class EdgeDto {
        public int a;
        public int b;
        public int marker;
    }
}
