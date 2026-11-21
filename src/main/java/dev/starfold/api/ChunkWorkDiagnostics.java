package dev.starfold.api;

/** Opt-in planet chunk observations. Callbacks may run on server workers or the render thread. */
public final class ChunkWorkDiagnostics {
    public enum Stage {
        GENERATION_SORTER_QUEUE, GENERATION_WORKER_QUEUE, GENERATION_DISPATCH_LEVEL,
        TICKET_PLAYER, TICKET_NATIVE_VIEW, TICKET_NATIVE_CHART, TICKET_SPACE_VIEW, TICKET_OTHER,
        EMPTY_READ, EMPTY_MAIN_QUEUE, EMPTY_MAIN_WORK,
        GENERATION_LAYER_START, GENERATION_LAYER_LAST_DEPENDENCY, GENERATION_LAYER_RESUME,
        FULL_REQUEST, FULL_DEPENDENCIES_READY, FULL_DIRECT_SUBMITTED, FULL_CALLBACK, FULL_READY,
        FILL_QUEUED, FILL, COLUMN_EVALUATION, SECTION_WRITE, BASE_HEIGHT, FULL_CONVERSION_SUBMITTED, FULL_CONVERSION_DISPATCHED, FULL_CONVERSION,
        SABLE_COLLISION_CHUNK,
        SERVER_NEW_LOAD, SERVER_LOAD, SEND_QUEUED, SENT, IMAGE_FIRST_ATTEMPT, IMAGE_SENT, CLIENT_LOAD, CLIENT_LIGHT_APPLIED, CLIENT_SECTION_VISIBLE, CLIENT_MESH_SCHEDULED, CLIENT_MESH_COMPILED, FIRST_DRAW
    }

    @FunctionalInterface
    public interface Listener {
        void record(Stage stage, long chunk, long atNanos, long durationNanos);

        /** A generation layer marker, with the chunk status index and optional last dependency. */
        default void generationLayer(Stage stage, long center, int status, Long dependency, long atNanos) {}

        /** The image stream selected a candidate and is about to resolve its owners. */
        default void imageAttempt(java.util.UUID player, long image, long atNanos) {}

        /** An image was sent in client coordinates. The source is present for identity images. */
        default void imageSent(java.util.UUID player, long image, Long source, long atNanos, long durationNanos) {}

        /** Reports native section submission in client chart coordinates. */
        default void drawn(long chunk, int sectionY) {}

        /** A section has entered the native renderer's visible list. */
        default void sectionVisible(long chunk, int sectionY, long atNanos) {}

        /** The rebuild task that produced a nonempty mesh was created for this section. */
        default void meshScheduled(long chunk, int sectionY, long atNanos) {}

        /** A native section with renderable geometry finished CPU compilation. */
        default void meshCompiled(long chunk, int sectionY, long atNanos, long durationNanos) {}
    }

    private static volatile Listener listener;

    public static Listener listener() { return listener; }

    public static void register(Listener next) { listener = next; }

    private ChunkWorkDiagnostics() {}
}
