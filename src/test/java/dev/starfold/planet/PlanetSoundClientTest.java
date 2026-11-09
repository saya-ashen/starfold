package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.Test;

class PlanetSoundClientTest {
    private static SimpleSoundInstance sound(SoundSource source) {
        return new SimpleSoundInstance(
                SoundEvent.createVariableRangeEvent(ResourceLocation.parse("starfold:test")),
                source,
                4,
                1,
                RandomSource.create(7),
                10,
                20,
                30) {
            @Override
            public float getVolume() {
                return volume;
            }
        };
    }

    private static final class BoundSound extends AbstractTickableSoundInstance
            implements PlanetSoundOwner {
        private final Object owner;

        BoundSound(Object owner) {
            super(
                    SoundEvent.createVariableRangeEvent(ResourceLocation.parse("starfold:test")),
                    SoundSource.PLAYERS,
                    RandomSource.create(7));
            this.owner = owner;
            volume = 4;
            x = 10;
            y = 20;
            z = 30;
        }

        @Override
        public Object starfold$soundOwner() {
            return owner;
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public void tick() {
            volume = volume == 4 ? 2 : 4;
        }
    }

    @Test
    void identicalCoordinatesCategoryAndNameDoNotConferLocalOwnership() {
        Object local = new Object();
        var own = sound(SoundSource.PLAYERS);
        var other = sound(SoundSource.PLAYERS);
        PlanetSoundClient.withOwner(local, () -> PlanetSoundClient.created(own));
        PlanetSoundClient.created(other);
        assertEquals(own.getLocation(), other.getLocation());
        assertEquals(own.getSource(), other.getSource());
        assertEquals(own.getX(), other.getX());
        assertEquals(own.getY(), other.getY());
        assertEquals(own.getZ(), other.getZ());
        assertTrue(PlanetSoundClient.self(own, local));
        assertFalse(PlanetSoundClient.self(other, local));
        assertEquals(.45f, PlanetSoundClient.scale(own, local, 0));
        assertEquals(.12f, PlanetSoundClient.scale(other, local, 0));
        assertFalse(PlanetSoundClient.self(own, new Object()));
        assertFalse(PlanetSoundClient.self(own, null));
    }

    @Test
    void entityBoundAndTickableOwnershipUsesReferenceIdentityNotCategory() {
        Object local = new Object(), remote = new Object();
        SoundInstance own = new BoundSound(local), other = new BoundSound(remote);
        assertTrue(PlanetSoundClient.self(own, local));
        assertFalse(PlanetSoundClient.self(other, local));
        assertEquals(.45f, PlanetSoundClient.scale(own, local, 0));
        assertEquals(.12f, PlanetSoundClient.scale(other, local, 0));
    }

    @Test
    void tickableVolumeRemainsRawAsItsOwnerAndDensityAreReevaluated() {
        Object local = new Object();
        var own = new BoundSound(local);
        var other = new BoundSound(new Object());
        for (double density : new double[] {0, .5, 1, 0}) {
            own.tick();
            other.tick();
            for (var sound : new BoundSound[] {own, other}) {
                float raw = sound.getVolume();
                for (int refresh = 0; refresh < 3; refresh++) {
                    assertEquals(
                            Math.clamp(
                                    raw
                                            * .5f
                                            * (float)
                                                    PlanetConditions.soundScale(
                                                            density, sound == own),
                                    0,
                                    1),
                            Math.clamp(
                                    sound.getVolume()
                                            * .5f
                                            * PlanetSoundClient.scale(sound, local, density),
                                    0,
                                    1),
                            1e-6);
                    assertEquals(raw, sound.getVolume());
                }
            }
        }
    }

    @Test
    void emitterScopeRestoresAfterNestedCallsAndExceptions() {
        Object local = new Object(), remote = new Object();
        var own = sound(SoundSource.PLAYERS);
        var other = sound(SoundSource.PLAYERS);
        PlanetSoundClient.withOwner(
                local,
                () -> {
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    PlanetSoundClient.withOwner(
                                            remote,
                                            () -> {
                                                PlanetSoundClient.created(other);
                                                throw new IllegalStateException();
                                            }));
                    PlanetSoundClient.created(own);
                });
        assertTrue(PlanetSoundClient.self(own, local));
        assertFalse(PlanetSoundClient.self(other, local));
        assertTrue(PlanetSoundClient.self(other, remote));
        var anonymous = PlanetSoundClient.created(sound(SoundSource.PLAYERS));
        assertFalse(PlanetSoundClient.self(anonymous, local));
        assertFalse(PlanetSoundClient.self(anonymous, remote));
    }

    @Test
    void constructionConsumesProvenanceBeforeReentrantSoundCallbacks() {
        Object local = new Object();
        var own = sound(SoundSource.PLAYERS);
        var callbackSound = sound(SoundSource.PLAYERS);
        PlanetSoundClient.withOwner(
                local,
                () -> {
                    PlanetSoundClient.created(own);
                    PlanetSoundClient.created(callbackSound);
                });
        assertTrue(PlanetSoundClient.self(own, local));
        assertFalse(PlanetSoundClient.self(callbackSound, local));
    }

    @Test
    void exemptCategoriesRemainUnchangedEvenWhenExplicitlyLocal() {
        Object local = new Object();
        for (var source : SoundSource.values()) {
            var sound = sound(source);
            PlanetSoundClient.withOwner(local, () -> PlanetSoundClient.created(sound));
            assertEquals(
                    PlanetSoundClient.world(source) ? .45f : 1f,
                    PlanetSoundClient.scale(sound, local, 0),
                    source.name());
            var world = sound(source);
            assertEquals(
                    PlanetSoundClient.world(source) ? .12f : 1f,
                    PlanetSoundClient.scale(world, local, 0),
                    source.name());
        }
    }

    @Test
    void repeatedPlayTickAndRefreshCalculationsUseUnmodifiedVolumeBeforeClamping() {
        Object local = new Object();
        var own = sound(SoundSource.PLAYERS);
        var world = sound(SoundSource.BLOCKS);
        PlanetSoundClient.withOwner(local, () -> PlanetSoundClient.created(own));
        for (double density : new double[] {1, 0, .5, 0, 1}) {
            for (int pass = 0; pass < 3; pass++) {
                for (var sound : new SoundInstance[] {own, world}) {
                    float scale = PlanetSoundClient.scale(sound, local, density);
                    float result = Math.clamp(sound.getVolume() * .5f * scale, 0, 1);
                    float expected =
                            (float)
                                    Math.min(
                                            1,
                                            2 * PlanetConditions.soundScale(density, sound == own));
                    assertEquals(expected, result, 1e-6);
                    assertEquals(4f, sound.getVolume());
                }
            }
        }
        assertEquals(
                .24f,
                Math.clamp(
                        world.getVolume() * .5f * PlanetSoundClient.scale(world, local, 0), 0, 1),
                1e-6);
    }
}
