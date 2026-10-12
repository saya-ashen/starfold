import com.sun.source.tree.*;
import com.sun.source.util.*;

import java.nio.file.*;
import java.util.*;

import javax.tools.*;

/** Parses Java declarations (including multiline signatures), without resolving dependencies. */
public class MethodLengths {
    public static void main(String[] args) throws Exception {
        var root = Path.of("src/main/java/dev/starfold");
        var exceptions =
                Map.of(
                        "ship/Airstair.java#tickServerStair",
                        "Per-stair motion, settling and publication share at/out, chart/gravity/lift, poseSpeed/linear/angular/speed, flight/planned/clear/reach, before and why. Further step boundaries need >6 parameters or a new per-tick carrier.",
                        "space/SpaceServer.java#advanceLead",
                        "Arrival/attitude/cruise/probes share model/tick, point/before, v/prior/push/impulse/towardEntry, destination/entryHeight/entryClosing/remaining, oldArrival/wasArriving/arriving/blend, universe/nearest and targetVelocity, alongside level/container/bodies/lead/value. Further pipeline boundaries need >6 parameters or a new per-tick carrier.");
        var files = new ArrayList<java.io.File>();
        try (var paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(
                            p ->
                                    !root.relativize(p).startsWith("dev")
                                            && !root.relativize(p).startsWith("mixin"))
                    .sorted()
                    .forEach(p -> files.add(p.toFile()));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    null,
                                    List.of("-proc:none"),
                                    null,
                                    manager.getJavaFileObjectsFromFiles(files));
            var trees = Trees.instance(task);
            int[] counts = {0, 0, 0};
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (method.getBody() != null) {
                            var positions = trees.getSourcePositions();
                            long start = positions.getStartPosition(unit, method);
                            long end = positions.getEndPosition(unit, method);
                            long first = unit.getLineMap().getLineNumber(start);
                            long last = unit.getLineMap().getLineNumber(end - 1);
                            long length = last - first + 1;
                            counts[0]++;
                            if (length >= 120) {
                                counts[1]++;
                                var path = Path.of(unit.getSourceFile().toUri());
                                var relative = root.toAbsolutePath().relativize(path);
                                var key = relative + "#" + method.getName();
                                var reason = exceptions.get(key);
                                System.out.printf(
                                        "%s:%d %s %d lines%n",
                                        root.resolve(relative), first, method.getName(), length);
                                if (reason != null) System.out.println("  EXCEPTION: " + reason);
                                else if (length > 120) counts[2]++;
                            }
                        }
                        return super.visitMethod(method, unused);
                    }
                }.scan(unit, null);
            }
            System.out.printf(
                    "Scanned %d methods; %d at least 120 lines; %d unapproved over 120.%n",
                    counts[0], counts[1], counts[2]);
            if (counts[2] != 0 && !Arrays.asList(args).contains("--inventory")) System.exit(1);
        }
    }
}
