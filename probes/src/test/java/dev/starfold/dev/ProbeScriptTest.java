package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeScriptTest {
    @Test void waitConsumesItsCommandTickThenExactlyNMoreTicks() {
        var script = new ProbeScript();
        script.lines = List.of("log before", "wait 2", "log after");
        var actions = new ArrayList<String>();
        java.util.function.BiPredicate<String, String> command = (name, arg) -> {
            if (name.equals("wait")) { script.wait = Integer.parseInt(arg); script.line++; return false; }
            actions.add(arg); return true;
        };
        script.tick(command, s -> s, reason -> fail(reason));
        assertEquals(List.of("before"), actions);
        assertEquals(2, script.line);
        script.tick(command, s -> s, reason -> fail(reason));
        script.tick(command, s -> s, reason -> fail(reason));
        assertEquals(List.of("before"), actions);
        script.tick(command, s -> s, reason -> fail(reason));
        assertEquals(List.of("before", "after"), actions);
        assertEquals(3, script.line);
    }

    @Test void pendingCommandRetriesSameLineUntilAcknowledged() {
        var script = new ProbeScript();
        script.lines = List.of("receipt", "done");
        var seen = new ArrayList<String>();
        script.tick((name, arg) -> { seen.add(name); return false; }, s -> s, reason -> fail(reason));
        assertEquals(0, script.line);
        script.tick((name, arg) -> { seen.add(name); return true; }, s -> s, reason -> fail(reason));
        assertEquals(List.of("receipt", "receipt", "done"), seen);
    }

    @Test void failureOrQuitCanTerminateWithoutImplicitIncrement() {
        for (String stop : List.of("timeout", "failure", "quit")) {
            var script = new ProbeScript();
            script.lines = List.of(stop, "must-not-run");
            var seen = new ArrayList<String>();
            script.tick((name, arg) -> {
                seen.add(name); script.line = script.lines.size(); return false;
            }, s -> s, reason -> fail(reason));
            assertEquals(List.of(stop), seen);
            assertEquals(2, script.line);
        }
    }

    @Test void quitAcknowledgementWaitRetriesQuitBeforeAdvancing() {
        var script = new ProbeScript();
        script.lines = List.of("quit");
        int[] calls = {0};
        java.util.function.BiPredicate<String, String> command = (name, arg) -> {
            if (++calls[0] == 1) script.wait = 60;
            else script.line = script.lines.size();
            return false;
        };
        script.tick(command, s -> s, reason -> fail(reason));
        for (int i = 0; i < 60; i++) script.tick(command, s -> s, reason -> fail(reason));
        assertEquals(1, calls[0]);
        assertEquals(0, script.line);
        script.tick(command, s -> s, reason -> fail(reason));
        assertEquals(2, calls[0]);
        assertEquals(1, script.line);
    }

    @Test void strictGuardAndExpansionPreserveOriginalOrder() {
        var script = new ProbeScript();
        script.lines = List.of("log {boardb}", "quit");
        script.strict = true;
        var actions = new ArrayList<String>();
        script.tick((name, arg) -> { actions.add(name + " " + arg); return true; },
                arg -> "1 2 3", actions::add);
        assertEquals(List.of("log 1 2 3", "guard log"), actions);
        assertEquals(0, script.line);
        script.lines = List.of("shot final", "quit");
        script.tick((name, arg) -> name.equals("shot"), s -> s, reason -> fail(reason));
        assertEquals(1, script.line);
    }
}
