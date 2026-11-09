package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class DevPositionDiagnosticsTest {
    @Test
    void defaultOffDoesNotEvaluatePayloadOrCollectState() {
        assertFalse(DevPositionDiagnostics.enabled());
        assertNull(DevPositionDiagnostics.begin(null, "test", () -> {
            fail("disabled diagnostic evaluated its payload");
            return "";
        }, true));
        DevPositionDiagnostics.end(null);
    }

    @Test
    void wrappersExecuteTheOriginalExactlyOnceWithIdenticalArgumentsAndPropagateExceptions() throws Exception {
        for (var name : List.of("GroundPositionDiagnosticsMixin", "GroundPositionDiagnosticsNetworkMixin")) {
            // Mixin rightly forbids loading its classes as normal game classes. Execute the compiled
            // wrappers in an isolated loader, changing only the class's abstract flag.
            var node = new ClassNode();
            try (var in = getClass().getResourceAsStream("/dev/starfold/probes/mixin/" + name + ".class")) {
                assertNotNull(in);
                new ClassReader(in).accept(node, 0);
            }
            node.access &= ~Opcodes.ACC_ABSTRACT;
            var writer = new ClassWriter(0);
            node.accept(writer);
            var type = new ClassLoader(getClass().getClassLoader()) {
                Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
            }.define(writer.toByteArray());
            var owner = type.getConstructor().newInstance();
            for (var method : type.getDeclaredMethods()) {
                if (!method.getName().startsWith("starfold$diagnostic")) continue;
                method.setAccessible(true);
                Object[] input = switch (method.getName()) {
                    case "starfold$diagnosticPosition" -> new Object[] {null, new Vec3(4352.5, 158, 256.5), null};
                    case "starfold$diagnosticDimension" -> new Object[] {null, null, null};
                    case "starfold$diagnosticTeleport" -> new Object[] {1d, 2d, 3d, 4f, 5f, Set.of(), null};
                    default -> new Object[] {null, null};
                };
                int arity = input.length - 1;
                var calls = new AtomicInteger();
                input[arity] = (Operation<Void>) args -> {
                    calls.incrementAndGet();
                    assertEquals(arity, args.length);
                    for (int i = 0; i < arity; i++) assertEquals(input[i], args[i]);
                    return null;
                };
                method.invoke(owner, input);
                assertEquals(1, calls.get(), method.getName());
                var originalFailure = new IllegalStateException("original exception");
                input[arity] = (Operation<Void>) args -> { throw originalFailure; };
                var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(owner, input));
                assertSame(originalFailure, failure.getCause());
            }
        }
    }

    @Test
    void eventRetainsCausalIdsPositionsAndAwaitingStateWithoutFillingMissingMoveCoordinates() {
        var before = new DevPositionDiagnostics.State("starfold:native_planet", -16383.5, 8191.1, -5991.5,
                7, new Vec3(4352.5, 158, 256.5), 24);
        var after = new DevPositionDiagnostics.State("starfold:native_planet", 4352.5, 158, 256.5, 7, null, 24);
        var enter = new DevPositionDiagnostics.Event(11, 8, 2, "test-uuid", "handle-ack", "before",
                960, "Server thread", before, "ackId=7", List.of());
        var end = new DevPositionDiagnostics.Event(12, enter.id(), enter.connection(), enter.uuid(),
                enter.operation(), "finally", 960, "Server thread", after, "", List.of());
        assertEquals(11, end.parent());
        assertEquals(2, end.connection());
        assertEquals(960, end.serverTick());
        assertEquals(4352.5, enter.state().awaiting().x);
        assertEquals(enter.state().teleportId(), end.state().teleportId());
        assertNotEquals(enter.state().x(), end.state().x());
        assertNull(end.state().awaiting());
        assertEquals("hasPosition=false xyz=absent hasRotation=false yaw=0.0 pitch=0.0 onGround=true",
                DevPositionDiagnostics.move(new ServerboundMovePlayerPacket.StatusOnly(true)));
        assertTrue(DevPositionDiagnostics.move(new ServerboundMovePlayerPacket.Pos(1, 2, 3, false))
                .startsWith("hasPosition=true xyz=[1.0, 2.0, 3.0]"));
    }
}
