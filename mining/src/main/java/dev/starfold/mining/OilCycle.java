package dev.starfold.mining;

/** Pure accounting policy, independent of Minecraft and of optional CDG classes. */
final class OilCycle {
    static final int ENERGY_CAPACITY = 20000, TANK_CAPACITY = 16000;
    static final int SURVEY_TICKS = 100, SURVEY_ENERGY = 10, WELL_ENERGY = 80;
    // A size-2048 home has its core near Y=-525; a surface well exceeds the solid miner's
    // 512-layer survey. Cover the native dimension's full 848-block height, still bounded.
    static final int PERIOD = 20, BATCH = 100, ENERGY_PER_MB = 2, MAX_DEPTH = 1024;

    interface Reservoir {
        int read();

        void write(int amount);
    }

    interface Receiver {
        int simulate(int requested);

        int receive(int offered);

        void rollback();
    }

    /** Server-thread transaction: receiver is private, has no external callbacks, and supports rollback.
     * A failed CDG write never leaves oil in the tank. Zero is written, never removed/re-generated.
     * The live reservoir is read each time: saved survey results cannot authorize extraction.
     */
    static int transfer(Reservoir reservoir, Receiver receiver, int energy) {
        int available = reservoir.read();
        int requested =
                Math.min(
                        BATCH,
                        Math.min(Math.max(0, available), Math.max(0, energy) / ENERGY_PER_MB));
        if (requested == 0) return 0;
        int planned = Math.clamp(receiver.simulate(requested), 0, requested);
        if (planned == 0) return 0;
        try {
            int accepted = receiver.receive(planned);
            if (accepted < 0 || accepted > planned)
                throw new IllegalStateException("Invalid oil receiver accounting");
            if (accepted > 0 && available != Integer.MAX_VALUE)
                reservoir.write(available - accepted);
            return accepted;
        } catch (RuntimeException | LinkageError failure) {
            receiver.rollback();
            throw failure;
        }
    }

    /** Scalar save-state invariant. Work progress is paid once; tank is persisted separately as NBT. */
    record Progress(
            int energy,
            int burnTicks,
            int survey,
            int depth,
            int clock,
            int mode,
            int status,
            int lastKnown,
            boolean surveyed,
            boolean well) {
        Progress {
            energy = Math.clamp(energy, 0, ENERGY_CAPACITY);
            burnTicks = Math.clamp(burnTicks, 0, 1000000);
            survey = Math.clamp(survey, 0, SURVEY_TICKS);
            depth = Math.clamp(depth, 0, MAX_DEPTH);
            clock = Math.clamp(clock, 0, PERIOD - 1);
            mode = Math.clamp(mode, 0, 3);
            status = Math.clamp(status, 0, OilStatus.values().length - 1);
            lastKnown = Math.max(0, lastKnown);
            surveyed = surveyed && survey == SURVEY_TICKS;
            well = well && surveyed && depth > 0;
            if (!surveyed && mode > 1) mode = 0;
            if (!well && mode == 3) mode = 0;
        }

        void save(
                java.util.function.BiConsumer<String, Integer> ints,
                java.util.function.BiConsumer<String, Boolean> flags) {
            ints.accept("Energy", energy);
            ints.accept("BurnTicks", burnTicks);
            ints.accept("Survey", survey);
            ints.accept("Depth", depth);
            ints.accept("Clock", clock);
            ints.accept("Mode", mode);
            ints.accept("Status", status);
            ints.accept("LastKnown", lastKnown);
            flags.accept("Surveyed", surveyed);
            flags.accept("Well", well);
        }

        static Progress load(
                java.util.function.ToIntFunction<String> ints,
                java.util.function.Predicate<String> flags) {
            return new Progress(
                    ints.applyAsInt("Energy"),
                    ints.applyAsInt("BurnTicks"),
                    ints.applyAsInt("Survey"),
                    ints.applyAsInt("Depth"),
                    ints.applyAsInt("Clock"),
                    ints.applyAsInt("Mode"),
                    ints.applyAsInt("Status"),
                    ints.applyAsInt("LastKnown"),
                    flags.test("Surveyed"),
                    flags.test("Well"));
        }
    }
}
