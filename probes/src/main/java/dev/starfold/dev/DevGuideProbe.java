package dev.starfold.dev;

import dev.starfold.render.GroundClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Development driver using ordinary survival input and menu clicks, without item/world injection. */
final class DevGuideProbe {
    private static BlockPos digging, leafGoal;
    private static BlockState diggingState;
    private static int clearedLeaves;
    private static int left;
    private static double walkX, walkZ;
    private static boolean walking, boundedApproach, climbing, aligning;
    private static double climbY;
    private static boolean clearApproach;
    private static int approachLeaves, walkTicks, alignBrake;
    private static final java.util.ArrayDeque<Runnable> clicks = new java.util.ArrayDeque<>();
    private static int delay;
    private static java.util.function.BooleanSupplier awaiting;
    private static int waitLeft;
    private static String waitLabel;
    private static int eating, worldWait;
    private static boolean waitAllowsDeath;
    private static boolean guarding;
    private static java.util.UUID hunting;
    private static Vec3 huntLast;
    private static int huntLeft, pickupLeft;
    private static int downRemaining, downWait, downTicks;
    private static BlockPos downLanding;

    static boolean tick(Minecraft mc) {
        try {
            return step(mc);
        } catch (RuntimeException error) {
            clicks.clear();
            awaiting = null;
            if (digging != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            digging = null;
            diggingState = null;
            if (eating > 0) mc.options.keyUse.setDown(false);
            eating = 0;
            hunting = null;
            downRemaining = 0;
            downLanding = null;
            walking = false;
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            throw error;
        }
    }

    private static boolean step(Minecraft mc) {
        if (worldWait > 0) {
            if (--worldWait <= 0)
                throw new IllegalStateException("Timed out awaiting survival connection");
            if (mc.player != null
                    && mc.level != null
                    && mc.gameMode != null
                    && (mc.screen == null
                            || waitAllowsDeath
                                    && mc.screen
                                            instanceof
                                            net.minecraft.client.gui.screens.DeathScreen)) {
                requireSurvival(mc, waitAllowsDeath);
                worldWait = 0;
                log(mc, "connected-survival-read-only-guard");
            }
            return true;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            clicks.clear();
            awaiting = null;
            digging = null;
            diggingState = null;
            mc.options.keyUse.setDown(false);
            eating = 0;
            hunting = null;
            downRemaining = 0;
            downLanding = null;
            walking = false;
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return false;
        }
        // Guard attacks may interrupt input, but cannot extend a bounded menu/item wait forever.
        if (awaiting != null && --waitLeft <= 0)
            throw new IllegalStateException("Timed out waiting for " + waitLabel);
        if (downRemaining > 0) {
            if (--downTicks <= 0) throw new IllegalStateException("mine-down batch timed out");
            checkDownBoundary(mc, downLanding);
        }
        if (guarding
                && clicks.isEmpty()
                && mc.screen == null
                && !mc.player.isDeadOrDying()
                && !mc.player.isUsingItem()
                && mc.player.getAttackStrengthScale(0) >= .98f) {
            var threats =
                    mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(3)).stream()
                            // Neutral mobs (notably endermen) are not ambient threats. Their
                            // server-side target is not reliably available on this client;
                            // automatic guarding must not provoke them just by walking past.
                            .filter(
                                    entity ->
                                            entity
                                                            instanceof
                                                            net.minecraft.world.entity.monster
                                                                    .Monster
                                                    && !(entity
                                                            instanceof
                                                            net.minecraft.world.entity.NeutralMob)
                                                    && entity.isAlive())
                            .sorted(java.util.Comparator.comparingDouble(mc.player::distanceToSqr))
                            .toList();
            for (var threat : threats) {
                aim(mc, threat.getBoundingBox().getCenter());
                if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                        && hit.getEntity() == threat) {
                    mc.gameMode.attack(mc.player, threat);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    log(mc, "guard-attacked " + threat.getUUID());
                    return true;
                }
            }
        }
        if (awaiting != null) {
            if (awaiting.getAsBoolean()) awaiting = null;
            return true;
        }
        if (!clicks.isEmpty()) {
            if (delay-- > 0) return true;
            clicks.removeFirst().run();
            delay = 3;
            return true;
        }
        if (digging != null) {
            var state = mc.level.getBlockState(digging);
            // Native destruction restores the fluid block; a still-present water plant is not done.
            if (state.isAir()
                    || !state.is(diggingState.getBlock())
                            && !diggingState.getFluidState().isEmpty()
                            && state == diggingState.getFluidState().createLegacyBlock()) {
                log(mc, "mined " + digging);
                mc.gameMode.stopDestroyBlock();
                digging = null;
                diggingState = null;
                if (leafGoal != null && !mc.level.getBlockState(leafGoal).isAir()) {
                    startLeafApproach(mc);
                    return true;
                }
                leafGoal = null;
                return downRemaining > 0 || walking;
            }
            if (--left <= 0) throw new IllegalStateException("Mining timed out " + digging);
            var hit = aimBlock(mc, digging);
            mc.gameMode.continueDestroyBlock(digging, hit.getDirection());
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        if (downRemaining > 0) return stepDown(mc);
        if (eating > 0) {
            if (mc.screen != null)
                throw new IllegalStateException("Eating interrupted by an open screen");
            if (--eating == 0) {
                mc.options.keyUse.setDown(false);
                if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
                log(mc, "eat-released");
                return false;
            }
            mc.options.keyUse.setDown(true);
            return true;
        }
        if (hunting != null) {
            var prey =
                    mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(64)).stream()
                            .filter(entity -> entity.getUUID().equals(hunting) && entity.isAlive())
                            .findFirst()
                            .orElse(null);
            if (prey != null) huntLast = prey.position();
            if (--huntLeft <= 0) throw new IllegalStateException("Hunt timed out " + hunting);
            if (prey == null && --pickupLeft <= 0) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                hunting = null;
                log(mc, "hunt-ended-inspect-inventory");
                return false;
            }
            var point = prey == null ? huntLast : prey.getBoundingBox().getCenter();
            aim(mc, point);
            double distance = Math.hypot(point.x - mc.player.getX(), point.z - mc.player.getZ());
            mc.options.keyUp.setDown(distance > (prey == null ? .2 : 1.5));
            mc.options.keyJump.setDown(mc.player.horizontalCollision);
            if (prey != null
                    && mc.player.getAttackStrengthScale(0) >= .98f
                    && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                    && hit.getEntity() == prey) {
                mc.gameMode.attack(mc.player, prey);
                mc.player.swing(InteractionHand.MAIN_HAND);
                log(mc, "hunt-attacked " + hunting);
            }
            return true;
        }
        if (alignBrake > 0) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(--alignBrake > 0);
            return alignBrake > 0;
        }
        if (walking) {
            mc.options.keyShift.setDown(aligning);
            double dx = walkX - mc.player.getX(), dz = walkZ - mc.player.getZ();
            if (clearApproach && mc.player.horizontalCollision && Math.hypot(dx, dz) > .15) {
                double distance = Math.hypot(dx, dz);
                for (double height : new double[] {1.5, .5}) {
                    aim(
                            mc,
                            mc.player
                                    .position()
                                    .add(dx / distance * 1.5, height, dz / distance * 1.5));
                    if (mc.hitResult instanceof BlockHitResult hit
                            && hit.getType() == HitResult.Type.BLOCK
                            && mc.level
                                    .getBlockState(hit.getBlockPos())
                                    .is(net.minecraft.tags.BlockTags.LEAVES)) {
                        if (++approachLeaves > 16)
                            throw new IllegalStateException("Approach leaf budget exhausted");
                        mc.options.keyUp.setDown(false);
                        mc.options.keyJump.setDown(false);
                        leafGoal = null;
                        digging = hit.getBlockPos().immutable();
                        diggingState = mc.level.getBlockState(digging);
                        mc.gameMode.startDestroyBlock(digging, hit.getDirection());
                        left = 200;
                        log(mc, "approach-dig-leaf " + digging);
                        return true;
                    }
                }
            }
            boolean done =
                    climbing
                            ? mc.player.getY() >= climbY - .03
                            : Math.hypot(dx, dz) < (aligning ? .03 : .15);
            // Do not reverse the camera on the stopping tick: one ordinary movement
            // input can still be in flight until the following client tick.
            if (!done) aim(mc, new Vec3(walkX, mc.player.getEyeY(), walkZ));
            if (climbing && Math.hypot(dx, dz) > 4)
                throw new IllegalStateException("Climb moved away from its ladder wall");
            mc.options.keyUp.setDown(!done);
            mc.options.keyJump.setDown(
                    !done && !climbing && (mc.player.horizontalCollision || mc.player.isInWater()));
            if (done) {
                walking = false;
                // Retain normal sneaking while friction settles the final movement.
                alignBrake = aligning ? 10 : 0;
                mc.options.keyShift.setDown(aligning);
                log(mc, "walked");
                return alignBrake > 0;
            }
            if (--walkTicks <= 0) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyShift.setDown(false);
                walking = false;
                if (boundedApproach) {
                    log(mc, "approach-ended-inspect-position target=" + walkX + "," + walkZ);
                    return false;
                }
                throw new IllegalStateException(
                        "Walk timed out before reaching " + walkX + ", " + walkZ);
            }
            return true;
        }
        return false;
    }

    static void command(Minecraft mc, String arg) {
        var a = arg.split("\\s+", 2);
        String value = a.length > 1 ? a[1] : "";
        switch (a[0]) {
            case "state" -> log(mc, value);
            case "require-survival" -> requireSurvival(mc);
            case "wait-survival", "wait-player" -> {
                waitAllowsDeath = a[0].equals("wait-player");
                worldWait = boundedWait(value.trim());
            }
            case "respawn" -> {
                requireSurvival(mc, true);
                if (mc.screen == null && !mc.player.isDeadOrDying()) {
                    log(mc, "already-alive-native-rejoin");
                    break;
                }
                if (!(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen))
                    throw new IllegalStateException("Respawn requires the actual death screen");
                String label =
                        net.minecraft.network.chat.Component.translatable("deathScreen.respawn")
                                .getString();
                var button =
                        mc.screen.children().stream()
                                .filter(
                                        child ->
                                                child
                                                                instanceof
                                                                net.minecraft.client.gui.components
                                                                                .Button
                                                                        b
                                                        && b.active
                                                        && b.visible
                                                        && b.getMessage().getString().equals(label))
                                .map(child -> (net.minecraft.client.gui.components.Button) child)
                                .findFirst()
                                .orElseThrow();
                log(mc, "ordinary-death-screen-respawn-button");
                button.onPress();
            }
            case "at" -> {
                requireSurvival(mc);
                var fields = value.trim().split("\\s+");
                if (fields.length != 4) throw new IllegalArgumentException("guide at x y z radius");
                var point =
                        new Vec3(
                                Double.parseDouble(fields[0]),
                                Double.parseDouble(fields[1]),
                                Double.parseDouble(fields[2]));
                double radius = Double.parseDouble(fields[3]);
                if (!Double.isFinite(radius)
                        || radius <= 0
                        || radius > 4
                        || !Double.isFinite(point.lengthSqr())
                        || mc.player.position().distanceTo(point) > radius)
                    throw new IllegalStateException("Player is outside checkpoint " + value);
                log(mc, "at " + value);
            }
            case "require-item" -> {
                var fields = value.trim().split("\\s+");
                if (fields.length != 3)
                    throw new IllegalArgumentException("guide require-item id min max");
                int count = itemCount(mc, fields[0]);
                requireRange(
                        count, Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), value);
                log(mc, "require-item " + value + " actual=" + count);
            }
            case "wait-item" -> {
                var fields = value.trim().split("\\s+");
                if (fields.length != 4)
                    throw new IllegalArgumentException("guide wait-item id min max ticks");
                int min = Integer.parseInt(fields[1]), max = Integer.parseInt(fields[2]);
                requireRange(min, 0, max, "item range");
                await(
                        () -> {
                            requireSurvival(mc);
                            int count = itemCount(mc, fields[0]);
                            return count >= min && count <= max;
                        },
                        "inventory " + value);
                waitLeft = boundedWait(fields[3]);
            }
            case "require-slot" -> {
                var fields = value.trim().split("\\s+");
                if (fields.length != 4)
                    throw new IllegalArgumentException("guide require-slot slot id min max");
                var menu = mc.player.containerMenu;
                checkMenu(mc, menu);
                var stack = menu.getSlot(Integer.parseInt(fields[0])).getItem();
                if (!id(stack).equals(fields[1]))
                    throw new IllegalStateException("Wrong item in slot " + fields[0]);
                requireRange(
                        stack.getCount(),
                        Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]),
                        value);
            }
            case "interact-picked", "drop-picked" -> {
                requireSurvival(mc);
                var fields = value.trim().split("\\s+");
                if (fields.length != 2)
                    throw new IllegalArgumentException(
                            "guide interact-picked|drop-picked block-id face");
                if (!(mc.hitResult instanceof BlockHitResult candidate))
                    throw new IllegalStateException("Aim at the required block face first");
                var hit = picked(mc, candidate.getBlockPos());
                if (!BuiltInRegistries.BLOCK
                                .getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock())
                                .toString()
                                .equals(fields[0])
                        || hit.getDirection()
                                != Direction.valueOf(fields[1].toUpperCase(java.util.Locale.ROOT))
                        || hit.isInside())
                    throw new IllegalStateException("Wrong picked block/face for " + value);
                if (a[0].equals("drop-picked")) {
                    if (mc.player.getMainHandItem().isEmpty() || !mc.player.drop(false))
                        throw new IllegalStateException("Could not drop the selected item");
                    log(mc, "dropped toward " + hit.getBlockPos() + "; insertion must be observed");
                } else {
                    var pos = hit.getBlockPos();
                    command(
                            mc,
                            "interact "
                                    + pos.getX()
                                    + " "
                                    + pos.getY()
                                    + " "
                                    + pos.getZ()
                                    + " "
                                    + fields[1]);
                }
            }
            case "collect" -> {
                requireSurvival(mc);
                idleClicks();
                var fields = value.trim().split("\\s+");
                if (fields.length != 2)
                    throw new IllegalArgumentException("guide collect first last");
                var menu = mc.player.containerMenu;
                checkMenu(mc, menu);
                emptyCursor(menu);
                int first = Integer.parseInt(fields[0]), last = Integer.parseInt(fields[1]);
                requireRange(first, 0, last, "first output slot");
                requireRange(last, first, menu.slots.size() - 1, "last output slot");
                for (int index = first; index <= last; index++) {
                    final int slot = index;
                    if (menu.getSlot(slot).container == mc.player.getInventory())
                        throw new IllegalArgumentException(
                                "collect is for external output slots only");
                    clicks.add(
                            () -> {
                                checkMenu(mc, menu);
                                emptyCursor(menu);
                                var source = menu.getSlot(slot);
                                if (!source.hasItem()) return;
                                int before = source.getItem().getCount();
                                if (!source.mayPickup(mc.player))
                                    throw new IllegalStateException("Cannot collect slot " + slot);
                                mc.gameMode.handleInventoryMouseClick(
                                        menu.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
                                if (source.getItem().getCount() >= before)
                                    throw new IllegalStateException(
                                            "No space to collect slot " + slot);
                            });
                }
                clicks.add(
                        () -> {
                            checkMenu(mc, menu);
                            for (int index = first; index <= last; index++)
                                if (menu.getSlot(index).hasItem())
                                    throw new IllegalStateException(
                                            "Output slot not empty " + index);
                            log(mc, "collected " + value);
                        });
                delay = 0;
            }
            case "console" -> console(mc, value);
            case "wait-menu" -> {
                var fields = value.trim().split("\\s+");
                if (fields.length != 5)
                    throw new IllegalArgumentException(
                            "guide wait-menu mining|oil field min max ticks");
                var menu = mc.player.containerMenu;
                checkMenu(mc, menu);
                int min = Integer.parseInt(fields[2]), max = Integer.parseInt(fields[3]);
                requireRange(min, 0, max, "menu range");
                menuValue(menu, fields[0], fields[1]); // Validate before scheduling the wait.
                await(
                        () -> {
                            requireSurvival(mc);
                            checkMenu(mc, menu);
                            int actual = menuValue(menu, fields[0], fields[1]);
                            return actual >= min && actual <= max;
                        },
                        "menu " + value);
                waitLeft = boundedWait(fields[4]);
            }
            case "mine-down" -> {
                requireSurvival(mc);
                idleClicks();
                int count = Integer.parseInt(value.trim());
                requireRange(count, 1, 8, "mine-down layers");
                if (digging != null
                        || walking
                        || eating > 0
                        || hunting != null
                        || mc.screen != null)
                    throw new IllegalStateException("mine-down requires idle gameplay");
                downRemaining = count;
                downTicks = count * 300;
                downLanding = null;
                downWait = 60;
            }
            case "guard" -> {
                if (!value.equals("on") && !value.equals("off"))
                    throw new IllegalArgumentException("guide guard on|off");
                guarding = value.equals("on");
                log(mc, "guard " + value);
            }
            case "nearby" -> {
                var base = mc.player.blockPosition();
                var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
                for (int x = -2; x <= 2; x++)
                    for (int z = -2; z <= 2; z++) {
                        var column = new StringBuilder();
                        for (int y = -4; y <= 3; y++) {
                            var pos = base.offset(x, y, z);
                            column.append(pos.getY())
                                    .append(':')
                                    .append(
                                            BuiltInRegistries.BLOCK.getKey(
                                                    mc.level.getBlockState(pos).getBlock()))
                                    .append(' ');
                        }
                        logger.info(
                                "PROBE NEARBY x={} z={} {}",
                                base.getX() + x,
                                base.getZ() + z,
                                column);
                    }
                for (var entity :
                        mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(64)))
                    if (entity instanceof net.minecraft.world.entity.animal.Animal
                            || entity instanceof net.minecraft.world.entity.monster.Monster
                            || entity instanceof net.minecraft.world.entity.item.ItemEntity)
                        logger.info(
                                "PROBE ENTITY {} {} {} {}",
                                entity.getUUID(),
                                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                                entity.position(),
                                entity instanceof net.minecraft.world.entity.item.ItemEntity item
                                        ? item.getItem()
                                        : "");
                for (var slot : mc.player.containerMenu.slots)
                    if (slot.hasItem()) logger.info("PROBE SLOT {} {}", slot.index, slot.getItem());
                logger.info(
                        "PROBE VITALS health={} food={} saturation={}",
                        mc.player.getHealth(),
                        mc.player.getFoodData().getFoodLevel(),
                        mc.player.getFoodData().getSaturationLevel());
            }
            case "hunt", "engage" -> {
                requireSurvival(mc);
                hunting = java.util.UUID.fromString(value);
                var prey =
                        mc
                                .level
                                .getEntities(mc.player, mc.player.getBoundingBox().inflate(64))
                                .stream()
                                .filter(
                                        entity ->
                                                entity.getUUID().equals(hunting)
                                                        && (a[0].equals("hunt")
                                                                ? entity
                                                                        instanceof
                                                                        net.minecraft.world.entity
                                                                                .animal.Animal
                                                                : entity
                                                                        instanceof
                                                                        net.minecraft.world.entity
                                                                                .monster.Monster))
                                .findFirst()
                                .orElse(null);
                if (prey == null && a[0].equals("engage")) {
                    hunting = null;
                    log(mc, "engage-target-absent-inspect " + value);
                    break;
                }
                if (prey == null) throw new IllegalStateException("Animal unavailable " + value);
                huntLast = prey.position();
                huntLeft = 600;
                pickupLeft = 50;
            }
            case "store", "retrieve" -> {
                idleClicks();
                var fields = value.trim().split("\\s+");
                if (fields.length != 3)
                    throw new IllegalArgumentException("guide store|retrieve item target count");
                var menu = mc.player.containerMenu;
                int source = -1;
                for (var slot : menu.slots)
                    if ((slot.container == mc.player.getInventory()) == a[0].equals("store")
                            && id(slot.getItem()).equals(fields[0])) {
                        source = slot.index;
                        break;
                    }
                if (source < 0) throw new IllegalStateException("No source item " + fields[0]);
                final int from = source;
                clicks.add(
                        () ->
                                transfer(
                                        mc,
                                        menu,
                                        from,
                                        Integer.parseInt(fields[1]),
                                        Integer.parseInt(fields[2])));
                clicks.add(() -> log(mc, a[0] + " " + value));
                delay = 0;
            }
            case "entity" -> {
                var fields = value.split("\\s+");
                if (fields.length != 2)
                    throw new IllegalArgumentException("guide entity attack|use UUID");
                var uuid = java.util.UUID.fromString(fields[1]);
                var target =
                        mc
                                .level
                                .getEntities(mc.player, mc.player.getBoundingBox().inflate(5))
                                .stream()
                                .filter(entity -> entity.getUUID().equals(uuid))
                                .findFirst()
                                .orElseThrow();
                aim(mc, target.getBoundingBox().getCenter());
                if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)
                        || hit.getEntity() != target)
                    throw new IllegalStateException(
                            "Entity is not the reachable picked entity " + uuid);
                if (fields[0].equals("attack")) mc.gameMode.attack(mc.player, target);
                else if (fields[0].equals("use"))
                    mc.gameMode.interact(mc.player, target, InteractionHand.MAIN_HAND);
                else throw new IllegalArgumentException("Expected attack or use");
                mc.player.swing(InteractionHand.MAIN_HAND);
                log(mc, "entity " + value);
            }
            case "ultimine" -> {
                try {
                    var type = Class.forName("dev.ftb.mods.ftbultimine.client.FTBUltimineClient");
                    var binding =
                            (net.minecraft.client.KeyMapping)
                                    type.getField("keyBindUltimine").get(null);
                    if (!value.equals("on") && !value.equals("off"))
                        throw new IllegalArgumentException("guide ultimine on|off");
                    binding.setDown(value.equals("on"));
                    log(mc, "ultimine " + value);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Ultimine client binding unavailable", error);
                }
            }
            case "mine", "mineclear" -> {
                var v = value.split(" ");
                var target =
                        new BlockPos(
                                Integer.parseInt(v[0]),
                                Integer.parseInt(v[1]),
                                Integer.parseInt(v[2]));
                leafGoal = null;
                if (a[0].equals("mineclear")) {
                    leafGoal = target;
                    clearedLeaves = 0;
                    startLeafApproach(mc);
                    break;
                }
                var hit = aimBlock(mc, target);
                digging = target;
                diggingState = mc.level.getBlockState(digging);
                left = 200;
                mc.gameMode.startDestroyBlock(digging, hit.getDirection());
                log(mc, "dig-start " + digging + " " + mc.level.getBlockState(digging));
            }
            case "climb" -> {
                requireSurvival(mc);
                if (mc.screen != null)
                    throw new IllegalStateException("Close the menu before climbing");
                var v = value.trim().split("\\s+");
                if (v.length != 4)
                    throw new IllegalArgumentException("guide climb wallX wallZ targetY ticks");
                walkX = Double.parseDouble(v[0]);
                walkZ = Double.parseDouble(v[1]);
                climbY = Double.parseDouble(v[2]);
                walkTicks = Integer.parseInt(v[3]);
                requireRange(walkTicks, 1, 2400, "climb ticks");
                climbing = true;
                aligning = false;
                clearApproach = false;
                boundedApproach = false;
                walking = true;
            }
            case "walk", "approach", "approach-clear", "align" -> {
                climbing = false;
                aligning = a[0].equals("align");
                clearApproach = a[0].equals("approach-clear");
                approachLeaves = 0;
                boundedApproach = !a[0].equals("walk") && !aligning;
                var v = value.split(" ");
                walkX = Double.parseDouble(v[0]);
                walkZ = Double.parseDouble(v[1]);
                walkTicks = v.length > 2 ? Integer.parseInt(v[2]) : 200;
                requireRange(walkTicks, 1, 2400, "walk ticks");
                walking = true;
            }
            case "aim" -> {
                var v = value.split(" ");
                aim(
                        mc,
                        new Vec3(
                                Double.parseDouble(v[0]),
                                Double.parseDouble(v[1]),
                                Double.parseDouble(v[2])));
            }
            case "select" -> {
                idleClicks();
                emptyCursor(mc.player.containerMenu);
                if (mc.player.containerMenu != mc.player.inventoryMenu) closeMenu(mc);
                mc.setScreen(
                        new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                var menu = mc.player.containerMenu;
                net.minecraft.world.inventory.Slot choice = null;
                for (var slot : menu.slots)
                    if (slot.container == mc.player.getInventory()
                            && slot.index >= 9
                            && slot.index <= 44
                            && id(slot.getItem()).equals(value)
                            && (choice == null
                                    || slot.getItem().isDamageableItem()
                                            && slot.getItem().getDamageValue()
                                                    < choice.getItem().getDamageValue()))
                        choice = slot;
                if (choice == null) throw new IllegalStateException("No item " + value);
                // Choose the least-worn actual tool, then perform the normal inventory swap.
                mc.gameMode.handleInventoryMouseClick(
                        menu.containerId, choice.index, 0, ClickType.SWAP, mc.player);
                mc.player.getInventory().selected = 0;
                closeMenu(mc);
            }
            case "craft-batch", "craft2-batch" -> {
                var fields = value.split("\\s+", 2);
                if (fields.length != 2)
                    throw new IllegalArgumentException("guide craft[-2]-batch count CSV");
                int count = Integer.parseInt(fields[0]);
                requireRange(count, 1, 64, "recipe count");
                if (a[0].equals("craft2-batch")) {
                    idleClicks();
                    if (mc.player.containerMenu != mc.player.inventoryMenu) closeMenu(mc);
                    mc.setScreen(
                            new net.minecraft.client.gui.screens.inventory.InventoryScreen(
                                    mc.player));
                    craft(mc, fields[1], 4, 9, count);
                } else {
                    if (!(mc.player.containerMenu
                            instanceof net.minecraft.world.inventory.CraftingMenu))
                        throw new IllegalStateException("Open a crafting table first");
                    craft(mc, fields[1], 9, 10, count);
                }
            }
            case "craft" -> {
                if (!(mc.player.containerMenu
                        instanceof net.minecraft.world.inventory.CraftingMenu))
                    throw new IllegalStateException("Open a crafting table first");
                craft(mc, value, 9, 10);
            }
            case "craft2" -> {
                idleClicks();
                if (value.split(",", -1).length != 4)
                    throw new IllegalArgumentException("Four cells required");
                if (mc.player.containerMenu != mc.player.inventoryMenu) closeMenu(mc);
                mc.setScreen(
                        new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                craft(mc, value, 4, 9);
            }
            case "slot" -> {
                var v = value.trim().split("\\s+");
                if (v.length != 2 && v.length != 3)
                    throw new IllegalArgumentException("guide slot <source> <target> [count]");
                idleClicks();
                var menu = mc.player.containerMenu;
                int source = Integer.parseInt(v[0]), target = Integer.parseInt(v[1]);
                int count = v.length == 3 ? Integer.parseInt(v[2]) : 1;
                clicks.add(() -> transfer(mc, menu, source, target, count));
                clicks.add(() -> log(mc, "slotted " + source + " -> " + target + " x" + count));
                delay = 0;
            }
            case "take" -> {
                idleClicks();
                var menu = mc.player.containerMenu;
                int slot = Integer.parseInt(value.trim());
                clicks.add(
                        () -> {
                            checkMenu(mc, menu);
                            emptyCursor(menu);
                            var source = menu.getSlot(slot);
                            if (!source.hasItem() || !source.mayPickup(mc.player))
                                throw new IllegalStateException("Cannot take slot " + slot);
                            int before = source.getItem().getCount();
                            mc.gameMode.handleInventoryMouseClick(
                                    menu.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
                            if (source.getItem().getCount() >= before)
                                throw new IllegalStateException("No items moved from slot " + slot);
                        });
                clicks.add(() -> log(mc, "took " + slot));
                delay = 0;
            }
            case "pointer" -> {
                if (mc.screen == null) throw new IllegalStateException("Open a screen first");
                var fields = value.trim().split("\\s+");
                if (fields.length != 3)
                    throw new IllegalArgumentException("guide pointer x y button");
                double x = Double.parseDouble(fields[0]), y = Double.parseDouble(fields[1]);
                int button = Integer.parseInt(fields[2]);
                if (x < 0 || y < 0 || x >= mc.screen.width || y >= mc.screen.height)
                    throw new IllegalArgumentException("Pointer outside screen");
                mc.screen.mouseClicked(x, y, button);
                mc.screen.mouseReleased(x, y, button);
                log(mc, "pointer " + value);
            }
            case "text" -> {
                if (mc.screen == null) throw new IllegalStateException("Open a screen first");
                for (char character : value.toCharArray())
                    if (!mc.screen.charTyped(character, 0))
                        throw new IllegalStateException("Screen did not accept text");
                log(mc, "typed " + value);
            }
            case "place" -> {
                idleClicks();
                var v = value.trim().split("\\s+");
                if (v.length != 3 && v.length != 4)
                    throw new IllegalArgumentException("guide place x y z [support-face]");
                Direction requiredFace =
                        v.length == 4
                                ? Direction.valueOf(v[3].toUpperCase(java.util.Locale.ROOT))
                                : null;
                var target =
                        new BlockPos(
                                Integer.parseInt(v[0]),
                                Integer.parseInt(v[1]),
                                Integer.parseInt(v[2]));
                var before = mc.level.getBlockState(target);
                if (!before.canBeReplaced())
                    throw new IllegalStateException("Need a replaceable destination " + target);
                BlockHitResult hit = null;
                for (var face :
                        new Direction[] {
                            Direction.UP,
                            Direction.NORTH,
                            Direction.SOUTH,
                            Direction.WEST,
                            Direction.EAST,
                            Direction.DOWN
                        }) {
                    if (requiredFace != null && face != requiredFace) continue;
                    var support = target.relative(face.getOpposite());
                    var state = mc.level.getBlockState(support);
                    if (state.isAir() || state.canBeReplaced()) continue;
                    for (double faceU : new double[] {0, -.4, .4}) {
                        for (double faceV : new double[] {0, -.4, .4}) {
                            var offset =
                                    switch (face.getAxis()) {
                                        case X -> new Vec3(0, faceU, faceV);
                                        case Y -> new Vec3(faceU, 0, faceV);
                                        case Z -> new Vec3(faceU, faceV, 0);
                                    };
                            var point =
                                    Vec3.atCenterOf(support)
                                            .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499))
                                            .add(offset);
                            aim(mc, point);
                            if (mc.screen == null
                                    && mc.hitResult instanceof BlockHitResult candidate
                                    && candidate.getType() == HitResult.Type.BLOCK
                                    && candidate.getBlockPos().equals(support)
                                    && candidate.getDirection() == face
                                    && !candidate.isInside()
                                    && mc.player
                                                    .getEyePosition()
                                                    .distanceTo(candidate.getLocation())
                                            <= mc.player.blockInteractionRange()) {
                                hit = candidate;
                                break;
                            }
                        }
                        if (hit != null) break;
                    }
                    if (hit != null) break;
                }
                if (hit == null)
                    throw new IllegalStateException("No reachable support face for " + target);
                var placement = hit;
                // Let ordinary movement packets deliver the aimed rotation before the use packet.
                // Directional blocks otherwise get the server's previous facing on placement.
                clicks.add(
                        () -> {
                            // Track the same real face while a normal jump changes eye height.
                            aim(mc, placement.getLocation());
                            var current = picked(mc, placement.getBlockPos());
                            if (current.getDirection() != placement.getDirection())
                                throw new IllegalStateException("Placement face changed " + target);
                            var result =
                                    mc.gameMode.useItemOn(
                                            mc.player, InteractionHand.MAIN_HAND, current);
                            if (result.shouldSwing()) mc.player.swing(InteractionHand.MAIN_HAND);
                            if (!result.consumesAction())
                                throw new IllegalStateException("Placement rejected " + target);
                            // Native item use may finish on the server before its block update
                            // reaches
                            // this client (for example a cog placed against a mechanical pump).
                            // Wait for that update without repeating the click or supplying another
                            // item.
                            awaiting =
                                    () -> {
                                        requireSurvival(mc);
                                        if (mc.level.getBlockState(target).equals(before))
                                            return false;
                                        log(mc, "placed " + target + " result " + result);
                                        return true;
                                    };
                            waitLeft = 60;
                            waitLabel =
                                    "native placement at "
                                            + target
                                            + "; support may have handled the click";
                        });
                delay = 0;
            }
            case "interact" -> {
                idleClicks();
                var fields = value.trim().split("\\s+");
                if (fields.length != 4)
                    throw new IllegalArgumentException("guide interact x y z face");
                var target =
                        new BlockPos(
                                Integer.parseInt(fields[0]),
                                Integer.parseInt(fields[1]),
                                Integer.parseInt(fields[2]));
                var face = Direction.valueOf(fields[3].toUpperCase(java.util.Locale.ROOT));
                var hit = aimFace(mc, target, face);
                clicks.add(
                        () -> {
                            aim(mc, hit.getLocation());
                            var current = picked(mc, target);
                            if (current.getDirection() != face || current.isInside())
                                throw new IllegalStateException(
                                        "Interaction face changed " + target);
                            var result =
                                    mc.gameMode.useItemOn(
                                            mc.player, InteractionHand.MAIN_HAND, current);
                            if (result.shouldSwing()) mc.player.swing(InteractionHand.MAIN_HAND);
                            if (!result.consumesAction())
                                throw new IllegalStateException(
                                        "Interaction rejected " + target + " " + face);
                            log(mc, "interacted " + target + " " + face + " result " + result);
                        });
                // Ordinary rotation packets precede the use packet, as for manual placement.
                delay = 3;
            }
            case "eat", "hold-use" -> {
                idleClicks();
                if (digging != null || walking || eating > 0 || mc.screen != null)
                    throw new IllegalStateException(
                            "Held use requires idle gameplay with no screen");
                int ticks = value.isBlank() ? 40 : Integer.parseInt(value.trim());
                if (ticks < 1 || ticks > 1200)
                    throw new IllegalArgumentException("Held-use ticks must be 1..1200");
                // Ordinary right-click prefers a looked-at chest over food. Look above it first.
                if (a[0].equals("eat")) aim(mc, mc.player.getEyePosition().add(0, 10, 0));
                eating = ticks;
                mc.options.keyUse.setDown(true);
            }
            case "close" -> closeMenu(mc);
            case "drop" -> {
                mc.player.drop(false);
            }
            case "advancements" ->
                    mc.setScreen(
                            new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(
                                    mc.player.connection.getAdvancements()));
            case "button" -> {
                var v = value.split(" ");
                try {
                    var method =
                            net.minecraft.client.MouseHandler.class.getDeclaredMethod(
                                    "onPress", long.class, int.class, int.class, int.class);
                    method.setAccessible(true);
                    method.invoke(
                            mc.mouseHandler,
                            mc.getWindow().getWindow(),
                            Integer.parseInt(v[0]),
                            Integer.parseInt(v[1]),
                            0);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            case "hover" -> {
                var v = value.split(" ");
                try {
                    var x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                    var y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                    x.setAccessible(true);
                    y.setAccessible(true);
                    x.setDouble(
                            mc.mouseHandler,
                            Double.parseDouble(v[0]) * mc.getWindow().getGuiScale());
                    y.setDouble(
                            mc.mouseHandler,
                            Double.parseDouble(v[1]) * mc.getWindow().getGuiScale());
                    mc.screen.mouseMoved(Double.parseDouble(v[0]), Double.parseDouble(v[1]));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            case "pan" -> {
                var v = value.split(" ");
                double x = mc.screen.width / 2d, y = mc.screen.height / 2d;
                mc.screen.mouseClicked(x, y, 0);
                mc.screen.mouseDragged(x, y, 0, Double.parseDouble(v[0]), 0);
                mc.screen.mouseReleased(x, y, 0);
            }
            case "language" -> {
                mc.options.languageCode = value;
                mc.getLanguageManager().setSelected(value);
                mc.reloadResourcePacks();
            }
            default -> throw new IllegalArgumentException(arg);
        }
    }

    /** Read-only guard: scripts are usable only in the named copied survival save. */
    private static void requireSurvival(Minecraft mc) {
        requireSurvival(mc, false);
    }

    private static void requireSurvival(Minecraft mc, boolean allowDead) {
        var server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || mc.gameMode == null)
            throw new IllegalStateException("Open the copied survival world first");
        String world;
        boolean operator;
        if (server != null) {
            world =
                    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .toAbsolutePath()
                            .normalize()
                            .getFileName()
                            .toString();
            var owner = server.getPlayerList().getPlayer(mc.player.getUUID());
            operator =
                    owner == null
                            || owner.hasPermissions(2)
                            || server.getPlayerList().isOp(mc.player.getGameProfile());
        } else {
            // This local dedicated harness has no integrated server. Read its configuration;
            // never grant permission, change worlds, or send a server command to establish
            // identity.
            var endpoint = mc.getCurrentServer();
            if (endpoint == null
                    || !(endpoint.ip.equals("127.0.0.1:25576")
                            || endpoint.ip.equals("localhost:25576")))
                throw new IllegalStateException("Requires the local Stage2 dedicated endpoint");
            var directory = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!directory.endsWith(java.nio.file.Path.of("build", "stage2", "instance")))
                throw new IllegalStateException("Requires the Stage2 instance directory");
            try {
                var properties = new java.util.Properties();
                try (var reader =
                        java.nio.file.Files.newBufferedReader(
                                directory.resolve("server.properties"))) {
                    properties.load(reader);
                }
                world = properties.getProperty("level-name", "");
                var ops =
                        com.google.gson.JsonParser.parseString(
                                        java.nio.file.Files.readString(
                                                directory.resolve("ops.json")))
                                .getAsJsonArray();
                operator =
                        !ops.isEmpty()
                                || mc.player.hasPermissions(2)
                                || !properties.getProperty("gamemode", "").equals("survival")
                                || !properties.getProperty("difficulty", "").equals("normal");
            } catch (java.io.IOException error) {
                throw new IllegalStateException(
                        "Cannot verify local dedicated survival identity", error);
            }
        }
        if (!world.equals("regression-stage2-survival-20261110")
                || !mc.player.getGameProfile().getName().equals("Prospector")
                || mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL
                || mc.player.isDeadOrDying() && !allowDead
                || mc.player.getAbilities().instabuild
                || mc.player.getAbilities().mayfly
                || operator
                || !mc.player
                        .getUUID()
                        .equals(java.util.UUID.fromString("b0785dae-4536-3d8c-8c7d-5880c312c9f7")))
            throw new IllegalStateException(
                    "Requires living non-OP Prospector in Stage2 survival COPY; world=" + world);
    }

    private static int itemCount(Minecraft mc, String item) {
        int count = 0;
        for (var stack : mc.player.getInventory().items)
            if (!stack.isEmpty() && id(stack).equals(item)) count += stack.getCount();
        return count;
    }

    private static void requireRange(int actual, int min, int max, String label) {
        if (min < 0 || min > max || actual < min || actual > max)
            throw new IllegalStateException(
                    label + " actual=" + actual + " expected=" + min + ".." + max);
    }

    private static int boundedWait(String value) {
        int ticks = Integer.parseInt(value);
        requireRange(ticks, 1, 18000, "wait ticks");
        return ticks;
    }

    /** Click the screen's real widget, including its ordinary enabled/disabled hit test. */
    private static void console(Minecraft mc, String value) {
        requireSurvival(mc);
        var fields = value.trim().split("\\s+");
        if (fields.length != 2)
            throw new IllegalArgumentException("guide console mining|oil action");
        var menu = mc.player.containerMenu;
        checkMenu(mc, menu);
        emptyCursor(menu);
        if (!net.neoforged.fml.ModList.get().isLoaded("starfold_mining"))
            throw new IllegalStateException("Mining module is not loaded");
        DevMiningGuideProbe.console(mc, fields[0], fields[1]);
        log(mc, "console-click " + value);
    }

    /** Only synchronized menu fields: never prospect or read a server machine directly. */
    private static int menuValue(AbstractContainerMenu menu, String kind, String field) {
        if (!net.neoforged.fml.ModList.get().isLoaded("starfold_mining"))
            throw new IllegalStateException("Mining module is not loaded");
        return DevMiningGuideProbe.menuValue(menu, kind, field);
    }

    /** One normal dig, then wait for the actual one-block landing before considering another. */
    private static boolean stepDown(Minecraft mc) {
        requireSurvival(mc);
        if (--downWait <= 0) throw new IllegalStateException("mine-down landing timed out");
        if (downLanding != null) {
            if (Math.abs(mc.player.getX() - (downLanding.getX() + .5)) > .20
                    || Math.abs(mc.player.getZ() - (downLanding.getZ() + .5)) > .20
                    || mc.player.getY() < downLanding.getY() - .05)
                throw new IllegalStateException("mine-down left its one-block landing");
            if (!mc.player.onGround() || Math.abs(mc.player.getY() - downLanding.getY()) > .03)
                return true;
            log(mc, "mine-down-landed " + downLanding);
            downLanding = null;
            downRemaining--;
            downWait = 60;
            return downRemaining > 0;
        }
        if (!mc.player.onGround()) return true;
        var feet = mc.player.blockPosition();
        // A normal player's 0.6-wide body must fit entirely over this full floor.
        if (Math.abs(mc.player.getX() - (feet.getX() + .5)) > .20
                || Math.abs(mc.player.getZ() - (feet.getZ() + .5)) > .20
                || Math.abs(mc.player.getY() - feet.getY()) > .03)
            throw new IllegalStateException("Center on a full block before mine-down");
        var target = feet.below();
        checkDownBoundary(mc, target);
        var state = mc.level.getBlockState(target);
        if (!state.isCollisionShapeFullBlock(mc.level, target)
                || !mc.level
                        .getBlockState(target.below())
                        .isCollisionShapeFullBlock(mc.level, target.below())
                || mc.level.getBlockEntity(target) != null
                || state.getDestroySpeed(mc.level, target) < 0
                || !mc.player.hasCorrectToolForDrops(state))
            throw new IllegalStateException(
                    "Need mineable full floor and solid next landing " + target);
        var tool = mc.player.getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() < 2)
            throw new IllegalStateException("Replace nearly broken tool before descent");
        var hit = aimBlock(mc, target);
        leafGoal = null;
        digging = target;
        diggingState = mc.level.getBlockState(digging);
        left = 200;
        downLanding = target;
        downWait = 60;
        mc.gameMode.startDestroyBlock(target, hit.getDirection());
        log(mc, "mine-down-start " + target + " remaining=" + downRemaining);
        return true;
    }

    /** Recheck throughout each dig, including ticks before continueDestroyBlock. */
    private static void checkDownBoundary(Minecraft mc, BlockPos target) {
        requireSurvival(mc);
        if (mc.screen != null
                || mc.options.keyUp.isDown()
                || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown()
                || mc.options.keyAttack.isDown()
                || mc.options.keyUse.isDown()
                || mc.player.isInWater())
            throw new IllegalStateException("mine-down interrupted by screen, held input or water");
        if (target == null) return;
        if (Math.abs(mc.player.getX() - (target.getX() + .5)) > .20
                || Math.abs(mc.player.getZ() - (target.getZ() + .5)) > .20
                || mc.player.getY() < target.getY() - .05
                || !mc.level
                        .getBlockState(target.below())
                        .isCollisionShapeFullBlock(mc.level, target.below()))
            throw new IllegalStateException("mine-down lost its centered solid landing " + target);
        var floor = mc.level.getBlockState(target);
        if (!floor.isAir()
                && (mc.level.getBlockEntity(target) != null
                        || !floor.isCollisionShapeFullBlock(mc.level, target)
                        || floor.getDestroySpeed(mc.level, target) < 0
                        || !mc.player.hasCorrectToolForDrops(floor)))
            throw new IllegalStateException("mine-down floor/tool changed " + target);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                for (int y = -1; y <= 3; y++) {
                    var pos = target.offset(x, y, z);
                    if (!mc.level.hasChunkAt(pos))
                        throw new IllegalStateException("Unloaded descent boundary " + pos);
                    var state = mc.level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty()
                            || state.getBlock()
                                    instanceof net.minecraft.world.level.block.FallingBlock
                            || state.is(net.minecraft.tags.BlockTags.FIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                            || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                            || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW))
                        throw new IllegalStateException(
                                "Unsafe descent boundary " + pos + " " + state);
                }
    }

    private static void idleClicks() {
        if (!clicks.isEmpty() || awaiting != null)
            throw new IllegalStateException("Container clicks are still pending");
    }

    private static void checkMenu(Minecraft mc, AbstractContainerMenu menu) {
        if (mc.player.containerMenu != menu
                || !(mc.screen instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != menu)
            throw new IllegalStateException("The original container must stay open");
    }

    private static void emptyCursor(AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty())
            throw new IllegalStateException("Empty the cursor before container clicks");
    }

    private static void await(java.util.function.BooleanSupplier ready, String label) {
        awaiting = ready;
        waitLeft = 200;
        waitLabel = label;
    }

    private static void awaitMenu(
            Minecraft mc, AbstractContainerMenu menu, int state, String label) {
        await(
                () -> {
                    checkMenu(mc, menu);
                    return menu.getStateId() != state && menu.getCarried().isEmpty();
                },
                label + " server update");
    }

    /** Pick up the source stack, place individual items, then return the remainder. */
    private static int transfer(
            Minecraft mc, AbstractContainerMenu menu, int source, int target, int count) {
        checkMenu(mc, menu);
        emptyCursor(menu);
        if (source < 0
                || source >= menu.slots.size()
                || target < 0
                || target >= menu.slots.size()
                || source == target
                || count < 1) throw new IllegalArgumentException("Invalid source, target or count");
        var from = menu.getSlot(source);
        var to = menu.getSlot(target);
        var stack = from.getItem();
        var existing = to.getItem();
        if (stack.getCount() < count
                || !from.mayPickup(mc.player)
                || (stack.getCount() > count && !from.mayPlace(stack))
                || !to.mayPlace(stack)
                || (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(stack, existing))
                || to.getMaxStackSize(stack) - existing.getCount() < count)
            throw new IllegalStateException(
                    "Cannot move " + count + " items from " + source + " to " + target);
        int state = menu.getStateId();
        int expected = existing.getCount() + count;
        mc.gameMode.handleInventoryMouseClick(
                menu.containerId, source, 0, ClickType.PICKUP, mc.player);
        for (int i = 0; i < count; i++)
            mc.gameMode.handleInventoryMouseClick(
                    menu.containerId, target, 1, ClickType.PICKUP, mc.player);
        if (!menu.getCarried().isEmpty())
            mc.gameMode.handleInventoryMouseClick(
                    menu.containerId, source, 0, ClickType.PICKUP, mc.player);
        if (!menu.getCarried().isEmpty() || to.getItem().getCount() != expected)
            throw new IllegalStateException("Transfer was rejected; inspect the container");
        // Correctly predicted ordinary clicks need no server acknowledgement packet.
        return state;
    }

    private static void craft(Minecraft mc, String value, int cells, int sourceStart) {
        craft(mc, value, cells, sourceStart, 1);
    }

    private static void craft(Minecraft mc, String value, int cells, int sourceStart, int count) {
        idleClicks();
        var menu = mc.player.containerMenu;
        checkMenu(mc, menu);
        emptyCursor(menu);
        var grid = value.split(",", -1);
        if (grid.length != cells) throw new IllegalArgumentException(cells + " cells required");
        var needed = new java.util.HashMap<String, Integer>();
        var expectedIds = new String[cells];
        var expectedCounts = new int[cells];
        for (int i = 0; i < cells; i++) {
            grid[i] = grid[i].trim();
            var existing = menu.getSlot(i + 1).getItem();
            expectedIds[i] = id(existing);
            expectedCounts[i] = existing.getCount();
            if (!grid[i].isEmpty()) {
                if (!existing.isEmpty() && !id(existing).equals(grid[i]))
                    throw new IllegalStateException(
                            "Different ingredient already in cell " + (i + 1));
                expectedIds[i] = grid[i];
                expectedCounts[i] += count;
                if (expectedCounts[i] > 64)
                    throw new IllegalStateException("Ingredient cell overflow");
                needed.merge(grid[i], count, Integer::sum);
            }
        }
        for (var entry : needed.entrySet()) {
            int available = 0;
            for (var slot : menu.slots)
                if (slot.index >= sourceStart
                        && slot.container == mc.player.getInventory()
                        && id(slot.getItem()).equals(entry.getKey()))
                    available += slot.getItem().getCount();
            if (available < entry.getValue())
                throw new IllegalStateException("Missing ingredient " + entry.getKey());
        }
        for (int i = 0; i < cells; i++)
            if (!grid[i].isEmpty()) {
                final int target = i + 1;
                final String item = grid[i];
                clicks.add(
                        () -> {
                            checkMenu(mc, menu);
                            int remaining = count;
                            int state = menu.getStateId();
                            while (remaining > 0) {
                                int source = -1;
                                for (var slot : menu.slots)
                                    if (slot.index >= sourceStart
                                            && slot.container == mc.player.getInventory()
                                            && slot.hasItem()
                                            && id(slot.getItem()).equals(item)) {
                                        source = slot.index;
                                        break;
                                    }
                                if (source < 0)
                                    throw new IllegalStateException("Missing ingredient " + item);
                                int moved =
                                        Math.min(
                                                remaining,
                                                menu.getSlot(source).getItem().getCount());
                                state = transfer(mc, menu, source, target, moved);
                                remaining -= moved;
                            }
                            // A crafting-grid change sends a server-computed result, even if empty.
                            awaitMenu(mc, menu, state, "crafting cell " + target);
                        });
            }
        clicks.add(
                () ->
                        await(
                                () -> {
                                    checkMenu(mc, menu);
                                    for (int i = 0; i < cells; i++) {
                                        var stack = menu.getSlot(i + 1).getItem();
                                        if (stack.getCount() != expectedCounts[i]
                                                || (expectedCounts[i] > 0
                                                        && !id(stack).equals(expectedIds[i])))
                                            return false;
                                    }
                                    // Crafting results are supplied asynchronously by the server.
                                    return menu.getSlot(0).hasItem();
                                },
                                "crafting result"));
        clicks.add(
                () -> {
                    checkMenu(mc, menu);
                    emptyCursor(menu);
                    var result = menu.getSlot(0).getItem().copy();
                    if (result.isEmpty()) throw new IllegalStateException("No crafting result");
                    int before = inventoryCount(mc, result);
                    int state = menu.getStateId();
                    log(mc, "craft-result " + result);
                    mc.gameMode.handleInventoryMouseClick(
                            menu.containerId, 0, 0, ClickType.QUICK_MOVE, mc.player);
                    if (inventoryCount(mc, result) < before + result.getCount())
                        throw new IllegalStateException(
                                "Crafting output did not move; need inventory space");
                    await(
                            () -> {
                                checkMenu(mc, menu);
                                return menu.getStateId() != state
                                        && inventoryCount(mc, result) >= before + result.getCount();
                            },
                            "crafted items in inventory");
                });
        clicks.add(() -> log(mc, "crafted"));
        delay = 0;
    }

    private static int inventoryCount(Minecraft mc, ItemStack match) {
        int count = 0;
        for (var stack : mc.player.getInventory().items)
            if (ItemStack.isSameItemSameComponents(stack, match)) count += stack.getCount();
        return count;
    }

    /** Clear actual picked leaves or higher blocks of the same trunk before its requested base. */
    private static void startLeafApproach(Minecraft mc) {
        aim(mc, Vec3.atCenterOf(leafGoal));
        BlockPos target = leafGoal;
        if (mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && !hit.getBlockPos().equals(leafGoal)) {
            var obstruction = hit.getBlockPos();
            var state = mc.level.getBlockState(obstruction);
            boolean sameTrunk =
                    state.is(net.minecraft.tags.BlockTags.LOGS)
                            && obstruction.getX() == leafGoal.getX()
                            && obstruction.getZ() == leafGoal.getZ()
                            && obstruction.getY() > leafGoal.getY()
                            && obstruction.getY() <= leafGoal.getY() + 4
                            && mc.level
                                    .getBlockState(leafGoal)
                                    .is(net.minecraft.tags.BlockTags.LOGS);
            if (++clearedLeaves > 12
                    || (!state.is(net.minecraft.tags.BlockTags.LEAVES) && !sameTrunk))
                throw new IllegalStateException("Leaf/trunk approach blocked by " + obstruction);
            target = hit.getBlockPos().immutable();
        }
        var hit = picked(mc, target);
        digging = target;
        diggingState = mc.level.getBlockState(digging);
        left = 200;
        mc.gameMode.startDestroyBlock(target, hit.getDirection());
        log(mc, "dig-start " + target + " toward " + leafGoal);
    }

    /** Sample visible points on the requested block, always using the actual camera pick. */
    private static BlockHitResult aimBlock(Minecraft mc, BlockPos target) {
        aim(mc, Vec3.atCenterOf(target));
        if (isPicked(mc, target)) return picked(mc, target);
        for (var face : Direction.values())
            for (double u : new double[] {0, -.4, .4})
                for (double v : new double[] {0, -.4, .4}) {
                    var offset =
                            switch (face.getAxis()) {
                                case X -> new Vec3(0, u, v);
                                case Y -> new Vec3(u, 0, v);
                                case Z -> new Vec3(u, v, 0);
                            };
                    aim(
                            mc,
                            Vec3.atCenterOf(target)
                                    .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499))
                                    .add(offset));
                    if (isPicked(mc, target)) return picked(mc, target);
                }
        return picked(mc, target);
    }

    /** Aim at an exposed requested face; occluded faces never get a fabricated hit. */
    private static BlockHitResult aimFace(Minecraft mc, BlockPos target, Direction face) {
        for (double u : new double[] {0, -.4, .4})
            for (double v : new double[] {0, -.4, .4}) {
                var offset =
                        switch (face.getAxis()) {
                            case X -> new Vec3(0, u, v);
                            case Y -> new Vec3(u, 0, v);
                            case Z -> new Vec3(u, v, 0);
                        };
                aim(
                        mc,
                        Vec3.atCenterOf(target)
                                .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499))
                                .add(offset));
                if (isPicked(mc, target)) {
                    var hit = picked(mc, target);
                    if (hit.getDirection() == face && !hit.isInside()) return hit;
                }
            }
        throw new IllegalStateException("No reachable " + face + " face of " + target);
    }

    private static boolean isPicked(Minecraft mc, BlockPos target) {
        return mc.screen == null
                && mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target)
                && mc.player.getEyePosition().distanceTo(hit.getLocation())
                        <= mc.player.blockInteractionRange();
    }

    private static BlockHitResult picked(Minecraft mc, BlockPos target) {
        if (mc.screen != null
                || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(target)
                || mc.player.getEyePosition().distanceTo(hit.getLocation())
                        > mc.player.blockInteractionRange())
            throw new IllegalStateException(
                    "Requested block is not the reachable picked block "
                            + target
                            + "; actual="
                            + (mc.hitResult instanceof BlockHitResult actual
                                    ? actual.getBlockPos() + " " + actual.getDirection()
                                    : mc.hitResult));
        return hit;
    }

    /** Use the actual inventory screen's close handler, as Escape does. */
    private static void closeMenu(Minecraft mc) {
        if (mc.screen
                instanceof
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
            screen.onClose();
            if (mc.screen == screen) mc.setScreen(null);
        } else if (mc.screen == null && mc.player.containerMenu != mc.player.inventoryMenu)
            mc.player.closeContainer();
        else if (mc.screen != null)
            throw new IllegalStateException("Close requires an ordinary inventory screen");
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void aim(Minecraft mc, Vec3 target) {
        var d = target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        mc.gameRenderer.pick(1);
    }

    private static void log(Minecraft mc, String label) {
        var items = new java.util.TreeMap<String, Integer>();
        for (var item : mc.player.getInventory().items)
            if (!item.isEmpty()) items.merge(id(item), item.getCount(), Integer::sum);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE GUIDE {} wall {} mode {} pos {} storage {} inventory {} screen {}",
                        label,
                        System.currentTimeMillis(),
                        mc.gameMode.getPlayerMode(),
                        mc.player.position(),
                        GroundClient.storage(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                        items,
                        mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
    }

    private DevGuideProbe() {}
}
