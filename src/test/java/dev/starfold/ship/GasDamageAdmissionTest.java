package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashSet;

class GasDamageAdmissionTest {
    @BeforeAll
    static void boot() throws Exception {
        PlanetBiomeFixtures.boot();
    }

    @Test
    void pressureNeverRemovesTheLastMassOfABody() {
        assertTrue(GasDamageAdmission.leavesMass(4, 2));
        assertTrue(GasDamageAdmission.leavesMass(2.5, 2));
        assertFalse(GasDamageAdmission.leavesMass(2, 2), "Sable would destroy and remove the body");
        assertFalse(GasDamageAdmission.leavesMass(2 + 1e-9, 2));
        assertFalse(GasDamageAdmission.leavesMass(1, 2));
        assertFalse(GasDamageAdmission.leavesMass(Double.NaN, 1));
        assertFalse(GasDamageAdmission.leavesMass(3, Double.POSITIVE_INFINITY));
    }

    @Test
    void exactSimpleFullCubeClassesAllowStructureButNotInventoriesFluidsOrUnknownCallbacks() {
        assertEquals(
                net.minecraft.world.level.block.TransparentBlock.class, Blocks.GLASS.getClass());
        for (var block :
                new Block[] {
                    Blocks.IRON_BLOCK,
                    Blocks.OAK_SLAB,
                    Blocks.BLACKSTONE,
                    Blocks.OAK_LOG,
                    Blocks.GLASS,
                    Blocks.BLUE_STAINED_GLASS
                }) {
            assertTrue(
                    GasDamageAdmission.eligible(
                            block.defaultBlockState(),
                            EmptyBlockGetter.INSTANCE,
                            BlockPos.ZERO,
                            false),
                    block.toString());
            assertFalse(
                    GasDamageAdmission.eligible(
                            block.defaultBlockState(),
                            EmptyBlockGetter.INSTANCE,
                            BlockPos.ZERO,
                            true));
        }
        for (var block :
                new Block[] {
                    Blocks.AIR,
                    Blocks.WATER,
                    Blocks.BEDROCK,
                    Blocks.CHEST,
                    Blocks.HOPPER,
                    Blocks.OAK_STAIRS,
                    Blocks.OAK_BUTTON,
                    Blocks.OBSERVER,
                    Blocks.MAGMA_BLOCK
                })
            assertFalse(
                    GasDamageAdmission.eligible(
                            block.defaultBlockState(),
                            EmptyBlockGetter.INSTANCE,
                            BlockPos.ZERO,
                            false),
                    block.toString());
        class UnknownCallbackBlock extends Block {
            UnknownCallbackBlock() {
                super(BlockBehaviour.Properties.of());
            }
        }
        // Registry is frozen in the hosted harness: exercise the SAME production class gate
        // without trying to register an ad-hoc block after startup.
        assertFalse(GasDamageAdmission.simpleClass(UnknownCallbackBlock.class));
    }

    @Test
    void drySlabsAreStructuralButWaterloggedSlabsRemainProtected() {
        var slab = Blocks.OAK_SLAB.defaultBlockState();
        for (var type : net.minecraft.world.level.block.state.properties.SlabType.values()) {
            var state = slab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, type);
            assertTrue(GasDamageAdmission.eligible(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, false));
            assertFalse(GasDamageAdmission.eligible(state.setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true),
                    EmptyBlockGetter.INSTANCE, BlockPos.ZERO, false));
        }
    }

    @Test
    void marginRequiresTheCandidateAndEveryOffsetTwoNeighborInTheSameLoadedOwner() {
        var pos = new BlockPos(15, 40, -16);
        var expected = new HashSet<BlockPos>();
        expected.add(pos);
        for (var direction : Direction.values()) expected.add(pos.relative(direction, 2));
        var calls = new ArrayList<BlockPos>();
        assertTrue(
                GasDamageAdmission.loadedMargin(
                        pos,
                        p -> {
                            calls.add(p);
                            return expected.contains(p);
                        }));
        assertEquals(7, calls.size());
        assertEquals(expected, new HashSet<>(calls));
        for (var absent : expected) {
            var allowed = new HashSet<>(expected);
            allowed.remove(absent);
            assertFalse(
                    GasDamageAdmission.loadedMargin(pos, allowed::contains),
                    "unloaded/other plot at " + absent);
        }
        assertFalse(
                GasDamageAdmission.loadedMargin(new BlockPos(Integer.MAX_VALUE, 40, 0), p -> true));
        assertFalse(
                GasDamageAdmission.loadedMargin(new BlockPos(0, Integer.MIN_VALUE, 0), p -> true));
    }

    @Test
    void productionAdmissionKeepsOwnerAndNoForceLoadGuardsInTheSharedPath() throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/ship/GasDamageAdmission.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        var checked =
                node.methods.stream()
                        .filter(m -> m.name.equals("checkedChunk"))
                        .findFirst()
                        .orElseThrow();
        var calls = new HashSet<String>();
        var fields = new HashSet<String>();
        for (var i : checked.instructions) {
            if (i instanceof MethodInsnNode m) calls.add(m.name);
            if (i instanceof FieldInsnNode f) fields.add(f.name);
        }
        assertTrue(
                calls.containsAll(
                        java.util.Set.of(
                                "isOutsideBuildHeight",
                                "isRemoved",
                                "getLevel",
                                "getSubLevel",
                                "getContainer",
                                "loadedMargin",
                                "ownedChunk")));
        assertTrue(fields.contains("captureBlockSnapshots"));
        var owned =
                node.methods.stream()
                        .filter(m -> m.name.equals("ownedChunk"))
                        .findFirst()
                        .orElseThrow();
        calls.clear();
        for (var i : owned.instructions) if (i instanceof MethodInsnNode m) calls.add(m.name);
        assertTrue(
                calls.containsAll(
                        java.util.Set.of("contains", "getChunkHolder", "toLocal", "getChunkNow")));
        for (var method : node.methods)
            for (var i : method.instructions)
                if (i instanceof MethodInsnNode m) {
                    assertNotEquals("destroyBlock", m.name);
                    assertNotEquals("addRegionTicket", m.name);
                    assertFalse(
                            m.name.equals("getChunk") && !m.owner.endsWith("PlotChunkHolder"),
                            m.owner);
                }
    }
}
