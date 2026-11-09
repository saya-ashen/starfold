package dev.starfold.probes.mixin;

import dev.starfold.dev.DevMeshProbe;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MeshSectionProbeMixin implements DevMeshProbe.SectionAccess {
    @Unique private long starfold$generation, starfold$dirtySince, starfold$prepare;
    @Unique private volatile DevMeshProbe.Trace starfold$published;

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void starfold$dirty(boolean player, CallbackInfo ci) {
        if (starfold$dirtySince == 0) starfold$dirtySince = System.nanoTime();
        starfold$generation++;
        DevMeshProbe.dirty(player);
    }
    @Inject(method = "reset", at = @At("RETURN"))
    private void starfold$reset(CallbackInfo ci) {
        starfold$published = null;
        starfold$dirtySince = System.nanoTime();
        starfold$generation++;
    }
    @Inject(method = "createCompileTask", at = @At("HEAD"))
    private void starfold$prepare(RenderRegionCache cache, CallbackInfoReturnable<Object> cir) {
        starfold$prepare = System.nanoTime();
    }
    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void starfold$created(RenderRegionCache cache, CallbackInfoReturnable<Object> cir) {
        var section = (SectionRenderDispatcher.RenderSection) (Object) this;
        var trace = new DevMeshProbe.Trace(section.getOrigin().asLong(), starfold$generation);
        ((DevMeshProbe.TaskAccess) cir.getReturnValue()).starfold$trace(trace);
        DevMeshProbe.created(trace, section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED,
                trace.created - starfold$prepare, starfold$dirtySince);
        starfold$dirtySince = 0;
    }
    @Override public void starfold$published(DevMeshProbe.Trace trace) { starfold$published = trace; }
    @Override public void starfold$drawn() {
        var trace = starfold$published;
        if (trace != null) {
            starfold$published = null;
            DevMeshProbe.drawn(trace);
        }
    }
}
