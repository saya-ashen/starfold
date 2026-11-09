package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class StarfoldCommandsTest {
    @Test
    void commandTreeRetainsNavigationAndFaceRanges() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        StarfoldCommands.register(dispatcher);
        var root = dispatcher.getRoot().getChild("starfold");
        for (String name : new String[] {"enter", "terrain", "leave", "status", "universe", "orbit", "nav", "face", "spin"})
            assertNotNull(root.getChild(name), name);
        var face = (ArgumentCommandNode<?, ?>) root.getChild("face").getChild("id");
        var range = (IntegerArgumentType) face.getType();
        assertEquals(0, range.getMinimum());
        assertEquals(5, range.getMaximum());
        var target = (ArgumentCommandNode<?, ?>) root.getChild("nav").getChild("target").getChild("planet");
        range = (IntegerArgumentType) target.getType();
        assertEquals(0, range.getMinimum());
        assertEquals(dev.starfold.planet.PlanetStorage.MAX_PLANETS - 1, range.getMaximum());
        for (String mode : new String[] {"brake", "land"}) {
            assertNotNull(root.getChild("nav").getChild(mode).getChild("on"));
            assertNotNull(root.getChild("nav").getChild(mode).getChild("off"));
        }
    }
}
