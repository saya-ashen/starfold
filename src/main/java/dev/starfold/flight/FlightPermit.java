package dev.starfold.flight;

import dev.starfold.ground.GroundServer;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Who may fly on a planet. The planet's flight is vanilla flight near the ground and the vessel above it, so the permit
 *  is the vanilla one: a player who holds it may fly, and loses it with whatever granted it. Ships and suits belong to
 *  other mods; they grant the permit by putting their equipment in {@link #TAG} or by {@link #grant}. The mod's own
 *  pack is the plainest such equipment. */
public final class FlightPermit {
    /** Worn in any armour slot, an item of this tag grants the permit. */
    public static final TagKey<Item> TAG =
            TagKey.create(
                    Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("starfold", "flight_permit"));

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold");
    public static Item PACK;
    private static final List<Predicate<Player>> grants = new CopyOnWriteArrayList<>();

    private static final class Pack extends Item implements Equipable {
        Pack() {
            super(new Item.Properties().stacksTo(1));
        }

        @Override
        public EquipmentSlot getEquipmentSlot() {
            return EquipmentSlot.CHEST;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(
                Level level, Player player, InteractionHand hand) {
            return swapWithEquipmentSlot(this, level, player, hand);
        }
    }

    /** Register the item before creative tab contents are built. */
    public static void initialize(IEventBus modBus) {
        ITEMS.register("thruster_pack", () -> PACK = new Pack());
        ITEMS.register(modBus);
        modBus.addListener(
                (BuildCreativeModeTabContentsEvent event) -> {
                    if (event.getTabKey()
                            .equals(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES))
                        event.accept(PACK);
                });
        // The permit is the planet's: whoever leaves for another world keeps only what their game
        // mode gives.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent
                                cpEvent) -> {
                    var player = (net.minecraft.server.level.ServerPlayer) cpEvent.getEntity();
                    var destination = player.getServer().getLevel(cpEvent.getTo());
                    if (!GroundServer.is(destination)) revoke(player);
                });
    }

    /** Another mod's own condition for flight: a seat in its ship, a charged suit. Asked on both sides, every tick. */
    public static void grant(Predicate<Player> condition) {
        grants.add(java.util.Objects.requireNonNull(condition));
    }

    public static boolean granted(Player player) {
        if (player.isCreative()) return true;
        if (player.isSpectator()) return false;
        for (var slot : EquipmentSlot.values())
            if (slot.isArmor() && player.getItemBySlot(slot).is(TAG)) return true;
        for (var grant : grants) if (grant.test(player)) return true;
        return false;
    }

    /** Gives or takes the vanilla permit to match, and tells the client when it changed. Creative flight is left alone. */
    public static boolean sync(ServerPlayer player) {
        boolean granted = granted(player);
        if (player.isCreative() || player.isSpectator()) return granted;
        var abilities = player.getAbilities();
        if (abilities.mayfly != granted) {
            abilities.mayfly = granted;
            if (!granted) abilities.flying = false;
            player.onUpdateAbilities();
        }
        return granted;
    }

    public static void revoke(ServerPlayer player) {
        player.gameMode.getGameModeForPlayer().updatePlayerAbilities(player.getAbilities());
        player.onUpdateAbilities();
    }

    private FlightPermit() {}
}
