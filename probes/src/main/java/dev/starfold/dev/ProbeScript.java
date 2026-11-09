package dev.starfold.dev;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Script cursor only: a false command result preserves its tick boundary and owns line advancement. */
final class ProbeScript {
    List<String> lines = List.of();
    int line, wait;
    boolean strict;

    void tick(BiPredicate<String, String> command, UnaryOperator<String> expand, Consumer<String> fail) {
        while (line < lines.size()) {
            if (wait > 0) {
                wait--;
                return;
            }
            String[] p = lines.get(line).trim().split("\\s+", 2);
            String arg = p.length > 1 ? p[1] : "";
            if (arg.contains("{")) arg = expand.apply(arg);
            if (!command.test(p[0], arg)) return;
            if (strict && line == lines.size() - 2 && !p[0].equals("shot")) {
                fail.accept("guard " + p[0]);
                return;
            }
            line++;
        }
    }
}
