package dev.starfold.probes;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class ProbeMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        String name = mixin.substring(mixin.lastIndexOf('.') + 1);
        if (name.startsWith("Mesh")) return System.getProperty("starfold.probe") != null;
        if (name.startsWith("Arrival"))
            return Boolean.getBoolean("starfold.carryProbe")
                    || name.equals("ArrivalTickMixin") && Boolean.getBoolean("starfold.relightProbe");
        if (name.startsWith("DevPort")) return Boolean.getBoolean("starfold.portProbe");
        if (name.equals("PilotPoseProbeMixin")) return LoadingModList.get().getModFileById("sable") != null;
        return true;
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
