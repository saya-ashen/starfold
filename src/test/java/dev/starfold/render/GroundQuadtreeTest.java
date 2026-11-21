package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

class GroundQuadtreeTest {
    @Test
    void budgetedTargetsProgressWithoutStarvingRootsDuringContinuousViewRefresh() {
        var tree = new GroundQuadtree(new PlanetShape(2048));
        var visited = new HashSet<Integer>();
        for (int i = 0; i < GroundQuadtree.ROOT_COUNT; i++) {
            tree.requestTargetRefresh();
            int before = visited.size();
            tree.updateChangedTargets(1080, 1.2, n -> 1000, n -> {
                assertEquals(0, GroundQuadtree.depth(n));
                visited.add(n);
                return true;
            }, n -> false, 0);
            assertEquals(before + 1, visited.size(), "zero budget completes exactly one new root");
        }
        assertEquals(GroundQuadtree.ROOT_COUNT, visited.size());
        assertTrue(tree.hasTargetUpdates());
        tree.updateChangedTargets(1080, 1.2, n -> 1000, n -> true, n -> false);
        assertFalse(tree.hasTargetUpdates());
        assertThrows(IllegalArgumentException.class,
                () -> tree.updateChangedTargets(1080, 1.2, n -> 1000, n -> true, n -> false, -1));
    }

    @Test
    void budgetedTargetsConvergeToSynchronousAfterPublicationsEditsAndNativeViewChanges() {
        var local = new GroundQuadtree(new PlanetShape(512));
        var full = new GroundQuadtree(new PlanetShape(512));
        for (int stage = 0; stage < 4; stage++) {
            if (stage == 1) {
                for (int patch : new int[] {0, 1, 8, 64}) {
                    int root = GroundQuadtree.root(patch);
                    upload(local, root, patch + 1); upload(full, root, patch + 1);
                    for (int q = 0; q < 4; q++) {
                        upload(local, GroundQuadtree.child(root,q), 1000 + patch*4 + q);
                        upload(full, GroundQuadtree.child(root,q), 1000 + patch*4 + q);
                    }
                }
            }
            if (stage == 2) {
                local.invalidate(0,0,0,16,16,-1000,1000);
                full.invalidate(0,0,0,16,16,-1000,1000);
            }
            final int phase = stage;
            java.util.function.IntPredicate visible = n -> phase != 3 || GroundQuadtree.face(n) != 1;
            java.util.function.IntPredicate near = n -> phase == 3 && GroundQuadtree.face(n) == 0;
            local.requestTargetRefresh();
            int slices = 0;
            while (local.hasTargetUpdates()) {
                local.updateChangedTargets(1080,1.2,n -> 1000,visible,near,0);
                assertTrue(++slices <= GroundQuadtree.ROOT_COUNT);
                // Pending target work never destroys resident fallback coverage.
                if (stage > 0) assertTrue(local.patchReady(0));
            }
            full.updateTargets(1080,1.2,n -> 1000,visible,near);
            for (int n = 0; n < GroundQuadtree.NODE_COUNT; n++) {
                assertEquals(full.desiredStep(n),local.desiredStep(n));
                assertEquals(full.wantsSplit(n),local.wantsSplit(n));
                assertEquals(full.version(n),local.version(n));
                assertEquals(full.dirty(n),local.dirty(n));
            }
            var a = new GroundQuadtree.Cut(); var b = new GroundQuadtree.Cut();
            var nativeTiles = new BitSet(); if(stage == 3) nativeTiles.set(0);
            local.selectCut(nativeTiles,visible,stage+1,a);
            full.selectCut(nativeTiles,visible,stage+1,b);
            assertArrayEquals(java.util.Arrays.copyOf(a.pure,a.pureCount),java.util.Arrays.copyOf(b.pure,b.pureCount));
            assertArrayEquals(java.util.Arrays.copyOf(a.masked,a.maskedCount),java.util.Arrays.copyOf(b.masked,b.maskedCount));
        }
    }

    @Test
    void pendingViewRefreshDrawsNewlyVisibleRootsAndProtectsTheirDetail() {
        var tree = new GroundQuadtree(new PlanetShape(256));
        int front = GroundQuadtree.root(0), back = GroundQuadtree.root(64);
        tree.target(back, 32, true);
        upload(tree, back, 1);
        for (int q = 0; q < 3; q++) upload(tree, GroundQuadtree.child(back, q), q + 2);
        tree.updateTargets(1080, 1.2, n -> 1000, n -> n == front, n -> false);
        assertEquals(300, tree.offscreenDetailBytes());

        java.util.function.IntPredicate turned = n -> GroundQuadtree.face(n) == 1;
        tree.requestTargetRefresh();
        tree.updateChangedTargets(1080, 1.2, n -> 1000, turned, n -> false, 0);
        assertTrue(tree.hasTargetUpdates());
        assertEquals(0, tree.offscreenDetailBytes());
        assertEquals(0, tree.evictOffscreenToBudget(0, 1, 1, h -> fail("the new view may need this detail")));
        var demand = new HashSet<Integer>();
        tree.visitDemand(turned, demand::add);
        assertTrue(demand.isEmpty(), "a pending root must not start detail from old targets");

        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), turned, 1, cut, 2);
        assertEquals(0, cut.missingCount);
        assertEquals(1, cut.pureCount);
        assertEquals(back, cut.pure[0]);

        tree.updateChangedTargets(1080, 1.2, n -> 1000, turned, n -> false);
        tree.visitDemand(turned, demand::add);
        assertTrue(demand.contains(GroundQuadtree.child(back, 3)));
    }

    @Test
    void resolvedCutEmitsWithoutRetestingCertifiedDescendantsAndRefreshesHiddenBranches() {
        var tree=new GroundQuadtree(new PlanetShape(256));
        int root=GroundQuadtree.root(0);
        tree.target(root,tree.desiredStep(root),true); upload(tree,root,1);
        for(int q=0;q<4;q++) upload(tree,GroundQuadtree.child(root,q),q+2);
        var calls=new int[GroundQuadtree.NODE_COUNT];
        var cut=new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(),n->{calls[n]++;return GroundQuadtree.face(n)==0;},1,cut,1);
        assertEquals(4,cut.pureCount);
        for(int q=0;q<4;q++) assertEquals(1,calls[GroundQuadtree.child(root,q)],
                "resolved descendants must not repeat the visibility predicate during emission");
        int hidden=GroundQuadtree.child(root,0);
        tree.selectCut(new BitSet(),n->GroundQuadtree.face(n)==0 && n!=hidden,2,cut,2);
        assertEquals(3,cut.pureCount);
        for(int i=0;i<cut.pureCount;i++) assertNotEquals(hidden,cut.pure[i]);
        var nativeTiles=new BitSet();nativeTiles.set(0,8); // native changes invalidate the certified choices
        tree.selectCut(nativeTiles,n->GroundQuadtree.face(n)==0,3,cut,2);
        assertTrue(cut.pureCount+cut.maskedCount>0);
    }

    @Test
    void lodDowngradeKeepsVerifiedFineHeightsUntilTerrainChanges() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int leaf = GroundQuadtree.nodeId(0, 4, 0, 0);
        for (int depth = 0; depth < 4; depth++) {
            int n = GroundQuadtree.nodeId(0, depth, 0, 0);
            tree.target(n, tree.desiredStep(n), true);
            publishBounds(tree, n, depth + 1, -20, 10);
        }
        int uploads = 0;
        for (int frame = 0; frame < 20; frame++) {
            tree.updateTargets(2000, Math.PI/2,
                    n -> n == leaf ? 900 - tree.targetMaxY(n) : 1,
                    n -> GroundQuadtree.face(n) == 0 && GroundQuadtree.x(n) == 0 && GroundQuadtree.z(n) == 0,
                    n -> false);
            if (tree.meshHandle(leaf) < 0 || tree.builtStep(leaf) != tree.desiredStep(leaf)) {
                int step = tree.desiredStep(leaf);
                publishBounds(tree, leaf, 100 + uploads++, step == 1 ? -10 : -20, step == 1 ? 0 : 10);
            }
        }
        assertEquals(2, uploads, "fine/coarse publication must settle rather than change its own SSE forever");
        assertEquals(2, tree.builtStep(leaf));
        assertEquals(-20, tree.targetMinY(leaf));
        assertEquals(10, tree.targetMaxY(leaf));
        tree.invalidate(0, 0, 0, 16, 16, -300, 900);
        publishBounds(tree, leaf, 200, -20, 10);
        assertEquals(-200, tree.targetMinY(leaf));
        assertEquals(600, tree.targetMaxY(leaf), "a coarse replacement cannot verify edited fine terrain");
        tree.target(leaf, 1, false);
        publishBounds(tree, leaf, 201, 2, 8);
        assertEquals(2, tree.targetMinY(leaf));
        assertEquals(8, tree.targetMaxY(leaf));
        tree.clear(h -> {});
        assertEquals(600, tree.targetMaxY(leaf));
    }

    @Test
    void publicationUpdatesOnlyItsTargetRootAndMatchesFullTraversal() {
        var local = new GroundQuadtree(new PlanetShape(512));
        var full = new GroundQuadtree(new PlanetShape(512));
        java.util.function.IntToDoubleFunction distance = n -> 1000;
        java.util.function.IntPredicate visible = n -> true, near = n -> false;
        local.updateTargets(1392, 1.2, distance, visible, near);
        full.updateTargets(1392, 1.2, distance, visible, near);
        for (int patch : new int[] {0, 1, 8, 9, 64}) {
            int root = GroundQuadtree.root(patch);
            upload(local, root, patch + 1); upload(full, root, patch + 1);
            var visited = new HashSet<Integer>();
            local.updateChangedTargets(1392, 1.2, n -> { visited.add(n); return 1000; }, visible, near);
            full.updateTargets(1392, 1.2, distance, visible, near);
            assertTrue(visited.size() <= 5, "only the changed drawable frontier should be visited");
            assertTrue(visited.contains(root));
            assertFalse(local.hasTargetUpdates());
            for (int n = 0; n < GroundQuadtree.NODE_COUNT; n++) {
                assertEquals(full.desiredStep(n), local.desiredStep(n));
                assertEquals(full.wantsSplit(n), local.wantsSplit(n));
                assertEquals(full.version(n), local.version(n));
            }
        }
        local.invalidate(0, 0, 0, 16, 16, -900, 900);
        assertTrue(local.hasTargetUpdates());
        local.updateChangedTargets(1392, 1.2, distance, visible, near);
        assertFalse(local.hasTargetUpdates());
        local.clear(h -> {});
        assertTrue(local.hasTargetUpdates());
    }

    @Test
    void cachedRootCutsMatchUncachedAfterPublicationEditsNativeChangesAndViewChanges() {
        var tree = new GroundQuadtree(new PlanetShape(256));
        int root = GroundQuadtree.root(0);
        tree.target(root, 64, true); upload(tree, root, 1);
        var cached = new GroundQuadtree.Cut(); var reference = new GroundQuadtree.Cut();
        var nativeTiles = new BitSet();
        for (int frame = 1; frame <= 8; frame++) {
            if (frame <= 4) upload(tree, GroundQuadtree.child(root, frame - 1), frame + 1);
            if (frame == 5) nativeTiles.set(0);
            if (frame == 6) nativeTiles.clear();
            if (frame == 7) tree.invalidate(0, 0, 0, 16, 16, -1000, 1000);
            java.util.function.IntPredicate visible = frame == 8 ? n -> false : n -> true;
            tree.selectCut(nativeTiles, visible, frame, cached, frame == 8 ? 2 : 1);
            tree.selectCut(nativeTiles, visible, frame, reference);
            assertArrayEquals(java.util.Arrays.copyOf(reference.pure, reference.pureCount),
                    java.util.Arrays.copyOf(cached.pure, cached.pureCount));
            assertArrayEquals(java.util.Arrays.copyOf(reference.masked, reference.maskedCount),
                    java.util.Arrays.copyOf(cached.masked, cached.maskedCount));
            assertArrayEquals(java.util.Arrays.copyOf(reference.missing, reference.missingCount),
                    java.util.Arrays.copyOf(cached.missing, cached.missingCount));
            // Repeat using the certified unchanged predicate to exercise the retained choices.
            tree.selectCut(nativeTiles, visible, frame, cached, frame == 8 ? 2 : 1);
        }
    }

    @Test
    void cachedCutRevisionTracksRealMutationsAndRetainedCommandsProtectTheirRanges() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        long initial = tree.cutRevision();
        tree.target(root, tree.desiredStep(root), false);
        tree.invalidateTiles(new BitSet(), -200, 600);
        assertEquals(initial, tree.cutRevision());
        upload(tree, root, 1);
        assertTrue(tree.cutRevision() > initial);
        long publication = tree.cutRevision();
        tree.target(root, 64, true);
        assertTrue(tree.cutRevision() > publication);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), q + 2);
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 10, cut);
        assertEquals(4, cut.pureCount);
        tree.markUsed(cut, 100);
        assertEquals(0, tree.evictToBudget(0, 100, 0, h -> fail("cached draw range was evicted")));
        var retired = new ArrayList<Integer>();
        long beforeEviction = tree.cutRevision();
        assertEquals(400, tree.evictToBudget(0, 101, 0, retired::add));
        assertEquals(4, retired.size());
        assertTrue(tree.cutRevision() > beforeEviction);
        long beforeEdit = tree.cutRevision();
        var changed = new BitSet(); changed.set(0);
        tree.invalidateTiles(changed, -300, 600);
        assertTrue(tree.cutRevision() > beforeEdit);
    }

    @Test
    void nativeTileChangesOnlyResolveAffectedRootsForTheSameView() {
        var tree = new GroundQuadtree(new PlanetShape(512));
        int changedRoot = GroundQuadtree.root(9);
        int unchangedRoot = GroundQuadtree.root(0);
        for (int root : new int[]{changedRoot, unchangedRoot}) {
            tree.target(root, 64, true);
            upload(tree, root, root + 1);
            for (int q = 0; q < 4; q++) {
                int child = GroundQuadtree.child(root, q);
                upload(tree, child, child + 1);
            }
        }
        var nativeTiles = new BitSet();
        var fresh = new GroundQuadtree.Cut();
        var cached = new GroundQuadtree.Cut();
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 1, cached, 1);
        nativeTiles.set(16 * 128 + 16);
        int unchangedChild = GroundQuadtree.child(unchangedRoot, 0);
        int changedChild = GroundQuadtree.child(changedRoot, 0);
        var tested = new BitSet();
        tree.selectCut(nativeTiles, n -> {
            tested.set(n);
            return GroundQuadtree.face(n) == 0;
        }, 2, cached, 1);
        assertFalse(tested.get(unchangedChild));
        assertTrue(tested.get(changedChild));
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 3, fresh);
        assertArrayEquals(java.util.Arrays.copyOf(fresh.pure, fresh.pureCount),
                java.util.Arrays.copyOf(cached.pure, cached.pureCount));
        assertArrayEquals(java.util.Arrays.copyOf(fresh.masked, fresh.maskedCount),
                java.util.Arrays.copyOf(cached.masked, cached.maskedCount));
        assertArrayEquals(java.util.Arrays.copyOf(fresh.missing, fresh.missingCount),
                java.util.Arrays.copyOf(cached.missing, cached.missingCount));
    }

    @Test
    void stepOnlyTargetKeepsTheDrawRevisionUntilTheReplacementPublishes() {
        var tree = new GroundQuadtree(new PlanetShape(256));
        int root = GroundQuadtree.root(0);
        upload(tree, root, 1);
        long drawRevision = tree.cutRevision();
        long buildVersion = tree.version(root);

        tree.target(root, 32, false);
        assertEquals(drawRevision, tree.cutRevision());
        assertEquals(buildVersion + 1, tree.version(root));
        assertEquals(1, tree.meshHandle(root));
        assertFalse(tree.publish(root, 64, buildVersion, 2, 100, -200, 600, h -> {}));
        assertEquals(drawRevision, tree.cutRevision());

        var retired = new ArrayList<Integer>();
        assertTrue(tree.publish(root, 32, tree.version(root), 3, 100, -200, 600, retired::add));
        assertEquals(List.of(1), retired);
        assertTrue(tree.cutRevision() > drawRevision);
        assertEquals(3, tree.meshHandle(root));
    }

    @Test
    void oneFrameOfOverlappingDirtyTilesAdvancesEachAncestorOnlyOnce() {
        var tree = new GroundQuadtree(new PlanetShape(272), -200, 600);
        var tiles = new BitSet();
        tiles.set(0); tiles.set(1); tiles.set(128);
        int root = GroundQuadtree.root(0), nextRoot = GroundQuadtree.root(1);
        tree.invalidateTiles(tiles, -300, 900);
        assertEquals(1, tree.version(root));
        assertEquals(0, tree.version(nextRoot));
        assertEquals(1, tree.version(GroundQuadtree.nodeId(0, 4, 0, 0)));
        assertEquals(1, tree.version(GroundQuadtree.nodeId(0, 4, 1, 0)));
        assertEquals(1, tree.version(GroundQuadtree.nodeId(0, 4, 0, 1)));
        assertEquals(0, tree.version(GroundQuadtree.nodeId(0, 4, 1, 1)));
        assertEquals(-300, tree.targetMinY(root));
        assertEquals(900, tree.targetMaxY(root));
        tree.invalidateTiles(tiles, -250, 700);
        assertEquals(2, tree.version(root), "the next frame is a new input revision");
        assertEquals(900, tree.targetMaxY(root));
    }

    @Test
    void fixedIdsRoundTripIncludingPartialAndAbsentRoots() {
        var tree = new GroundQuadtree(new PlanetShape(272), -200, 600);
        var seen = new BitSet();
        for (int f = 0; f < 6; f++) for (int d = 0; d < 5; d++) {
            int w = 8 << d;
            for (int z = 0; z < w; z++) for (int x = 0; x < w; x++) {
                int n = GroundQuadtree.nodeId(f, d, x, z);
                assertFalse(seen.get(n)); seen.set(n);
                assertEquals(f, GroundQuadtree.face(n)); assertEquals(d, GroundQuadtree.depth(n));
                assertEquals(x, GroundQuadtree.nx(n)); assertEquals(z, GroundQuadtree.nz(n));
                assertEquals(x * (256 >> d) < 272 && z * (256 >> d) < 272, tree.valid(n));
                if (d > 0) assertEquals(n, GroundQuadtree.child(GroundQuadtree.parent(n), (z & 1) * 2 + (x & 1)));
            }
        }
        assertEquals(130944, seen.cardinality());
        assertEquals(130944, seen.length());
        for (int p = 0; p < 384; p++) assertEquals(p / 64, GroundQuadtree.face(GroundQuadtree.root(p)));
    }

    @Test
    void randomOutOfOrderBuildsNativeChangesAndEditsPreserveExactlyOneOwner() {
        var tree = new GroundQuadtree(new PlanetShape(272), -200, 600);
        var random = new Random(632891);
        var jobs = new ArrayList<Integer>();
        int handle = 0;
        for (int n = 0; n < GroundQuadtree.NODE_COUNT; n++) if (tree.valid(n)) {
            tree.target(n, tree.desiredStep(n), GroundQuadtree.depth(n) < 4);
            if (GroundQuadtree.depth(n) == 0) upload(tree, n, handle++);
            else jobs.add(n);
        }
        Collections.shuffle(jobs, random);
        var nativeTiles = new BitSet();
        var cut = new GroundQuadtree.Cut();
        for (int i = 0; i < jobs.size(); i++) {
            int n = jobs.get(i);
            upload(tree, n, handle++);
            if (i % 19 != 0 && i + 1 != jobs.size()) continue;
            nativeTiles.clear();
            for (int j = 0; j < 35; j++) nativeTiles.set(GroundQuadtree.tile(random.nextInt(6), random.nextInt(17), random.nextInt(17)));
            if (i % 57 == 0) tree.invalidate(random.nextInt(6), 15, 15, 33, 33, -300, 900);
            tree.selectCut(nativeTiles, ignored -> true, i, cut);
            assertTrue(cut.complete());
            assertExactCoverage(tree, nativeTiles, cut);
        }
        // Dirty meshes remain drawable, so every leaf now supplies its tile even after edits.
        tree.selectCut(nativeTiles, ignored -> true, jobs.size(), cut);
        assertEquals(0, cut.maskedCount);
        assertExactCoverage(tree, nativeTiles, cut);
        for (int p = 0; p < 384; p++) assertTrue(tree.patchReady(p));
    }

    @Test
    void parentSwitchWaitsForAllChildrenAndMergeWaitsForCleanParent() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        tree.target(root, 64, true); upload(tree, root, 1);
        var cut = new GroundQuadtree.Cut();
        var nativeTiles = new BitSet();
        for (int q = 0; q < 3; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 1, cut);
        assertTrue(cut.complete()); assertEquals(1, cut.pureCount); assertEquals(root, cut.pure[0]);
        upload(tree, GroundQuadtree.child(root, 3), 13);
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 2, cut);
        assertEquals(4, cut.pureCount);
        tree.invalidate(0, 0, 0, 16, 16, -900, 900);
        tree.target(root, 64, false);
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 3, cut);
        assertEquals(4, cut.pureCount);
        var retired = new ArrayList<Integer>();
        assertTrue(tree.publish(root, 64, tree.version(root), 20, 100, -900, 900, retired::add));
        assertEquals(java.util.List.of(1), retired);
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 4, cut);
        assertEquals(1, cut.pureCount); assertEquals(root, cut.pure[0]);
    }

    @Test
    void invisibleMissingSiblingCannotBlockVisibleDetailAndTurningRestoresFallback() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), missing = GroundQuadtree.child(root, 3);
        tree.target(root, 64, true);
        upload(tree, root, 1);
        for (int q = 0; q < 3; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        var cut = new GroundQuadtree.Cut();
        java.util.function.IntPredicate visible = n -> GroundQuadtree.face(n) == 0 && n != missing;
        tree.selectCut(new BitSet(), visible, 1, cut);
        assertEquals(3, cut.pureCount);
        for (int i = 0; i < cut.pureCount; i++) assertNotEquals(root, cut.pure[i]);
        assertEquals(-1, tree.meshHandle(missing));
        // Turn towards the missing branch: the resident parent must cover it again, without holes.
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 2, cut);
        assertEquals(1, cut.pureCount);
        assertEquals(root, cut.pure[0]);
        upload(tree, missing, 13);
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 3, cut);
        assertEquals(4, cut.pureCount);
    }

    @Test
    void invisibleRootsArePrunedBeforeCoverageTraversalAndNeverBecomeRemoteReady() {
        var tree = new GroundQuadtree(new PlanetShape(2048), -200, 600);
        var cut = new GroundQuadtree.Cut();
        int[] tests = {0};
        tree.selectCut(new BitSet(), n -> { tests[0]++; return false; }, 1, cut);
        assertEquals(GroundQuadtree.ROOT_COUNT, tests[0]);
        assertEquals(0, cut.pureCount);
        assertTrue(cut.complete());
        assertFalse(tree.patchReady(0));
        int root = GroundQuadtree.root(0);
        tree.target(root, 64, true);
        upload(tree, GroundQuadtree.child(root, 0), 1);
        tree.selectCut(new BitSet(), n -> n == root || n == GroundQuadtree.child(root, 0), 2, cut);
        assertEquals(1, cut.pureCount);
        assertEquals(GroundQuadtree.child(root, 0), cut.pure[0]);
        assertFalse(tree.patchReady(0), "screen culling must never manufacture global readiness");
    }

    @Test
    void nativeFallbackBecomesPureAtomicallyAndIsPassSpecific() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0); upload(tree, root, 1);
        var nativeTiles = new BitSet(); nativeTiles.set(GroundQuadtree.tile(0, 4, 4));
        var opaque = new GroundQuadtree.Cut(); var transparent = new GroundQuadtree.Cut();
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 9, opaque);
        assertEquals(1, opaque.maskedCount); assertEquals(0, opaque.pureCount);
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 9, transparent);
        assertEquals(0, transparent.maskedCount); assertEquals(1, transparent.pureCount);
        for (int n = 0; n < GroundQuadtree.NODES_PER_FACE; n++)
            if (tree.valid(n) && GroundQuadtree.depth(n) > 0) upload(tree, n, n + 2);
        tree.selectCut(nativeTiles, n -> GroundQuadtree.face(n) == 0, 10, opaque);
        assertEquals(0, opaque.maskedCount);
        assertEquals(3 + 3 + 3 + 3, opaque.pureCount); // one branch descends along native tile
    }

    @Test
    void noCullingOrNativeMaskCanInventRemoteReadiness() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        var nativeTiles = new BitSet(); nativeTiles.set(0, 6 * 16384);
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(nativeTiles, ignored -> true, 0, cut);
        assertTrue(cut.complete()); assertFalse(tree.patchReady(0));
        tree.selectCut(new BitSet(), ignored -> false, 0, cut);
        assertTrue(cut.complete()); assertFalse(tree.patchReady(0));
        tree.selectCut(new BitSet(), ignored -> true, 0, cut);
        assertEquals(6, cut.missingCount);
    }

    @Test
    void dirtyVersionsRejectLateUploadsAndOldIndependentHandleSurvivesUntilSwap() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int n = GroundQuadtree.nodeId(0, 4, 1, 1);
        upload(tree, n, 7);
        long old = tree.version(n);
        tree.invalidate(0, 16, 16, 32, 32, -400, 800);
        var freed = new ArrayList<Integer>();
        assertFalse(tree.publish(n, 2, old, 8, 20, -400, 800, freed::add));
        assertEquals(7, tree.meshHandle(n)); assertTrue(freed.isEmpty());
        assertTrue(tree.publish(n, 2, tree.version(n), 9, 20, -400, 800, freed::add));
        assertEquals(java.util.List.of(7), freed); assertFalse(tree.dirty(n));
        assertEquals(-400, tree.minY(GroundQuadtree.root(0)));
        long beforeReset = tree.version(n);
        tree.clear(freed::add);
        assertFalse(tree.publish(n, 2, beforeReset, 10, 20, -400, 800, freed::add));
        assertEquals(0, tree.residentBytes()); assertEquals(java.util.List.of(7, 9), freed);
    }

    @Test
    void detailBudgetCannotEvictRootsDirtyParentOrEitherCurrentPass() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0); upload(tree, root, 1);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), q + 2);
        tree.target(root, 64, true);
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 10, cut);
        var freed = new HashSet<Integer>();
        assertEquals(0, tree.evictToBudget(0, 10, 0, freed::add));
        tree.target(root, 64, false);
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 10, cut);
        assertEquals(0, tree.evictToBudget(0, 10, 0, freed::add)); // other pass still pins children
        tree.invalidate(0, 0, 0, 16, 16, -200, 600);
        assertEquals(0, tree.evictToBudget(0, 11, 0, freed::add));
        assertTrue(tree.publish(root, 64, tree.version(root), 20, 100, -200, 600, freed::add));
        assertEquals(400, tree.evictToBudget(0, 11, 0, freed::add));
        assertEquals(0, tree.detailBytes()); assertEquals(20, tree.meshHandle(root)); assertTrue(tree.patchReady(0));
    }

    @Test
    void sseUsesActualHeightAndTwentyPercentHysteresisAndNativeForcesLeaves() {
        assertEquals(2, GroundQuadtree.sse(2, 1000, 2000, Math.PI / 2), 1e-12);
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        // Error 2.5 no longer splits; 3.1 splits, 2.7 retains, 2.3 merges.
        tree.updateTargets(1000, Math.PI / 2, n -> 32 * 500 / 2.5, n -> true, n -> false);
        assertFalse(tree.wantsSplit(root));
        tree.updateTargets(1000, Math.PI / 2, n -> 32 * 500 / 3.1, n -> true, n -> false);
        assertTrue(tree.wantsSplit(root));
        tree.updateTargets(1000, Math.PI / 2, n -> 32 * 500 / 2.7, n -> true, n -> false);
        assertTrue(tree.wantsSplit(root));
        tree.updateTargets(1000, Math.PI / 2, n -> 32 * 500 / 2.3, n -> true, n -> false);
        assertFalse(tree.wantsSplit(root));
        for (int d = 0; d < 4; d++) upload(tree, GroundQuadtree.nodeId(0, d, 0, 0), d + 1);
        tree.updateTargets(1000, Math.PI / 2, n -> 1e9, n -> true, n -> GroundQuadtree.x(n) == 0 && GroundQuadtree.z(n) == 0);
        for (int d = 0; d < 4; d++) assertTrue(tree.wantsSplit(GroundQuadtree.nodeId(0, d, 0, 0)));
    }

    @Test
    void realBoundsTightenButCoarseMeshesDoNotCertifyUnknownTargetTerrain() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), leaf = GroundQuadtree.nodeId(0, 4, 0, 0);
        publishBounds(tree, root, 1, -10, 20);
        assertBounds(tree, root, -10, 20);
        assertEquals(-200, tree.targetMinY(root)); assertEquals(600, tree.targetMaxY(root));
        // A low coarse mesh must not hide demand for an as-yet unbuilt mountain.
        tree.updateTargets(1000, Math.PI / 2, n -> 1000,
                n -> GroundQuadtree.face(n) == 0 && tree.targetMaxY(n) >= 500, n -> true);
        var demand = new HashSet<Integer>();
        tree.visitDemand(n -> GroundQuadtree.face(n) == 0 && tree.targetMaxY(n) >= 500, demand::add);
        assertEquals(2, tree.desiredStep(leaf));
        assertTrue(demand.contains(GroundQuadtree.child(root, 0)));
        assertFalse(demand.contains(leaf)); // its missing coverage parents are built first
        publishBounds(tree, leaf, 2, 5, 8);
        assertBounds(tree, leaf, 5, 8);
        assertEquals(-200, tree.targetMinY(leaf)); assertEquals(600, tree.targetMaxY(leaf));
        tree.target(leaf, 1, false);
        publishBounds(tree, leaf, 3, 6, 7);
        assertEquals(6, tree.targetMinY(leaf)); assertEquals(7, tree.targetMaxY(leaf));
        // Unbuilt siblings still keep the ancestor's target conservative.
        assertEquals(-200, tree.targetMinY(root)); assertEquals(600, tree.targetMaxY(root));
    }

    @Test
    void ancestorRepublishCannotCullResidentDetailAndReplacementCanShrinkAgain() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), high = GroundQuadtree.child(root, 3);
        tree.target(root, 64, true);
        publishBounds(tree, root, 1, 0, 10);
        for (int q = 0; q < 4; q++) publishBounds(tree, GroundQuadtree.child(root, q), 10 + q,
                q == 3 ? -300 : 0, q == 3 ? 900 : 10);
        publishBounds(tree, root, 2, 1, 9);
        assertBounds(tree, root, -300, 900);
        assertEquals(-300, tree.targetMinY(root)); assertEquals(900, tree.targetMaxY(root));
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> tree.maxY(n) >= 800, 1, cut);
        assertEquals(1, cut.pureCount); assertEquals(high, cut.pure[0]);
        publishBounds(tree, high, 20, 2, 8);
        assertBounds(tree, root, 0, 10);
        assertEquals(-200, tree.targetMinY(root)); assertEquals(600, tree.targetMaxY(root));
    }

    @Test
    void dirtyBoundsExpandImmediatelyOldMeshDrawsAndOnlyReplacedGenerationsShrink() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), leaf = GroundQuadtree.nodeId(0, 4, 0, 0);
        publishBounds(tree, root, 1, 0, 10); publishBounds(tree, leaf, 2, -10, 20);
        long stale = tree.version(leaf);
        tree.invalidate(0, 0, 0, 16, 16, -400, 900);
        assertBounds(tree, root, -400, 900); assertBounds(tree, leaf, -400, 900);
        assertEquals(1, tree.meshHandle(root)); assertEquals(2, tree.meshHandle(leaf));
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> GroundQuadtree.face(n) == 0, 1, cut);
        assertEquals(1, cut.pureCount); assertEquals(root, cut.pure[0]);
        assertFalse(tree.publish(leaf, tree.desiredStep(leaf), stale, 3, 100, 0, 1,
                h -> fail("rejected upload must not release anything")));
        assertBounds(tree, leaf, -400, 900);
        publishBounds(tree, root, 4, 1, 9);
        assertBounds(tree, root, -400, 900); // dirty resident descendant still encloses its edit
        publishBounds(tree, leaf, 5, 2, 8);
        assertBounds(tree, root, 1, 9); assertBounds(tree, leaf, 2, 8);
        // The same rule works when ancestors have no mesh of their own.
        var sparse = new GroundQuadtree(new PlanetShape(256), -200, 600);
        publishBounds(sparse, leaf, 1, 3, 7);
        sparse.invalidate(0, 0, 0, 16, 16, -500, 1000);
        assertBounds(sparse, root, -500, 1000);
        publishBounds(sparse, leaf, 2, 4, 6);
        assertBounds(sparse, root, 4, 6);
    }

    @Test
    void targetBoundsTightenOnlyWithCompleteFineLeafEvidenceIncludingPartialRoots() {
        var tree = new GroundQuadtree(new PlanetShape(272), -200, 600);
        // The corner root contains exactly one valid leaf, while its other fixed slots are absent.
        int root = GroundQuadtree.nodeId(0, 0, 1, 1), leaf = GroundQuadtree.nodeId(0, 4, 16, 16);
        publishBounds(tree, root, 1, 1, 9);
        publishBounds(tree, leaf, 2, 2, 8);
        assertEquals(-200, tree.targetMinY(root)); assertEquals(600, tree.targetMaxY(root));
        tree.target(leaf, 1, false); publishBounds(tree, leaf, 3, 3, 7);
        assertEquals(1, tree.targetMinY(root)); assertEquals(9, tree.targetMaxY(root));
        // The older coarse mesh must remain inside both envelopes until it too is replaced.
        publishBounds(tree, root, 4, 4, 6);
        assertBounds(tree, root, 3, 7);
        assertEquals(3, tree.targetMinY(root)); assertEquals(7, tree.targetMaxY(root));
        var sparse = new GroundQuadtree(new PlanetShape(272), -200, 600);
        sparse.target(leaf, 1, false); publishBounds(sparse, leaf, 1, 3, 7);
        sparse.invalidate(0, 256, 256, 272, 272, -500, 1000);
        assertEquals(-500, sparse.targetMinY(root)); assertEquals(1000, sparse.targetMaxY(root));
        publishBounds(sparse, leaf, 2, 4, 6);
        assertBounds(sparse, root, 4, 6);
        assertEquals(4, sparse.targetMinY(root)); assertEquals(6, sparse.targetMaxY(root));
    }

    @Test
    void clearRestoresInitialBoundsAndCancelsEvenAbsentNodeTickets() {
        var tree = new GroundQuadtree(new PlanetShape(272), -123, 456);
        int root = GroundQuadtree.root(0), absentMesh = GroundQuadtree.child(root, 2);
        publishBounds(tree, root, 1, 0, 1);
        tree.invalidate(0, 0, 0, 272, 272, -1000, 2000);
        long ticket = tree.version(absentMesh);
        var freed = new ArrayList<Integer>();
        tree.clear(freed::add);
        assertEquals(java.util.List.of(1), freed);
        assertEquals(0, tree.residentBytes()); assertEquals(0, tree.detailBytes());
        for (int n = 0; n < GroundQuadtree.NODE_COUNT; n++) {
            assertBounds(tree, n, -123, 456);
            assertEquals(-123, tree.targetMinY(n)); assertEquals(456, tree.targetMaxY(n));
            assertEquals(-1, tree.meshHandle(n)); assertEquals(tree.valid(n), tree.dirty(n));
        }
        assertFalse(tree.publish(absentMesh, tree.desiredStep(absentMesh), ticket, 2, 100, 0, 1,
                h -> fail("stale ticket")));
        tree.clear(h -> fail("double release"));
    }

    @Test
    void warmPressureCanReach96MiBBeforeDrawingWithoutWaiting120Frames() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        for (int f = 0; f < 6; f++) upload(tree, GroundQuadtree.root(f * 64), f + 1);
        int root = GroundQuadtree.root(0);
        tree.target(root, 64, true);
        for (int q = 0; q < 4; q++) {
            int n = GroundQuadtree.child(root, q);
            assertTrue(tree.publish(n, tree.desiredStep(n), tree.version(n), 10 + q, 32L << 20,
                    -300, 900, h -> fail("initial upload")));
        }
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> true, 30, cut);
        assertExactCoverage(tree, new BitSet(), cut);
        var freed = new HashSet<Integer>();
        // Keep this pressure regression at 96 MiB even when the production allowance grows.
        assertEquals(0, tree.evictToBudget(96L << 20, 30, 120, freed::add));
        // Previous-frame detail is warm, but can be replaced by the clean parent before submission.
        assertEquals(128L << 20, tree.evictToBudget(96L << 20, 31, 120, freed::add));
        assertEquals(java.util.Set.of(10, 11, 12, 13), freed);
        assertEquals(0, tree.detailBytes()); assertEquals(1, tree.meshHandle(root));
        assertFalse(tree.wantsSplit(root)); assertBounds(tree, root, -200, 600);
        var nativeTiles = new BitSet(); nativeTiles.set(GroundQuadtree.tile(0, 1, 1));
        tree.selectCut(nativeTiles, n -> true, 31, cut);
        assertTrue(cut.complete()); assertEquals(1, cut.maskedCount);
        assertExactCoverage(tree, nativeTiles, cut);
        for (int p = 0; p < GroundQuadtree.ROOT_COUNT; p++) assertTrue(tree.patchReady(p));
    }

    @Test
    void pressurePrioritizesLowSseSiblingGroupAndCancelsAbsentDescendantBuilds() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        for (int f = 0; f < 6; f++) upload(tree, GroundQuadtree.root(f * 64), f + 1);
        int high = GroundQuadtree.root(0), low = GroundQuadtree.root(64);
        for (int f = 0; f < 2; f++) {
            int root = GroundQuadtree.root(f * 64);
            tree.target(root, 64, true);
            for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + f * 4 + q);
        }
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> true, 10, cut);
        int absent = GroundQuadtree.child(GroundQuadtree.child(low, 0), 0);
        long ticket = tree.version(absent);
        tree.updateTargets(1000, Math.PI / 2, n -> GroundQuadtree.face(n) == 1 ? 1e6 : 100,
                n -> true, n -> true);
        var freed = new HashSet<Integer>();
        assertEquals(400, tree.evictToBudget(400, 11, 120, freed::add));
        assertEquals(java.util.Set.of(14, 15, 16, 17), freed);
        assertTrue(tree.meshHandle(GroundQuadtree.child(high, 0)) >= 0);
        assertEquals(-1, tree.meshHandle(GroundQuadtree.child(low, 0)));
        assertFalse(tree.publish(absent, tree.desiredStep(absent), ticket, 30, 100, 0, 10,
                h -> fail("late collapsed build")));
        tree.selectCut(new BitSet(), n -> true, 11, cut);
        assertExactCoverage(tree, new BitSet(), cut);
        var demand = new HashSet<Integer>(); tree.visitDemand(n -> true, demand::add);
        assertFalse(demand.contains(absent));
    }

    @Test
    void evictToBudgetPrioritizesPreferredFaceBeforeOtherFaces() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int otherRoot = GroundQuadtree.root(0), preferredRoot = GroundQuadtree.root(64);
        for (int f = 0; f < 2; f++) {
            int root = GroundQuadtree.root(f * 64);
            tree.target(root, 64, true);
            upload(tree, root, f + 1);
            for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + f * 4 + q);
        }
        assertEquals(800, tree.detailBytes());
        var freed = new HashSet<Integer>();
        assertEquals(400, tree.evictToBudget(400, 1, 0, 1, freed::add));
        assertEquals(java.util.Set.of(14, 15, 16, 17), freed);
        assertEquals(400, tree.detailBytes());
        for (int q = 0; q < 4; q++) {
            assertEquals(-1, tree.meshHandle(GroundQuadtree.child(preferredRoot, q)));
            assertEquals(10 + q, tree.meshHandle(GroundQuadtree.child(otherRoot, q)));
        }
        assertEquals(1, tree.meshHandle(otherRoot));
        assertEquals(2, tree.meshHandle(preferredRoot));
    }

    @Test
    void deferNodeSuppressesFixedCameraReselectionUntilCloser() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), child = GroundQuadtree.child(root, 0);
        java.util.function.IntPredicate visible = n -> GroundQuadtree.face(n) == 0;
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        upload(tree, root, 1);
        var demand = new HashSet<Integer>();
        tree.visitDemand(visible, demand::add);
        assertTrue(demand.contains(child));
        assertTrue(tree.hasTargetUpdates(), "publication must reopen the root for incremental selection");
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        assertFalse(tree.hasTargetUpdates(), "consume publication before testing rejection at a fixed camera");

        tree.deferNode(child);
        assertTrue(tree.hasTargetUpdates(), "deferral must reopen an already clean target cache");
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        demand.clear();
        tree.visitDemand(visible, demand::add);
        assertFalse(demand.contains(child), "unchanged SSE must not retry the deferred child");
        assertFalse(tree.wantsSplit(root));
        assertFalse(tree.hasTargetUpdates());

        tree.updateTargets(1000, Math.PI / 2, n -> 500, visible, n -> false);
        demand.clear();
        tree.visitDemand(visible, demand::add);
        assertTrue(tree.wantsSplit(root));
        assertTrue(demand.contains(child), "higher SSE must reconsider the deferred child");
    }

    @Test
    void admissionEvictionInvalidatesPendingDescendantButRetainsRootFallback() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), parent = GroundQuadtree.child(root, 0);
        int pending = GroundQuadtree.child(parent, 0), sibling = GroundQuadtree.child(parent, 1);
        tree.target(root, 64, true);
        tree.target(parent, 16, true);
        upload(tree, root, 1);
        upload(tree, parent, 2);
        upload(tree, sibling, 3);
        long version = tree.version(pending);
        int step = tree.desiredStep(pending);

        assertEquals(200, tree.evictToBudget(0, 1, 0, 0, h -> {}));
        assertNotEquals(version, tree.version(pending));
        assertFalse(tree.publish(pending, step, version, 4, 100, -200, 600, h -> {}));
        assertEquals(1, tree.meshHandle(root), "pressure reclaim must retain the root fallback");
    }

    @Test
    void visiblePressureRecoversOnTurnOrMoreSpaceButNeverRetriesBlindly() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0), child = GroundQuadtree.child(root, 0);
        java.util.function.IntPredicate visible = n -> GroundQuadtree.face(n) == 0;
        tree.target(root, 32, true); upload(tree, root, 1);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        assertEquals(400, tree.evictToBudget(0, 2, 0, h -> {}));
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        assertFalse(tree.wantsSplit(root));
        assertEquals(0, tree.recoverPressure(7, 399, 4), "not enough room for the lost sibling group");
        assertEquals(1, tree.recoverPressure(7, 16L << 20, 4));
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        assertTrue(tree.wantsSplit(root));
        assertEquals(1, tree.meshHandle(root), "fallback must remain drawable during restoration");
        tree.deferNode(child, 100);
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        for (int i = 0; i < 100; i++) assertEquals(0, tree.recoverPressure(7, 16L << 20, 4));
        assertEquals(1, tree.recoverPressure(7, 24L << 20, 4), "actual additional room can recover at a fixed view");
        tree.deferNode(child, 100);
        assertEquals(0, tree.recoverPressure(8, 0, 4), "a turn cannot invent memory");
        assertEquals(1, tree.recoverPressure(8, 16L << 20, 4), "returning view retries at unchanged SSE");
    }

    @Test
    void recoveryBatchHonorsGroupLimitAndCumulativeRoomWhileRetainingFallbacks() {
        var tree = new GroundQuadtree(new PlanetShape(2048), -200, 600);
        int[] roots = {GroundQuadtree.root(0), GroundQuadtree.root(1), GroundQuadtree.root(2)};
        for (int root : roots) {
            tree.target(root, 32, true); upload(tree, root, root + 1);
            for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), root + 10 + q);
        }
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, n -> GroundQuadtree.face(n) == 0, n -> false);
        assertEquals(1200, tree.evictToBudget(0, 2, 0, h -> {}));
        tree.updateChangedTargets(1000, Math.PI / 2, n -> 1000, n -> GroundQuadtree.face(n) == 0, n -> false);
        assertEquals(1, tree.recoverPressure(7, 1200, 1));
        assertEquals(1, tree.recoverPressure(7, 799, 6), "space is reserved across a batch");
        assertEquals(1, tree.recoverPressure(7, 400, 6));
        assertEquals(0, tree.recoverPressure(7, 1200, 6));
        for (int root : roots) assertEquals(root + 1, tree.meshHandle(root));
    }

    @Test
    void admissionReclaimsOffscreenGeometryWithoutEvictingCurrentView() {
        var tree = new GroundQuadtree(new PlanetShape(256));
        int front = GroundQuadtree.root(0), back = GroundQuadtree.root(64);
        for (int root : new int[] {front, back}) {
            tree.target(root, 32, true); upload(tree, root, root + 1);
            for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), root + 10 + q);
        }
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, n -> GroundQuadtree.face(n) == 0, n -> false);
        assertEquals(400, tree.evictOffscreenToBudget(0, 10, 0, h -> {}));
        for (int q = 0; q < 4; q++) {
            assertTrue(tree.meshHandle(GroundQuadtree.child(front, q)) >= 0);
            assertEquals(-1, tree.meshHandle(GroundQuadtree.child(back, q)));
        }
        assertEquals(0, tree.evictOffscreenToBudget(0, 11, 0, h -> fail("visible detail must not churn")));
    }

    @Test
    void pressureCollapseConvergesUntilErrorGrowsOrNativeHandoffNeedsDetail() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        tree.target(root, 32, true);
        upload(tree, root, 1);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, n -> GroundQuadtree.face(n) == 0,
                n -> false);
        assertTrue(tree.wantsSplit(root));
        assertEquals(400, tree.evictToBudget(0, 2, 0, h -> {}));
        for (int frame = 0; frame < 20; frame++) {
            tree.updateTargets(1000, Math.PI / 2, n -> 1000,
                    n -> GroundQuadtree.face(n) == 0, n -> false);
            var demand = new HashSet<Integer>();
            tree.visitDemand(n -> GroundQuadtree.face(n) == 0, demand::add);
            assertTrue(demand.isEmpty(), "unchanged camera must not rebuild evicted children");
            assertFalse(tree.wantsSplit(root));
        }
        tree.updateTargets(1000, Math.PI / 2, n -> 1000,
                n -> GroundQuadtree.face(n) == 0, n -> n == root);
        assertTrue(tree.wantsSplit(root), "native handoff overrides pressure fallback");
        tree.updateTargets(1000, Math.PI / 2, n -> 500,
                n -> GroundQuadtree.face(n) == 0, n -> false);
        assertTrue(tree.wantsSplit(root), "approaching the region reconsiders detail");
        var demand = new HashSet<Integer>();
        tree.visitDemand(n -> GroundQuadtree.face(n) == 0, demand::add);
        assertEquals(4, demand.size());
    }

    @Test
    void unchangedNativeIntersectionDoesNotRestartPressureEvictedDetail() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        java.util.function.IntPredicate visible = n -> GroundQuadtree.face(n) == 0;
        java.util.function.IntPredicate near = n -> n == root;
        tree.target(root, 32, true);
        upload(tree, root, 1);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, near);
        assertTrue(tree.wantsSplit(root));
        assertEquals(400, tree.evictToBudget(0, 2, 0, h -> {}));
        for (int frame = 0; frame < 20; frame++) {
            tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, near);
            assertFalse(tree.wantsSplit(root), "same native requirement cannot defeat budget pressure every frame");
            var demand = new HashSet<Integer>();
            tree.visitDemand(visible, demand::add);
            assertTrue(demand.isEmpty());
            var cut = new GroundQuadtree.Cut();
            var nativeTiles = new BitSet(); nativeTiles.set(0);
            tree.selectCut(nativeTiles, visible, 3 + frame, cut);
            assertTrue(cut.complete());
            assertEquals(1, cut.maskedCount, "coarse fallback must retain masked native coverage");
        }
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, n -> false);
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, near);
        assertTrue(tree.wantsSplit(root), "new native intersection must restore refinement");
        tree.deferNode(GroundQuadtree.child(root, 0));
        tree.updateTargets(1000, Math.PI / 2, n -> 1000, visible, near);
        assertFalse(tree.wantsSplit(root), "native admission rejection must also stay deferred");
        tree.updateTargets(1000, Math.PI / 2, n -> 500, visible, near);
        assertTrue(tree.wantsSplit(root), "approach must retry even an unchanged native branch");
    }

    @Test
    void pressureCollapseUsesPostEvictionBoundsBeforeArmingItsSseThreshold() {
        var tree = new GroundQuadtree(new PlanetShape(256), -1030, 192);
        int parent = GroundQuadtree.root(0);
        int handle = 1;
        for (int d = 0; d <= 3; d++) {
            tree.target(parent, d == 0 ? 32 : d == 1 ? 16 : d == 2 ? 8 : 2, true);
            assertTrue(tree.publish(parent, tree.desiredStep(parent), tree.version(parent),
                    handle++, 100, -1030, 12, h -> fail("initial upload")));
            if (d < 3) parent = GroundQuadtree.child(parent, 0);
        }
        final int branch = parent;
        for (int q = 0; q < 4; q++) {
            int leaf = GroundQuadtree.child(branch, q);
            tree.target(leaf, 1, false);
            assertTrue(tree.publish(leaf, 1, tree.version(leaf), handle++, 100, 0, 12,
                    h -> fail("initial upload")));
        }
        java.util.function.IntPredicate visible = n -> GroundQuadtree.face(n) == 0;
        double[] approach = {1};
        java.util.function.IntToDoubleFunction distance = n -> approach[0]
                * (n == branch ? (tree.targetMaxY(n) == 12 ? 100 : 70) : 100);
        tree.updateTargets(1000, Math.PI / 2, distance, visible, n -> false);
        assertEquals(12, tree.targetMaxY(branch));
        assertTrue(tree.wantsSplit(branch));
        long version = tree.version(GroundQuadtree.child(branch, 0));
        long before = tree.detailBytes();
        // A tiny explicit budget tests real pressure independently of production defaults.
        assertEquals(400, tree.evictToBudget(before - 400, 1, 0, h -> {}));
        assertEquals(192, tree.targetMaxY(branch));
        assertEquals(-1, tree.meshHandle(GroundQuadtree.child(branch, 0)));
        assertTrue(tree.version(GroundQuadtree.child(branch, 0)) > version);
        for (int frame = 0; frame < 20; frame++) {
            if (frame % 2 == 0) tree.updateChangedTargets(1000, Math.PI / 2, distance, visible, n -> false);
            else tree.updateTargets(1000, Math.PI / 2, distance, visible, n -> false);
            assertFalse(tree.wantsSplit(branch), "bounds expansion is not camera approach");
            var demand = new HashSet<Integer>();
            tree.visitDemand(visible, demand::add);
            for (int q = 0; q < 4; q++) assertFalse(demand.contains(GroundQuadtree.child(branch, q)));
        }
        // Looking away and back must retain the pressure decision.
        tree.updateTargets(1000, Math.PI / 2, distance, n -> false, n -> false);
        tree.updateTargets(1000, Math.PI / 2, distance, visible, n -> false);
        assertFalse(tree.wantsSplit(branch));
        approach[0] = .5;
        tree.updateTargets(1000, Math.PI / 2, distance, visible, n -> false);
        assertTrue(tree.wantsSplit(branch), "actual approach must still restore detail");
    }

    @Test
    void pressurePrefersOffscreenGroupsAndNeverReleasesEitherPassSelectedRanges() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        for (int f = 0; f < 6; f++) upload(tree, GroundQuadtree.root(f * 64), f + 1);
        for (int f = 0; f < 2; f++) {
            int root = GroundQuadtree.root(f * 64); tree.target(root, 64, true);
            for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + f * 4 + q);
        }
        tree.updateTargets(1000, Math.PI / 2, n -> 1e6, n -> GroundQuadtree.face(n) == 0, n -> true);
        var freed = new HashSet<Integer>();
        assertEquals(400, tree.evictToBudget(400, 20, 120, freed::add));
        assertEquals(java.util.Set.of(14, 15, 16, 17), freed);
        int root = GroundQuadtree.root(0);
        var opaque = new GroundQuadtree.Cut(); var translucent = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> true, 20, opaque);
        tree.target(root, tree.desiredStep(root), false);
        tree.selectCut(new BitSet(), n -> true, 20, translucent);
        assertEquals(0, tree.evictToBudget(0, 20, 120, freed::add));
        for (int i = 0; i < opaque.pureCount; i++) assertFalse(freed.contains(tree.meshHandle(opaque.pure[i])));
        assertExactCoverage(tree, new BitSet(), opaque); assertExactCoverage(tree, new BitSet(), translucent);
        assertEquals(400, tree.evictToBudget(0, 21, 120, freed::add));
        tree.selectCut(new BitSet(), n -> true, 21, opaque);
        assertExactCoverage(tree, new BitSet(), opaque);
    }

    @Test
    void pressureKeepsCoverageWhenFallbackIsAbsentOrDirtyAndHandlesNestedGroupsOnce() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(root, q), 10 + q);
        var freed = new ArrayList<Integer>();
        assertTrue(tree.patchReady(0));
        assertEquals(0, tree.evictToBudget(0, 1, 120, freed::add)); // no resident fallback
        upload(tree, root, 1);
        tree.invalidate(0, 0, 0, 16, 16, -500, 900);
        assertEquals(0, tree.evictToBudget(0, 1, 120, freed::add)); // dirty fallback
        assertTrue(tree.publish(root, tree.desiredStep(root), tree.version(root), 2, 100,
                -20, 20, freed::add));
        int child = GroundQuadtree.child(root, 1);
        for (int q = 0; q < 4; q++) upload(tree, GroundQuadtree.child(child, q), 20 + q);
        assertEquals(800, tree.evictToBudget(0, 1, 120, freed::add));
        assertEquals(freed.size(), new HashSet<>(freed).size());
        assertEquals(java.util.Set.of(1, 10, 11, 12, 13, 20, 21, 22, 23), new HashSet<>(freed));
        assertEquals(2, tree.meshHandle(root)); assertTrue(tree.patchReady(0));
        assertBounds(tree, root, -20, 20);
        assertEquals(0, tree.evictToBudget(0, 2, 120, h -> fail("double release")));
    }

    @Test
    void uncoveredSiblingShortCircuitsTheUnusableSubtree() {
        var tree = new GroundQuadtree(new PlanetShape(2048), -1030, 192);
        int root = GroundQuadtree.root(0);
        upload(tree, root, 1);
        tree.target(root, tree.desiredStep(root), true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var cut = new GroundQuadtree.Cut();
        tree.selectCut(new BitSet(), n -> {
            calls.incrementAndGet();
            return GroundQuadtree.face(n) == 0 && GroundQuadtree.x(n) < 256
                    && GroundQuadtree.z(n) < 256;
        }, 1, cut);
        assertEquals(1, cut.pureCount);
        assertEquals(root, cut.pure[0]);
        assertTrue(calls.get() < 420, "absent siblings must not traverse their descendants");
    }

    @Test
    void demandBuildsCoverageFrontierBeforeItsAbsentGrandchildren() {
        var tree = new GroundQuadtree(new PlanetShape(256), -200, 600);
        int root = GroundQuadtree.root(0);
        tree.updateTargets(1000, Math.PI / 2, n -> 1, n -> GroundQuadtree.face(n) == 0,
                n -> false);
        var demand = new HashSet<Integer>();
        tree.visitDemand(n -> GroundQuadtree.face(n) == 0, demand::add);
        assertEquals(java.util.Set.of(root), demand);
        upload(tree, root, 1);
        tree.updateTargets(1000, Math.PI / 2, n -> 1, n -> GroundQuadtree.face(n) == 0,
                n -> false);
        demand.clear();
        tree.visitDemand(n -> GroundQuadtree.face(n) == 0, demand::add);
        assertEquals(5, demand.size()); // optional root step upgrade plus the four usable children
        assertTrue(demand.contains(root));
        for (int q = 0; q < 4; q++) assertTrue(demand.contains(GroundQuadtree.child(root, q)));
        assertTrue(tree.wantsSplit(GroundQuadtree.child(root, 0)), "final SSE target retained");
    }

    private static void assertBounds(GroundQuadtree tree, int node, int min, int max) {
        assertEquals(min, tree.minY(node)); assertEquals(max, tree.maxY(node));
    }
    private static void publishBounds(GroundQuadtree tree, int node, int handle, int min, int max) {
        assertTrue(tree.publish(node, tree.desiredStep(node), tree.version(node), handle, 100, min, max, h -> {}));
    }
    private static void upload(GroundQuadtree t, int n, int handle) {
        assertTrue(t.publish(n, t.desiredStep(n), t.version(n), handle, 100, -200, 600, h -> fail("unexpected replacement")));
    }
    private static void assertExactCoverage(GroundQuadtree tree, BitSet nativeTiles, GroundQuadtree.Cut cut) {
        int chunks = tree.shape().chunks();
        int[] count = new int[6 * 16384];
        for (int f = 0; f < 6; f++) for (int z = 0; z < chunks; z++) for (int x = 0; x < chunks; x++)
            if (nativeTiles.get(GroundQuadtree.tile(f, x, z))) count[GroundQuadtree.tile(f, x, z)]++;
        for (int group = 0; group < 2; group++) {
            int[] nodes = group == 0 ? cut.pure : cut.masked;
            int length = group == 0 ? cut.pureCount : cut.maskedCount;
            for (int i = 0; i < length; i++) {
                int n = nodes[i], x0 = GroundQuadtree.x(n) / 16, z0 = GroundQuadtree.z(n) / 16;
                for (int z = z0; z < Math.min(chunks, z0 + GroundQuadtree.size(n) / 16); z++)
                    for (int x = x0; x < Math.min(chunks, x0 + GroundQuadtree.size(n) / 16); x++) {
                        int tile = GroundQuadtree.tile(GroundQuadtree.face(n), x, z);
                        if (group == 0) assertFalse(nativeTiles.get(tile));
                        if (group == 0 || !nativeTiles.get(tile)) count[tile]++;
                    }
            }
        }
        for (int f = 0; f < 6; f++) for (int z = 0; z < chunks; z++) for (int x = 0; x < chunks; x++)
            assertEquals(1, count[GroundQuadtree.tile(f, x, z)], "tile ownership");
    }
}
