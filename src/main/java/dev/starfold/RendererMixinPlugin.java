package dev.starfold;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Runs before ModList/client classes exist. Keep all vanilla injection requirements intact. */
public final class RendererMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String targetClassName,String mixinClassName){
        // Do not wrap hot chunk lookups or frames at all in ordinary play.
        if(mixinClassName.startsWith("dev.starfold.mixin.NativeArrival")&&!Boolean.getBoolean("starfold.carryProbe")&&!(mixinClassName.endsWith("NativeArrivalTickMixin")&&Boolean.getBoolean("starfold.relightProbe")))return false;
        return RendererCompatibility.apply(mixinClassName,id->LoadingModList.get().getModFileById(id)!=null);
    }
    @Override public void acceptTargets(Set<String> myTargets,Set<String> otherTargets){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
    @Override public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
}
