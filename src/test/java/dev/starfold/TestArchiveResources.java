package dev.starfold;

/** Read original dependency bytes: JPMS hides .class/client resources from the transforming loader. */
public final class TestArchiveResources {
    public static java.io.InputStream open(String path) throws java.io.IOException {
        String name = path.startsWith("/") ? path.substring(1) : path;
        var local = TestArchiveResources.class.getResourceAsStream("/" + name);
        if (local != null) return local;
        for (String archive :
                System.getProperty("starfold.testArchives")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            try (var zip = new java.util.zip.ZipFile(archive)) {
                var entry = zip.getEntry(name);
                if (entry != null)
                    try (var in = zip.getInputStream(entry)) {
                        return new java.io.ByteArrayInputStream(in.readAllBytes());
                    }
            }
        // Callers name our classes without their package; find the class file wherever it was
        // compiled to.
        if (name.startsWith("dev/starfold/") && name.endsWith(".class")) {
            String file = name.substring(name.lastIndexOf('/') + 1);
            try (var files =
                    java.nio.file.Files.walk(java.nio.file.Path.of("build/classes/java/main"))) {
                var matches = files.filter(p -> p.getFileName().toString().equals(file)).toList();
                if (matches.size() == 1)
                    return new java.io.ByteArrayInputStream(
                            java.nio.file.Files.readAllBytes(matches.getFirst()));
            }
        }
        return null;
    }
}
