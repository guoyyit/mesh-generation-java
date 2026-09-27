package com.github.guoyyit.meshgenerationjava.generator;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.geometry.GeometryUtils;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.quality.MeshQualityChecker;
import com.github.guoyyit.meshgenerationjava.quality.QualityIssue;
import com.github.guoyyit.meshgenerationjava.refine.MeshRefiner;
import com.github.guoyyit.meshgenerationjava.task.CancellationToken;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import com.github.guoyyit.meshgenerationjava.validation.RegionValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates an initial conforming mesh of a bounded planar region.
 * Pipeline: validate -> bridge holes -> ear clipping -> Steiner points (seeded)
 * -> Rivara refinement -> quality report. Boundary edges are preserved exactly.
 */
public class MeshGenerator {

    public GenerationResult generate(Region region, MeshGenerationOptions options) {
        return generate(region, options, CancellationToken.none());
    }

    public GenerationResult generate(Region region, MeshGenerationOptions options, CancellationToken token) {
        new RegionValidator().validate(region);
        options.validate();
        token.checkCancelled();

        // Normalize orientation: outer CCW, holes CW.
        List<Point2D> outer = normalized(region.getOuter(), true);
        List<List<Point2D>> holes = new ArrayList<>();
        for (List<Point2D> hole : region.getHoles()) {
            holes.add(normalized(hole, false));
        }

        // Node ids: outer loop first, then holes in order.
        Mesh mesh = new Mesh();
        List<Integer> outerIds = addLoop(mesh, outer);
        List<List<Integer>> holeIds = new ArrayList<>();
        for (List<Point2D> hole : holes) {
            holeIds.add(addLoop(mesh, hole));
        }

        // Boundary edges follow the normalized loops; marker 0 = outer, 1..n = holes.
        addBoundaryLoop(mesh, outerIds, 0);
        for (int i = 0; i < holeIds.size(); i++) {
            addBoundaryLoop(mesh, holeIds.get(i), i + 1);
        }

        // Merge holes into one ring via visibility bridges, then ear-clip.
        List<Integer> ring = new ArrayList<>(outerIds);
        for (List<Integer> hole : holeIds) {
            ring = bridge(mesh, ring, hole, holeIds);
        }
        earClip(mesh, ring, token);
        token.checkCancelled();

        // Seeded Steiner pass: one jittered centroid per over-long element.
        int steiner = 0;
        if (options.getMaxEdgeLength() > 0.0) {
            steiner = insertSteinerPoints(mesh, options, token);
        }

        // Enforce edge-length / angle targets by Rivara bisection.
        int bisections = new MeshRefiner().refine(mesh, options.toQualityThresholds(),
                options.getMaxRefinementSteps(), token);

        List<QualityIssue> issues = new MeshQualityChecker().check(mesh, options.toQualityThresholds());
        if (!issues.isEmpty() && options.isFailOnQualityViolation()) {
            throw new MeshValidationException(ErrorCode.QUALITY_VIOLATION,
                    issues.size() + " quality violations, first: " + issues.get(0).message());
        }
        return new GenerationResult(mesh, issues, steiner, bisections, issues.isEmpty());
    }

    private static List<Point2D> normalized(List<Point2D> loop, boolean ccw) {
        boolean isCcw = GeometryUtils.polygonSignedArea(loop) > 0.0;
        if (isCcw == ccw) {
            return loop;
        }
        List<Point2D> reversed = new ArrayList<>(loop);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static List<Integer> addLoop(Mesh mesh, List<Point2D> loop) {
        List<Integer> ids = new ArrayList<>();
        for (Point2D p : loop) {
            ids.add(mesh.addNode(p.x(), p.y()));
        }
        return ids;
    }

    private static void addBoundaryLoop(Mesh mesh, List<Integer> ids, int marker) {
        for (int i = 0; i < ids.size(); i++) {
            mesh.addBoundaryEdge(ids.get(i), ids.get((i + 1) % ids.size()), marker);
        }
    }

    /**
     * Splices a hole into the current ring at the closest mutually visible
     * vertex pair. Deterministic tie-breaking by vertex order.
     */
    private static List<Integer> bridge(Mesh mesh, List<Integer> ring, List<Integer> hole,
                                        List<List<Integer>> allHoles) {
        double bestDist = Double.POSITIVE_INFINITY;
        int bestHole = -1;
        int bestRing = -1;
        for (int hi = 0; hi < hole.size(); hi++) {
            Point2D hp = mesh.point(hole.get(hi));
            for (int ri = 0; ri < ring.size(); ri++) {
                Point2D rp = mesh.point(ring.get(ri));
                double d = hp.distanceTo(rp);
                if (d < bestDist - GeometryUtils.EPS
                        && visible(mesh, hp, rp, hole.get(hi), ring.get(ri), ring, allHoles)) {
                    bestDist = d;
                    bestHole = hi;
                    bestRing = ri;
                }
            }
        }
        if (bestHole < 0) {
            throw new MeshValidationException(ErrorCode.GENERATION_FAILED,
                    "no visible bridge vertex for hole");
        }
        // ring = ring[0..bestRing] + hole rotated from bestHole around + ring[bestRing..]
        List<Integer> merged = new ArrayList<>();
        for (int i = 0; i <= bestRing; i++) {
            merged.add(ring.get(i));
        }
        for (int i = 0; i <= hole.size(); i++) {
            merged.add(hole.get((bestHole + i) % hole.size()));
        }
        for (int i = bestRing; i < ring.size(); i++) {
            merged.add(ring.get(i));
        }
        return merged;
    }

    /** Segment hp-rp must not cross any ring/hole edge except at shared endpoints. */
    private static boolean visible(Mesh mesh, Point2D hp, Point2D rp, int hId, int rId,
                                   List<Integer> ring, List<List<Integer>> allHoles) {
        List<List<Integer>> loops = new ArrayList<>();
        loops.add(ring);
        loops.addAll(allHoles);
        for (List<Integer> loop : loops) {
            for (int i = 0; i < loop.size(); i++) {
                int a = loop.get(i);
                int b = loop.get((i + 1) % loop.size());
                if (a == b || a == hId || a == rId || b == hId || b == rId) {
                    continue;
                }
                if (GeometryUtils.segmentsIntersect(hp, rp, mesh.point(a), mesh.point(b))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Classic ear clipping over a (weakly simple) ring of node ids; emits CCW triangles. */
    private static void earClip(Mesh mesh, List<Integer> ring, CancellationToken token) {
        List<Integer> work = new ArrayList<>(ring);
        int iterations = 0;
        while (work.size() > 3) {
            if ((++iterations & 0x3FF) == 0) {
                token.checkCancelled();
            }
            boolean clipped = false;
            for (int i = 0; i < work.size(); i++) {
                int size = work.size();
                int prev = (i - 1 + size) % size;
                int next = (i + 1) % size;
                int idPrev = work.get(prev);
                int idCur = work.get(i);
                int idNext = work.get(next);
                // Adjacent duplicate ids (bridge endpoints): drop one, emit nothing.
                if (idPrev == idCur || idCur == idNext) {
                    work.remove(i);
                    clipped = true;
                    break;
                }
                double orient = GeometryUtils.orient(mesh.point(idPrev), mesh.point(idCur), mesh.point(idNext));
                if (orient <= GeometryUtils.EPS) {
                    // Collinear vertex lying on the segment: drop it, emit nothing.
                    if (Math.abs(orient) <= GeometryUtils.EPS
                            && GeometryUtils.pointOnSegment(mesh.point(idCur), mesh.point(idPrev), mesh.point(idNext))) {
                        work.remove(i);
                        clipped = true;
                        break;
                    }
                    continue; // reflex vertex: not an ear
                }
                if (isEar(mesh, work, prev, i, next)) {
                    mesh.addElement(idPrev, idCur, idNext);
                    work.remove(i);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                throw new MeshValidationException(ErrorCode.DEGENERATE_GEOMETRY,
                        "ear clipping stalled; ring is degenerate");
            }
        }
        if (work.size() == 3
                && GeometryUtils.orient(mesh.point(work.get(0)), mesh.point(work.get(1)), mesh.point(work.get(2)))
                > GeometryUtils.EPS) {
            mesh.addElement(work.get(0), work.get(1), work.get(2));
        }
    }

    private static boolean isEar(Mesh mesh, List<Integer> ring, int prev, int cur, int next) {
        int a = ring.get(prev);
        int b = ring.get(cur);
        int c = ring.get(next);
        Point2D pa = mesh.point(a);
        Point2D pb = mesh.point(b);
        Point2D pc = mesh.point(c);
        int size = ring.size();
        for (int k = 0; k < size; k++) {
            if (k == prev || k == cur || k == next) {
                continue;
            }
            int id = ring.get(k);
            if (id == a || id == b || id == c) {
                continue; // same vertex via a bridge duplicate
            }
            Point2D p = mesh.point(id);
            if (GeometryUtils.orient(pa, pb, p) > GeometryUtils.EPS
                    && GeometryUtils.orient(pb, pc, p) > GeometryUtils.EPS
                    && GeometryUtils.orient(pc, pa, p) > GeometryUtils.EPS) {
                return false; // another vertex strictly inside
            }
        }
        return true;
    }

    /** Single bounded pass: split each over-long element around a seeded jittered centroid. */
    private static int insertSteinerPoints(Mesh mesh, MeshGenerationOptions options, CancellationToken token) {
        double h = options.getMaxEdgeLength();
        Random rng = new Random(options.getSeed());
        List<com.github.guoyyit.meshgenerationjava.core.Element> targets = new ArrayList<>();
        for (com.github.guoyyit.meshgenerationjava.core.Element e : mesh.elements()) {
            if (GeometryUtils.maxEdgeLength(mesh.point(e.getN0()), mesh.point(e.getN1()), mesh.point(e.getN2()))
                    > 1.5 * h) {
                targets.add(e);
            }
        }
        int inserted = 0;
        for (com.github.guoyyit.meshgenerationjava.core.Element e : targets) {
            token.checkCancelled();
            Point2D a = mesh.point(e.getN0());
            Point2D b = mesh.point(e.getN1());
            Point2D c = mesh.point(e.getN2());
            double cx = (a.x() + b.x() + c.x()) / 3.0;
            double cy = (a.y() + b.y() + c.y()) / 3.0;
            double minOrient = 0.01 * h * h;
            Point2D chosen = null;
            double jitterScale = options.getInteriorJitter() * h;
            for (int attempt = 0; attempt < 8; attempt++) {
                double dx = (rng.nextDouble() * 2.0 - 1.0) * jitterScale;
                double dy = (rng.nextDouble() * 2.0 - 1.0) * jitterScale;
                Point2D p = new Point2D(cx + dx, cy + dy);
                if (GeometryUtils.orient(a, b, p) > minOrient
                        && GeometryUtils.orient(b, c, p) > minOrient
                        && GeometryUtils.orient(c, a, p) > minOrient) {
                    chosen = p;
                    break;
                }
                jitterScale *= 0.5;
            }
            if (chosen == null) {
                chosen = new Point2D(cx, cy);
            }
            int m = mesh.addNode(chosen.x(), chosen.y());
            mesh.removeElement(e.getId());
            mesh.addElement(e.getN0(), e.getN1(), m);
            mesh.addElement(e.getN1(), e.getN2(), m);
            mesh.addElement(e.getN2(), e.getN0(), m);
            inserted++;
        }
        return inserted;
    }
}
