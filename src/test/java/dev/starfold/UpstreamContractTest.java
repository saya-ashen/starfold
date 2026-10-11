package dev.starfold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Bytecode inspection only: no Minecraft bootstrap, server, world or client is started. */
class UpstreamContractTest {
    private static ClassNode read(String name) throws IOException {
        try (var stream = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static void assertMethod(String owner, String name, String descriptor) throws IOException {
        assertTrue(read(owner).methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)),
                owner + "." + name + descriptor);
    }

    @Test void vanillaBakedModelAndAtlasApisMatchPinnedGame() throws IOException {
        assertMethod("net/minecraft/client/resources/model/BakedModel","getQuads",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;");
        assertMethod("net/minecraft/client/renderer/block/model/BakedQuad","getVertices","()[I");
        assertMethod("net/minecraft/client/renderer/texture/TextureAtlasSprite","getU","(F)F");
        assertMethod("net/minecraft/client/renderer/GameRenderer","getPositionTexColorShader","()Lnet/minecraft/client/renderer/ShaderInstance;");
    }
    @Test void foldClientInjectionTargetsExist() throws IOException {
        assertMethod("net/minecraft/client/renderer/GameRenderer","pick","(F)V");
        assertMethod("net/minecraft/client/Minecraft","startAttack","()Z");
        assertMethod("net/minecraft/client/Minecraft","startUseItem","()V");
        assertMethod("net/minecraft/client/Minecraft","continueAttack","(Z)V");
        assertMethod("net/minecraft/client/player/LocalPlayer","drop","(Z)Z");
        assertMethod("net/minecraft/client/renderer/LevelRenderer","renderHitOutline",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
    }
    @Test void foldRigidRenderFiltersTargetFirstIterableArgument() throws IOException {
        var type=read("dev/ryanhcode/sable/sublevel/render/dispatcher/VanillaSubLevelRenderDispatcher");
        for(String name:new String[]{"renderSectionLayer","renderAfterSections","renderBlockEntities"})
            assertTrue(type.methods.stream().anyMatch(m->m.name.equals(name) && m.desc.startsWith("(Ljava/lang/Iterable;")),name);
    }
    @Test void fluidDestinationChecksWrapOriginalVanillaAndSablePredicates() throws IOException {
        var fluid=read("net/minecraft/world/level/material/FlowingFluid");
        for(String name:new String[]{"spread","spreadToSides"}) {
            var method=fluid.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
            int calls=0;
            for(var instruction:method.instructions)
                if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("canSpreadTo")) calls++;
            assertEquals(1,calls,name);
        }
        var sable=read("dev/ryanhcode/sable/mixin/fluids_on_sub_levels/FlowingFluidMixin");
        var predicate=sable.methods.stream().filter(m->m.name.equals("sable$canSpreadTo")).findFirst().orElseThrow();
        boolean bounds=false,refusal=false;
        for(var instruction:predicate.instructions) if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            bounds |= call.name.equals("getBoundingBox"); refusal |= call.name.equals("setReturnValue");
        }
        assertTrue(bounds); assertTrue(refusal);
    }




    @Test void adjacencyReadWriteAndSchedulerTargetsExist() throws IOException {
        String pos="Lnet/minecraft/core/BlockPos;";
        assertMethod("net/minecraft/world/level/Level","getBlockState","("+pos+")Lnet/minecraft/world/level/block/state/BlockState;");
        assertMethod("net/minecraft/world/level/Level","getFluidState","("+pos+")Lnet/minecraft/world/level/material/FluidState;");
        assertMethod("net/minecraft/world/level/Level","setBlock","("+pos+"Lnet/minecraft/world/level/block/state/BlockState;II)Z");
        for (String priority : new String[]{"", "Lnet/minecraft/world/ticks/TickPriority;"})
            assertMethod("net/minecraft/world/level/LevelAccessor","createTick","("+pos+"Ljava/lang/Object;I"+priority+")Lnet/minecraft/world/ticks/ScheduledTick;");
        assertMethod("net/minecraft/world/level/material/FlowingFluid","tick","(Lnet/minecraft/world/level/Level;"+pos+"Lnet/minecraft/world/level/material/FluidState;)V");
    }

    @Test void externalMixinTargetsExistInPinnedRelease() throws IOException {
        String root = "dev/ryanhcode/sable/";
        String[][] targets = {
            {"api/entity/EntitySubLevelUtil", "shouldKick", "(Lnet/minecraft/world/entity/Entity;)Z"},
            {"sublevel/system/SubLevelPhysicsSystem", "tick", "(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"},
            {"sublevel/ServerSubLevel", "tick", "()V"},
            {"sublevel/plot/heat/SubLevelHeatMapManager", "tick", "()V"}
        };
        for (String[] target : targets)
            assertTrue(read(root + target[0]).methods.stream().anyMatch(m -> m.name.equals(target[1]) && m.desc.equals(target[2])), target[0]);
        assertTrue(read(root + "sublevel/plot/heat/SubLevelHeatMapManager").fields.stream()
                .anyMatch(f -> f.name.equals("subLevel") && f.desc.equals("Ldev/ryanhcode/sable/sublevel/ServerSubLevel;")));
    }

    @Test void loaderSatisfiesDependencyMetadataIncludingEmbeddedVeil() throws Exception {
        var mods = net.neoforged.fml.ModList.get();
        var loader = mods.getModContainerById("neoforge").orElseThrow().getModInfo().getVersion();
        boolean sawVeil = false;
        for (var metadata : mods.getMods()) {
            sawVeil |= metadata.getModId().equals("veil");
            for (var requirement : metadata.getDependencies()) if (requirement.getModId().equals("neoforge"))
                assertTrue(requirement.getVersionRange().containsVersion(loader), metadata.getModId() + " requires NeoForge " + requirement.getVersionRange() + ", configured " + loader);
        }
        assertTrue(sawVeil, "Embedded Veil metadata must be checked, not just the top-level Sable jar");
    }

    @Test void initialSyncWrapTargetExistsExactlyOnce() throws IOException {
        var node = read("dev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem");
        var method = node.methods.stream().filter(m -> m.name.equals("sendFullSync")).findFirst().orElseThrow();
        int sends = 0, bundles = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/server/network/ServerGamePacketListenerImpl")
                        && call.name.equals("send") && call.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V")) sends++;
                if (call.owner.equals("net/minecraft/network/protocol/game/ClientboundBundlePacket")
                        && call.name.equals("<init>")) bundles++;
            }
        }
        assertEquals(1, sends);
        assertEquals(1, bundles);
    }

    @Test void localPlayerAdapterTargetsExist() throws IOException {
        String root = "dev/ryanhcode/sable/";
        var movement = read(root + "mixin/entity/entities_stick_sublevels/player/ServerboundMovePlayerPacketMixin");
        assertTrue(movement.methods.stream().anyMatch(m -> m.name.equals("sable$handle")
                && m.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;)V")));
        assertTrue(read(root + "sublevel/system/SubLevelTrackingSystem").methods.stream()
                .anyMatch(m -> m.name.equals("shouldLoad") && m.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lorg/joml/Vector3dc;)Z")));
        assertTrue(read(root + "api/sublevel/ServerSubLevelContainer").methods.stream()
                .anyMatch(m -> m.name.equals("collectForceLoadedSubLevels") && m.desc.equals("()Ljava/util/Collection;")));
        assertTrue(read("net/minecraft/client/multiplayer/ClientPacketListener").methods.stream()
                .anyMatch(m -> m.name.equals("handleMovePlayer") && m.desc.equals("(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V")));
    }

    @Test void vanillaPlayerTicketCentersAndEarlyRecoveryTargets() throws IOException {
        var chunkMap = read("net/minecraft/server/level/ChunkMap");
        for (String methodName : new String[]{"updatePlayerStatus", "updatePlayerPos", "move"}) {
            var method = chunkMap.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
            int calls = 0;
            for (var instruction : method.instructions)
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("net/minecraft/core/SectionPos") && call.name.equals("of")
                        && call.desc.equals("(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;")) calls++;
            assertEquals(1, calls, methodName);
        }
        var view = chunkMap.methods.stream().filter(m -> m.name.equals("updateChunkTracking")).findFirst().orElseThrow();
        int centers = 0;
        for (var instruction : view.instructions)
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ServerPlayer") && call.name.equals("chunkPosition")) centers++;
        assertEquals(1, centers);
        var login = read("net/minecraft/server/players/PlayerList").methods.stream()
                .filter(m -> m.name.equals("placeNewPlayer")).findFirst().orElseThrow();
        int selectDimension = -1, registerPlayer = -1, index = 0, selects = 0;
        for (var instruction : login.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/server/level/ServerPlayer") && call.name.equals("setServerLevel")) {
                    selectDimension = index; selects++;
                }
                if (call.owner.equals("net/minecraft/server/level/ServerLevel") && call.name.equals("addNewPlayer")) registerPlayer = index;
            }
            index++;
        }
        assertEquals(1, selects);
        assertTrue(selectDimension >= 0 && registerPlayer > selectDimension,
                "Recovery must happen before player chunk registration, not at the late JOIN event");
    }

    @Test void entityObserverAndServerDropTargetsExist() throws IOException {
        var tracker = read("net/minecraft/server/level/ChunkMap$TrackedEntity").methods.stream()
                .filter(m -> m.name.equals("updatePlayer")).findFirst().orElseThrow();
        int observerCalls = 0, entityCalls = 0;
        for (var instruction : tracker.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                    || !call.name.equals("position") || !call.desc.equals("()Lnet/minecraft/world/phys/Vec3;")) continue;
            if (call.owner.equals("net/minecraft/server/level/ServerPlayer")) observerCalls++;
            if (call.owner.equals("net/minecraft/world/entity/Entity")) entityCalls++;
        }
        assertEquals(1, observerCalls, "Our observer hook must remain distinct from Sable's entity hook");
        assertEquals(1, entityCalls);
        assertMethod("net/minecraft/server/level/ServerPlayer", "drop",
                "(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;");
        assertMethod("dev/ryanhcode/sable/mixin/entity/entity_tracking/TrackedEntityMixin",
                "sable$trackSubLevelEntities", "(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/Vec3;");
    }

    @Test void localSurfaceMovementBoundaryTargetExists() throws IOException {
        assertMethod("net/minecraft/world/entity/Entity", "move",
                "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V");
        assertMethod("net/minecraft/client/multiplayer/ClientPacketListener", "handleMovePlayer",
                "(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V");
    }

    @Test void spaceFlightTravelAndVerticalThrustTargetsExist() throws IOException {
        assertMethod("net/minecraft/world/entity/player/Player", "travel", "(Lnet/minecraft/world/phys/Vec3;)V");
        var aiStep = read("net/minecraft/client/player/LocalPlayer").methods.stream()
                .filter(m -> m.name.equals("aiStep")).findFirst().orElseThrow();
        int speedReads = 0;
        for (var instruction : aiStep.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/entity/player/Abilities")
                    && call.name.equals("getFlyingSpeed") && call.desc.equals("()F")) speedReads++;
        }
        assertEquals(1, speedReads, "Only vanilla world-Y thrust must be suppressed, not general flight speed");
    }

    @Test void seamCollisionAndTransitionInputTargetsExist() throws IOException {
        assertMethod("net/minecraft/world/entity/Entity", "collectColliders",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;");
        assertMethod("net/minecraft/client/Minecraft", "startAttack", "()Z");
        assertMethod("net/minecraft/client/Minecraft", "continueAttack", "(Z)V");
        assertMethod("net/minecraft/client/Minecraft", "startUseItem", "()V");
    }


    @Test void vanillaWriteTargetExists() throws IOException {
        assertTrue(read("net/minecraft/world/level/Level").methods.stream().anyMatch(m -> m.name.equals("setBlock")
                && m.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")));
    }
}
