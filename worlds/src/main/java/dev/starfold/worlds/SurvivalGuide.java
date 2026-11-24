package dev.starfold.worlds;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/** Pack-opted-in survival help. No client classes or optional content dependencies. */
public final class SurvivalGuide {
    private static final TagKey<Item> ENABLED =
            TagKey.create(
                    Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("starfold", "survival_guide_enabled"));
    private static final String DATA = "starfold:survival_guide";
    private static final String RECEIVED = "received";
    private static final String NEXT_REQUEST = "next_request_tick";
    private static final long COOLDOWN_TICKS = 20 * 30;

    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedInEvent event) -> firstDelivery(event.getEntity()));
        // A player can log in while still on the death screen. Wait until their new
        // inventory exists; never record receipt in an inventory that respawn discards.
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerRespawnEvent event) -> firstDelivery(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.Clone event) -> {
                    // NeoForge already carries PlayerPersisted across respawns. Copy only our own
                    // compound as well, avoiding aliases and preserving other mods' persisted keys.
                    CompoundTag old =
                            event.getOriginal()
                                    .getPersistentData()
                                    .getCompound(Player.PERSISTED_NBT_TAG);
                    if (old.contains(DATA, Tag.TAG_COMPOUND)) {
                        CompoundTag target = persisted(event.getEntity());
                        target.put(DATA, old.getCompound(DATA).copy());
                    }
                });
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("starfoldguide")
                                                .executes(
                                                        context ->
                                                                replacement(
                                                                        context.getSource(), null))
                                                .then(
                                                        Commands.literal("zh_cn")
                                                                .executes(
                                                                        context ->
                                                                                replacement(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "zh_cn")))
                                                .then(
                                                        Commands.literal("en_us")
                                                                .executes(
                                                                        context ->
                                                                                replacement(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "en_us")))));
    }

    private static void firstDelivery(Player entity) {
        if (entity instanceof ServerPlayer player
                && player.isAlive()
                && enabled()
                && !receipt(player).getBoolean(RECEIVED)) {
            deliver(player, null);
        }
    }

    private static boolean enabled() {
        // Missing/empty tag is deliberately false, including Worlds alone and Extras installs.
        return Items.WRITTEN_BOOK.builtInRegistryHolder().is(ENABLED);
    }

    private static CompoundTag persisted(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static CompoundTag receipt(Player player) {
        CompoundTag root = persisted(player);
        if (!root.contains(DATA, Tag.TAG_COMPOUND)) root.put(DATA, new CompoundTag());
        return root.getCompound(DATA);
    }

    private static long now(ServerPlayer player) {
        // One persistent server clock across dimensions, reconnects, deaths and restarts.
        return player.server.overworld().getGameTime();
    }

    private static int replacement(CommandSourceStack source, String requestedLanguage)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!enabled()) {
            tell(player, "disabled");
            return 0;
        }
        if (!player.isAlive()) {
            tell(player, "respawn");
            return 0;
        }
        CompoundTag data = receipt(player);
        long tick = now(player);
        if (tick < data.getLong(NEXT_REQUEST)) {
            tell(player, "cooldown");
            return 0;
        }
        // Throttle failed full-inventory requests too. Failure never marks first receipt.
        data.putLong(NEXT_REQUEST, tick + COOLDOWN_TICKS);
        return deliver(player, requestedLanguage) ? 1 : 0;
    }

    private static boolean deliver(ServerPlayer player, String language) {
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0) {
            tell(player, "full");
            return false;
        }
        // Insert into an empty main-inventory slot directly. Inventory.add can silently
        // consume overflow in creative mode; never drop a first guide into the world.
        player.getInventory().setItem(slot, book(language));
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        CompoundTag data = receipt(player);
        data.putBoolean(RECEIVED, true);
        data.putLong(NEXT_REQUEST, now(player) + COOLDOWN_TICKS);
        tell(player, "received");
        return true;
    }

    private static final String TEXT = "guide.starfold_worlds.";
    private static final int PAGE_COUNT = 6;

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.translatable(TEXT + message));
    }

    private static ItemStack book(String requestedLanguage) {
        Map<String, String> translations = requestedLanguage == null ? Map.of() : translations(requestedLanguage);
        String title = translations.getOrDefault(TEXT + "title", "Survival Guide");
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        if (requestedLanguage == null) {
            book.set(DataComponents.ITEM_NAME, Component.translatable(TEXT + "title"));
        }
        var pages =
                IntStream.rangeClosed(1, PAGE_COUNT)
                        .mapToObj(page -> {
                            String key = TEXT + "page." + page;
                            String text = translations.get(key);
                            return Filterable.<Component>passThrough(
                                    text == null ? Component.translatable(key) : Component.literal(text));
                        })
                        .toList();
        book.set(
                DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(title), "Starfold", 0, pages, true));
        return book;
    }

    private static Map<String, String> translations(String language) {
        String path = "/assets/starfold_worlds/lang/" + language + ".json";
        try (var input = Objects.requireNonNull(SurvivalGuide.class.getResourceAsStream(path), path)) {
            Map<String, String> translations = new HashMap<>();
            Language.loadFromJson(input, translations::put);
            return translations;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
