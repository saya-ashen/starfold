package dev.starfold.dev;

/** Opt-in device smoke receipts and target fixtures. Never operates a lever, drills, moves a hull or fires a gun. */
final class DevDevicesProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");
    private static final java.util.Map<
                    net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>
            before = new java.util.HashMap<>();
    private static long started;
    private static long monitorUntil, stageStart;
    private static String stage = "";
    private static final java.util.List<net.minecraft.world.entity.LivingEntity> targets =
            new java.util.ArrayList<>();
    private static final java.util.Map<Integer, net.minecraft.world.entity.Entity> bullets =
            new java.util.HashMap<>();
    private static final java.util.Map<Integer, Float> health = new java.util.HashMap<>();
    private static net.minecraft.world.entity.npc.Villager lure;

    static void initialize() {
        if (!Boolean.getBoolean("starfold.devicesProbe")) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) -> {
                    if (!e.getLevel().isClientSide() && isBullet(e.getEntity())) {
                        var b = e.getEntity();
                        bullets.put(b.getId(), b);
                        LOG.info(
                                "TURRET BULLET birth tick={} id={} pos={} velocity={}",
                                e.getLevel().getGameTime(),
                                b.getId(),
                                b.position(),
                                b.getDeltaMovement());
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) -> {
                    if (!e.getLevel().isClientSide() && isBullet(e.getEntity())) {
                        var b = e.getEntity();
                        LOG.info(
                                "TURRET BULLET remove tick={} id={} age={} pos={} velocity={} reason={}",
                                e.getLevel().getGameTime(),
                                b.getId(),
                                b.tickCount,
                                b.position(),
                                b.getDeltaMovement(),
                                b.getRemovalReason());
                        bullets.remove(b.getId());
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) -> {
                    if (targets.contains(e.getEntity()))
                        LOG.info(
                                "TURRET DEATH stage={} tick={} elapsed={} id={} source={}",
                                stage,
                                e.getEntity().level().getGameTime(),
                                e.getEntity().level().getGameTime() - stageStart,
                                e.getEntity().getTags(),
                                e.getSource());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
                    if (e.getServer().getPlayerList().getPlayers().isEmpty()) return;
                    if (started == 0) started = System.nanoTime();
                    var level = e.getServer().getPlayerList().getPlayers().getFirst().serverLevel();
                    long tick = level.getGameTime();
                    if (tick <= monitorUntil) {
                        for (var t : targets) {
                            var old = health.put(t.getId(), t.getHealth());
                            if (old == null || old != t.getHealth())
                                LOG.info(
                                        "TURRET DAMAGE stage={} elapsed={} tags={} old={} hp={} pos={}",
                                        stage,
                                        tick - stageStart,
                                        t.getTags(),
                                        old,
                                        t.getHealth(),
                                        t.position());
                        }
                        for (var b : bullets.values())
                            LOG.info(
                                    "TURRET BULLET tick={} id={} age={} pos={} velocity={}",
                                    tick,
                                    b.getId(),
                                    b.tickCount,
                                    b.position(),
                                    b.getDeltaMovement());
                        if ((tick - stageStart) % 40 == 0)
                            for (var t : targets)
                                LOG.info(
                                        "TURRET HEALTH stage={} elapsed={} tags={} hp={} alive={} pos={}",
                                        stage,
                                        tick - stageStart,
                                        t.getTags(),
                                        t.getHealth(),
                                        t.isAlive(),
                                        t.position());
                        if ((tick - stageStart) % 20 == 0)
                            for (var body :
                                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                                    level)
                                            .getAllSubLevels())
                                for (var holder : body.getPlot().getLoadedChunks())
                                    for (var be : holder.getChunk().getBlockEntities().values())
                                        if (be.getClass()
                                                .getName()
                                                .endsWith("SentryArmBlockEntity"))
                                            LOG.info(
                                                    "TURRET ARM tick={} nbt={}",
                                                    tick,
                                                    be.saveWithFullMetadata(
                                                            level.registryAccess()));
                    }
                    if (System.nanoTime() - started > 350_000_000_000L) {
                        LOG.info("DEVICES six-minute safety deadline");
                        e.getServer().halt(false);
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                        e.getDispatcher()
                                .register(
                                        net.minecraft.commands.Commands.literal("devicesprobe")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        net.minecraft.commands.Commands.argument(
                                                                        "phase",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .StringArgumentType
                                                                                .word())
                                                                .executes(
                                                                        c -> {
                                                                            var player =
                                                                                    c.getSource()
                                                                                            .getPlayerOrException();
                                                                            var level =
                                                                                    player
                                                                                            .serverLevel();
                                                                            var phase =
                                                                                    com.mojang
                                                                                            .brigadier
                                                                                            .arguments
                                                                                            .StringArgumentType
                                                                                            .getString(
                                                                                                    c,
                                                                                                    "phase");
                                                                            var bodies =
                                                                                    dev.ryanhcode
                                                                                            .sable
                                                                                            .api
                                                                                            .sublevel
                                                                                            .SubLevelContainer
                                                                                            .getContainer(
                                                                                                    level);
                                                                            LOG.info(
                                                                                    "DEVICES {} tick={} bodies={} mspt={}",
                                                                                    phase,
                                                                                    level
                                                                                            .getGameTime(),
                                                                                    bodies.getAllSubLevels()
                                                                                            .size(),
                                                                                    level.getServer()
                                                                                                    .getAverageTickTimeNanos()
                                                                                            / 1e6);
                                                                            for (var body :
                                                                                    bodies
                                                                                            .getAllSubLevels()) {
                                                                                net.minecraft.core
                                                                                                .BlockPos
                                                                                        helm = null;
                                                                                for (var holder :
                                                                                        body.getPlot()
                                                                                                .getLoadedChunks())
                                                                                    for (var be :
                                                                                            holder.getChunk()
                                                                                                    .getBlockEntities()
                                                                                                    .values())
                                                                                        if (net
                                                                                                .minecraft
                                                                                                .core
                                                                                                .registries
                                                                                                .BuiltInRegistries
                                                                                                .BLOCK
                                                                                                .getKey(
                                                                                                        be.getBlockState()
                                                                                                                .getBlock())
                                                                                                .toString()
                                                                                                .equals(
                                                                                                        "starfold:helm"))
                                                                                            helm =
                                                                                                    be
                                                                                                            .getBlockPos();
                                                                                if (helm == null)
                                                                                    continue;
                                                                                final var origin =
                                                                                        helm.offset(
                                                                                                9,
                                                                                                -5,
                                                                                                0);
                                                                                var anchor =
                                                                                        body.logicalPose()
                                                                                                .transformPosition(
                                                                                                        new org
                                                                                                                .joml
                                                                                                                .Vector3d(
                                                                                                                origin
                                                                                                                                .getX()
                                                                                                                        + 7.5,
                                                                                                                origin
                                                                                                                                .getY()
                                                                                                                        + 3.5,
                                                                                                                origin
                                                                                                                                .getZ()
                                                                                                                        + .5));
                                                                                LOG.info(
                                                                                        "DEVICES {} pulleyWorld={} mass={} origin={}",
                                                                                        phase,
                                                                                        anchor,
                                                                                        body.getMassTracker()
                                                                                                .getMass(),
                                                                                        origin);
                                                                                if (phase.equals(
                                                                                                "board")
                                                                                        || phase
                                                                                                .startsWith(
                                                                                                        "view")) {
                                                                                    var local =
                                                                                            phase
                                                                                                            .equals(
                                                                                                                    "viewdrill")
                                                                                                    ? new org
                                                                                                            .joml
                                                                                                            .Vector3d(
                                                                                                            origin
                                                                                                                            .getX()
                                                                                                                    + 18,
                                                                                                            origin
                                                                                                                            .getY()
                                                                                                                    - 8,
                                                                                                            origin
                                                                                                                            .getZ()
                                                                                                                    + 18)
                                                                                                    : phase
                                                                                                                    .equals(
                                                                                                                            "viewturret")
                                                                                                            ? new org
                                                                                                                    .joml
                                                                                                                    .Vector3d(
                                                                                                                    origin
                                                                                                                                    .getX()
                                                                                                                            + 20,
                                                                                                                    origin
                                                                                                                                    .getY()
                                                                                                                            + 16,
                                                                                                                    origin
                                                                                                                                    .getZ()
                                                                                                                            + 12)
                                                                                                            : new org
                                                                                                                    .joml
                                                                                                                    .Vector3d(
                                                                                                                    helm
                                                                                                                                    .getX()
                                                                                                                            + 1.5,
                                                                                                                    helm
                                                                                                                                    .getY()
                                                                                                                            + .1,
                                                                                                                    helm
                                                                                                                                    .getZ()
                                                                                                                            + .5);
                                                                                    var feet =
                                                                                            body.logicalPose()
                                                                                                    .transformPosition(
                                                                                                            local);
                                                                                    player
                                                                                            .connection
                                                                                            .teleport(
                                                                                                    feet.x,
                                                                                                    feet.y,
                                                                                                    feet.z,
                                                                                                    90,
                                                                                                    15);
                                                                                    LOG.info(
                                                                                            "DEVICES FIXTURE board player only {}",
                                                                                            feet);
                                                                                }
                                                                                if (phase.equals(
                                                                                        "before")) {
                                                                                    before.clear();
                                                                                    int
                                                                                            x =
                                                                                                    (int)
                                                                                                            Math
                                                                                                                    .floor(
                                                                                                                            anchor.x),
                                                                                            z =
                                                                                                    (int)
                                                                                                            Math
                                                                                                                    .floor(
                                                                                                                            anchor.z),
                                                                                            top =
                                                                                                    (int)
                                                                                                                    Math
                                                                                                                            .floor(
                                                                                                                                    anchor.y)
                                                                                                            - 3;
                                                                                    for (var at :
                                                                                            net
                                                                                                    .minecraft
                                                                                                    .core
                                                                                                    .BlockPos
                                                                                                    .betweenClosed(
                                                                                                            x
                                                                                                                    - 1,
                                                                                                            top
                                                                                                                    - 60,
                                                                                                            z
                                                                                                                    - 1,
                                                                                                            x
                                                                                                                    + 1,
                                                                                                            top,
                                                                                                            z
                                                                                                                    + 1))
                                                                                        before.put(
                                                                                                at
                                                                                                        .immutable(),
                                                                                                level
                                                                                                        .getBlockState(
                                                                                                                at));
                                                                                }
                                                                                if (phase
                                                                                        .startsWith(
                                                                                                "turret")) {
                                                                                    for (var t :
                                                                                            targets)
                                                                                        if (!t
                                                                                                .isRemoved())
                                                                                            t
                                                                                                    .discard();
                                                                                    targets.clear();
                                                                                    health.clear();
                                                                                    if (lure
                                                                                            != null) {
                                                                                        lure
                                                                                                .discard();
                                                                                        lure = null;
                                                                                    }
                                                                                    stage = phase;
                                                                                    stageStart =
                                                                                            level
                                                                                                    .getGameTime();
                                                                                    monitorUntil =
                                                                                            stageStart
                                                                                                    + 600;
                                                                                    net.minecraft
                                                                                                    .core
                                                                                                    .BlockPos
                                                                                            arm =
                                                                                                    null;
                                                                                    for (var
                                                                                            holder :
                                                                                                    body.getPlot()
                                                                                                            .getLoadedChunks())
                                                                                        for (var
                                                                                                be :
                                                                                                        holder.getChunk()
                                                                                                                .getBlockEntities()
                                                                                                                .values())
                                                                                            if (be.getClass()
                                                                                                    .getName()
                                                                                                    .endsWith(
                                                                                                            "SentryArmBlockEntity"))
                                                                                                arm =
                                                                                                        be
                                                                                                                .getBlockPos();
                                                                                    if (arm == null)
                                                                                        throw new IllegalStateException(
                                                                                                "No deployed arm");
                                                                                    var a =
                                                                                            body.logicalPose()
                                                                                                    .transformPosition(
                                                                                                            new org
                                                                                                                    .joml
                                                                                                                    .Vector3d(
                                                                                                                    arm
                                                                                                                                    .getX()
                                                                                                                            + .5,
                                                                                                                    arm
                                                                                                                                    .getY()
                                                                                                                            + 2.42,
                                                                                                                    arm
                                                                                                                                    .getZ()
                                                                                                                            + .5));
                                                                                    int[] offsets =
                                                                                            phase
                                                                                                            .equals(
                                                                                                                    "turretfinal")
                                                                                                    ? new int
                                                                                                            [] {
                                                                                                        0,
                                                                                                        20,
                                                                                                        40
                                                                                                    }
                                                                                                    : new int
                                                                                                            [] {
                                                                                                        phase
                                                                                                                        .equals(
                                                                                                                                "turretsame")
                                                                                                                ? 15
                                                                                                                : phase
                                                                                                                                .equals(
                                                                                                                                        "turretslope")
                                                                                                                        ? 20
                                                                                                                        : phase
                                                                                                                                        .equals(
                                                                                                                                                "turretfar")
                                                                                                                                ? 40
                                                                                                                                : phase
                                                                                                                                                .equals(
                                                                                                                                                        "turretnatural")
                                                                                                                                        ? 6
                                                                                                                                        : 0
                                                                                                    };
                                                                                    for (int
                                                                                            distance :
                                                                                                    offsets) {
                                                                                        double
                                                                                                x =
                                                                                                        a.x
                                                                                                                - distance,
                                                                                                z =
                                                                                                        a.z;
                                                                                        double y =
                                                                                                phase
                                                                                                                .equals(
                                                                                                                        "turretsame")
                                                                                                        ? a.y
                                                                                                                - 1
                                                                                                        : phase
                                                                                                                        .equals(
                                                                                                                                "turretslope")
                                                                                                                ? a.y
                                                                                                                        - 20
                                                                                                                : level
                                                                                                                        .getHeight(
                                                                                                                                net
                                                                                                                                        .minecraft
                                                                                                                                        .world
                                                                                                                                        .level
                                                                                                                                        .levelgen
                                                                                                                                        .Heightmap
                                                                                                                                        .Types
                                                                                                                                        .MOTION_BLOCKING_NO_LEAVES,
                                                                                                                                (int)
                                                                                                                                        Math
                                                                                                                                                .floor(
                                                                                                                                                        x),
                                                                                                                                (int)
                                                                                                                                        Math
                                                                                                                                                .floor(
                                                                                                                                                        z));
                                                                                        var zombie =
                                                                                                new net
                                                                                                        .minecraft
                                                                                                        .world
                                                                                                        .entity
                                                                                                        .monster
                                                                                                        .Zombie(
                                                                                                        net
                                                                                                                .minecraft
                                                                                                                .world
                                                                                                                .entity
                                                                                                                .EntityType
                                                                                                                .ZOMBIE,
                                                                                                        level);
                                                                                        zombie
                                                                                                .setPos(
                                                                                                        x,
                                                                                                        y,
                                                                                                        z);
                                                                                        zombie
                                                                                                .setNoAi(
                                                                                                        !phase
                                                                                                                .equals(
                                                                                                                        "turretnatural"));
                                                                                        zombie
                                                                                                .setNoGravity(
                                                                                                        phase
                                                                                                                        .equals(
                                                                                                                                "turretsame")
                                                                                                                || phase
                                                                                                                        .equals(
                                                                                                                                "turretslope"));
                                                                                        zombie
                                                                                                .setPersistenceRequired();
                                                                                        zombie
                                                                                                .addTag(
                                                                                                        phase
                                                                                                                + "-"
                                                                                                                + distance);
                                                                                        zombie
                                                                                                .setItemSlot(
                                                                                                        net
                                                                                                                .minecraft
                                                                                                                .world
                                                                                                                .entity
                                                                                                                .EquipmentSlot
                                                                                                                .HEAD,
                                                                                                        new net
                                                                                                                .minecraft
                                                                                                                .world
                                                                                                                .item
                                                                                                                .ItemStack(
                                                                                                                net
                                                                                                                        .minecraft
                                                                                                                        .world
                                                                                                                        .item
                                                                                                                        .Items
                                                                                                                        .IRON_HELMET));
                                                                                        level
                                                                                                .addFreshEntity(
                                                                                                        zombie);
                                                                                        targets.add(
                                                                                                zombie);
                                                                                        if (phase
                                                                                                .equals(
                                                                                                        "turretnatural")) {
                                                                                            lure =
                                                                                                    new net
                                                                                                            .minecraft
                                                                                                            .world
                                                                                                            .entity
                                                                                                            .npc
                                                                                                            .Villager(
                                                                                                            net
                                                                                                                    .minecraft
                                                                                                                    .world
                                                                                                                    .entity
                                                                                                                    .EntityType
                                                                                                                    .VILLAGER,
                                                                                                            level);
                                                                                            lure
                                                                                                    .setPos(
                                                                                                            a.x,
                                                                                                            y,
                                                                                                            a.z);
                                                                                            lure
                                                                                                    .setNoAi(
                                                                                                            true);
                                                                                            lure
                                                                                                    .setInvulnerable(
                                                                                                            true);
                                                                                            level
                                                                                                    .addFreshEntity(
                                                                                                            lure);
                                                                                            zombie
                                                                                                    .setTarget(
                                                                                                            lure);
                                                                                            LOG
                                                                                                    .info(
                                                                                                            "TURRET LURE pos={} zombieNoAI={}",
                                                                                                            lure
                                                                                                                    .position(),
                                                                                                            zombie
                                                                                                                    .isNoAi());
                                                                                        }
                                                                                        LOG.info(
                                                                                                "TURRET HEALTH stage={} elapsed=0 tags={} hp={} alive={} pos={} muzzle={} distance={}",
                                                                                                stage,
                                                                                                zombie
                                                                                                        .getTags(),
                                                                                                zombie
                                                                                                        .getHealth(),
                                                                                                zombie
                                                                                                        .isAlive(),
                                                                                                zombie
                                                                                                        .position(),
                                                                                                a,
                                                                                                zombie.position()
                                                                                                        .distanceTo(
                                                                                                                new net
                                                                                                                        .minecraft
                                                                                                                        .world
                                                                                                                        .phys
                                                                                                                        .Vec3(
                                                                                                                        a.x,
                                                                                                                        a.y,
                                                                                                                        a.z)));
                                                                                    }
                                                                                }
                                                                                if (phase.equals(
                                                                                                "census")
                                                                                        || phase
                                                                                                .startsWith(
                                                                                                        "turret")) {
                                                                                    var counts =
                                                                                            new java
                                                                                                            .util
                                                                                                            .TreeMap<
                                                                                                    String,
                                                                                                    Integer>();
                                                                                    for (var
                                                                                            holder :
                                                                                                    body.getPlot()
                                                                                                            .getLoadedChunks())
                                                                                        for (var
                                                                                                section :
                                                                                                        holder.getChunk()
                                                                                                                .getSections())
                                                                                            if (!section
                                                                                                    .hasOnlyAir())
                                                                                                for (int
                                                                                                                x =
                                                                                                                        0;
                                                                                                        x
                                                                                                                < 16;
                                                                                                        x++)
                                                                                                    for (int
                                                                                                                    y =
                                                                                                                            0;
                                                                                                            y
                                                                                                                    < 16;
                                                                                                            y++)
                                                                                                        for (int
                                                                                                                        z =
                                                                                                                                0;
                                                                                                                z
                                                                                                                        < 16;
                                                                                                                z++) {
                                                                                                            var
                                                                                                                    state =
                                                                                                                            section
                                                                                                                                    .getBlockState(
                                                                                                                                            x,
                                                                                                                                            y,
                                                                                                                                            z);
                                                                                                            if (!state
                                                                                                                    .isAir())
                                                                                                                counts
                                                                                                                        .merge(
                                                                                                                                state
                                                                                                                                        .toString(),
                                                                                                                                1,
                                                                                                                                Integer
                                                                                                                                        ::sum);
                                                                                                        }
                                                                                    LOG.info(
                                                                                            "TURRET CENSUS phase={} blocks={} counts={} pose={} playerHealth={}",
                                                                                            phase,
                                                                                            counts
                                                                                                    .values()
                                                                                                    .stream()
                                                                                                    .mapToInt(
                                                                                                            Integer
                                                                                                                    ::intValue)
                                                                                                    .sum(),
                                                                                            counts,
                                                                                            body
                                                                                                    .logicalPose(),
                                                                                            player
                                                                                                    .getHealth());
                                                                                    var rotation =
                                                                                            body.logicalPose()
                                                                                                    .orientation();
                                                                                    LOG.info(
                                                                                            "TURRET POSE phase={} anchorX={} anchorY={} anchorZ={} qx={} qy={} qz={} qw={}",
                                                                                            phase,
                                                                                            anchor.x,
                                                                                            anchor.y,
                                                                                            anchor.z,
                                                                                            rotation
                                                                                                    .x(),
                                                                                            rotation
                                                                                                    .y(),
                                                                                            rotation
                                                                                                    .z(),
                                                                                            rotation
                                                                                                    .w());
                                                                                }
                                                                                if (phase.equals(
                                                                                        "targets")) {
                                                                                    var at =
                                                                                            body.logicalPose()
                                                                                                    .transformPosition(
                                                                                                            new org
                                                                                                                    .joml
                                                                                                                    .Vector3d(
                                                                                                                    origin
                                                                                                                                    .getX()
                                                                                                                            - 32.5,
                                                                                                                    origin
                                                                                                                                    .getY()
                                                                                                                            + 1,
                                                                                                                    origin
                                                                                                                                    .getZ()
                                                                                                                            + .5));
                                                                                    for (int dz :
                                                                                            new int
                                                                                                    [] {
                                                                                                -3,
                                                                                                0, 3
                                                                                            }) {
                                                                                        int
                                                                                                x =
                                                                                                        (int)
                                                                                                                Math
                                                                                                                        .floor(
                                                                                                                                at.x),
                                                                                                z =
                                                                                                        (int)
                                                                                                                        Math
                                                                                                                                .floor(
                                                                                                                                        at.z)
                                                                                                                + dz;
                                                                                        int y =
                                                                                                level
                                                                                                        .getHeight(
                                                                                                                net
                                                                                                                        .minecraft
                                                                                                                        .world
                                                                                                                        .level
                                                                                                                        .levelgen
                                                                                                                        .Heightmap
                                                                                                                        .Types
                                                                                                                        .MOTION_BLOCKING_NO_LEAVES,
                                                                                                                x,
                                                                                                                z);
                                                                                        String
                                                                                                command =
                                                                                                        "summon minecraft:zombie "
                                                                                                                + (x
                                                                                                                        + .5)
                                                                                                                + " "
                                                                                                                + y
                                                                                                                + " "
                                                                                                                + (z
                                                                                                                        + .5)
                                                                                                                + " {Tags:[\"devices1\"],NoAI:1b,PersistenceRequired:1b,ArmorItems:[{},{},{},{id:\"minecraft:iron_helmet\",count:1}]}";
                                                                                        LOG.info(
                                                                                                "DEVICES FIXTURE {}",
                                                                                                command);
                                                                                        level.getServer()
                                                                                                .getCommands()
                                                                                                .performPrefixedCommand(
                                                                                                        c
                                                                                                                .getSource(),
                                                                                                        command);
                                                                                    }
                                                                                }
                                                                                for (var holder :
                                                                                        body.getPlot()
                                                                                                .getLoadedChunks())
                                                                                    for (var be :
                                                                                            holder.getChunk()
                                                                                                    .getBlockEntities()
                                                                                                    .values()) {
                                                                                        String id =
                                                                                                net
                                                                                                        .minecraft
                                                                                                        .core
                                                                                                        .registries
                                                                                                        .BuiltInRegistries
                                                                                                        .BLOCK
                                                                                                        .getKey(
                                                                                                                be.getBlockState()
                                                                                                                        .getBlock())
                                                                                                        .toString();
                                                                                        if (id
                                                                                                        .contains(
                                                                                                                "pulley")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "piston")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "sentry")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "creative_motor")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "clutch")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "gearshift")
                                                                                                || id
                                                                                                        .contains(
                                                                                                                "redstone_link"))
                                                                                            LOG
                                                                                                    .info(
                                                                                                            "DEVICES NBT {} {} {}",
                                                                                                            phase,
                                                                                                            be
                                                                                                                    .getBlockState(),
                                                                                                            be
                                                                                                                    .saveWithFullMetadata(
                                                                                                                            level
                                                                                                                                    .registryAccess()));
                                                                                        if (be
                                                                                                instanceof
                                                                                                net
                                                                                                                .minecraft
                                                                                                                .world
                                                                                                                .level
                                                                                                                .block
                                                                                                                .entity
                                                                                                                .ChestBlockEntity
                                                                                                        chest) {
                                                                                            int
                                                                                                    total =
                                                                                                            0;
                                                                                            for (int
                                                                                                            i =
                                                                                                                    0;
                                                                                                    i
                                                                                                            < chest
                                                                                                                    .getContainerSize();
                                                                                                    i++)
                                                                                                total +=
                                                                                                        chest.getItem(
                                                                                                                        i)
                                                                                                                .getCount();
                                                                                            LOG
                                                                                                    .info(
                                                                                                            "DEVICES CHEST {} pos={} count={} nbt={}",
                                                                                                            phase,
                                                                                                            be
                                                                                                                    .getBlockPos(),
                                                                                                            total,
                                                                                                            be
                                                                                                                    .saveWithFullMetadata(
                                                                                                                            level
                                                                                                                                    .registryAccess()));
                                                                                        }
                                                                                    }
                                                                            }
                                                                            int gone = 0;
                                                                            for (var entry :
                                                                                    before
                                                                                            .entrySet())
                                                                                if (!entry.getValue()
                                                                                                .isAir()
                                                                                        && level.getBlockState(
                                                                                                        entry
                                                                                                                .getKey())
                                                                                                .isAir())
                                                                                    gone++;
                                                                            LOG.info(
                                                                                    "DEVICES TERRAIN {} baselineCells={} solidToAir={}",
                                                                                    phase,
                                                                                    before.size(),
                                                                                    gone);
                                                                            int zombies = 0;
                                                                            for (var entity :
                                                                                    level
                                                                                            .getAllEntities()) {
                                                                                if (entity.getTags()
                                                                                        .contains(
                                                                                                "devices1")) {
                                                                                    zombies++;
                                                                                    LOG.info(
                                                                                            "DEVICES TARGET {} {} alive={} nbt={}",
                                                                                            phase,
                                                                                            entity
                                                                                                    .position(),
                                                                                            entity
                                                                                                    .isAlive(),
                                                                                            entity
                                                                                                    .saveWithoutId(
                                                                                                            new net
                                                                                                                    .minecraft
                                                                                                                    .nbt
                                                                                                                    .CompoundTag()));
                                                                                }
                                                                                String id =
                                                                                        net
                                                                                                .minecraft
                                                                                                .core
                                                                                                .registries
                                                                                                .BuiltInRegistries
                                                                                                .ENTITY_TYPE
                                                                                                .getKey(
                                                                                                        entity
                                                                                                                .getType())
                                                                                                .toString();
                                                                                if (id.equals(
                                                                                        "create:stationary_contraption"))
                                                                                    LOG.info(
                                                                                            "DEVICES CONTRAPTION {} {}",
                                                                                            phase,
                                                                                            entity
                                                                                                    .saveWithoutId(
                                                                                                            new net
                                                                                                                    .minecraft
                                                                                                                    .nbt
                                                                                                                    .CompoundTag()));
                                                                            }
                                                                            LOG.info(
                                                                                    "DEVICES TARGETS {} remaining={}",
                                                                                    phase,
                                                                                    zombies);
                                                                            return 1;
                                                                        }))));
    }

    private static boolean isBullet(net.minecraft.world.entity.Entity e) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(e.getType())
                .getNamespace()
                .equals("tacz");
    }

    private DevDevicesProbe() {}
}
