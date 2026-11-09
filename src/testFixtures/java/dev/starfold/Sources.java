package dev.starfold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Sources {
    private Sources() {}

    public static SourceText text(String name) throws IOException {
        return SourceText.of(Files.readString(path(name)));
    }

    /** Explicit repository paths for contracts spanning a small set of implementation owners. */
    public static SourceText textPaths(String... paths) throws IOException {
        var text = new StringBuilder();
        for (String path : paths) text.append(Files.readString(Path.of(path))).append('\n');
        return SourceText.of(text.toString());
    }

    static Path path(String name) throws IOException {
        if (name.endsWith(".java")) return Path.of(name);
        var matches = new java.util.ArrayList<Path>();
        try (var projects = Files.list(Path.of("."))) {
            var roots = new java.util.ArrayList<Path>();
            roots.add(Path.of("src/main/java"));
            projects.filter(Files::isDirectory).map(p -> p.resolve("src/main/java"))
                    .filter(Files::isDirectory).sorted().forEach(roots::add);
            for (var root : roots) {
                try (var files = Files.walk(root)) {
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(name + ".java"))
                            .forEach(matches::add);
                }
            }
            if (matches.size() != 1)
                throw new IOException(
                        "Expected one main source for "
                                + name
                                + ", found "
                                + matches.size()
                                + ": "
                                + matches);
            return matches.getFirst();
        }
    }
}
