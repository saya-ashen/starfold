package dev.starfold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Sources {
    private Sources() {}

    public static SourceText text(String name) throws IOException {
        return SourceText.of(Files.readString(path(name)));
    }

    static Path path(String name) throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var matches =
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(name + ".java"))
                            .toList();
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
