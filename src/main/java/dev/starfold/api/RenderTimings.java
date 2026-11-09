package dev.starfold.api;

/** Optional render-thread observer. Sections may nest, but a section must not reenter itself. */
public final class RenderTimings {
    public enum Section { MESH, FAR, OWNERSHIP, NATIVE, SKY, REMOTE, REMOTE_WORK, GAS }
    public interface Listener {
        void begin(Section section);
        void end(Section section);
    }
    private static volatile Listener listener;
    public static void register(Listener value) { listener = value; }
    public static void begin(Section section) {
        Listener current = listener;
        if (current != null) current.begin(section);
    }
    public static void end(Section section) {
        Listener current = listener;
        if (current != null) current.end(section);
    }
    private RenderTimings() {}
}
