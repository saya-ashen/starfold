package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import io.netty.buffer.Unpooled;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundGuidanceTest {
    final GroundEphemeris.Model model=GroundEphemeris.spinningDefaults();
    void close(Point a,Point b){assertTrue(a.sub(b).length()<1e-6,a+" != "+b);}
    void same(Quaterniond a,Quaterniond b){assertEquals(1,Math.abs(a.dot(b)),1e-9);}
    @Test void radialLimiterPreservesTangentialAndAscendingMotion(){var v=new Point(4,-20,7);var capped=GroundGuidance.limitVelocity(v,GroundEphemeris.Y,10);assertEquals(4,capped.x());assertEquals(7,capped.z());assertEquals(-GroundGuidance.descentLimit(10),capped.y(),1e-9);assertEquals(new Point(4,20,7),GroundGuidance.limitVelocity(new Point(4,20,7),GroundEphemeris.Y,10));assertEquals(v,GroundGuidance.limitVelocity(v,GroundEphemeris.Y,3000));}
    @Test void landingLimitIsMonotoneAndContinuousAtLowAltitudeBoundaries(){double previous=0;for(int h=0;h<2048;h++){double cap=GroundGuidance.descentLimit(h);assertTrue(cap>=previous&&cap<=12);previous=cap;}assertEquals(.5,GroundGuidance.descentLimit(50),1e-9);assertEquals(GroundGuidance.descentLimit(50-.001),GroundGuidance.descentLimit(50+.001),1e-7);assertEquals(12,GroundGuidance.descentLimit(512),1e-9);}
}
