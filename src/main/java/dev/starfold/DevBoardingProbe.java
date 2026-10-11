package dev.starfold;

/** Opt-in missing-hull fault injection, restricted to the disposable development world. */
final class DevBoardingProbe {
    static void initialize(){
        DevHelmProbe.initialize();
        if(!Boolean.getBoolean("starfold.rejoinRemoveHull"))return;
        var removed=new java.util.HashSet<java.util.UUID>();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre event)->{
            if(!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)||!NativeGroundServer.is(player.level()))return;
            if(!player.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString().equals("devworld-create1"))throw new IllegalStateException("Missing-hull probe requires devworld-create1");
            var tag=player.getPersistentData().getCompound(NativeSableBoarding.KEY);if(!NativeSableBoarding.valid(tag)||removed.contains(tag.getUUID("body")))return;
            var container=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.serverLevel());var body=container==null?null:container.getSubLevel(tag.getUUID("body"));if(body==null)return;
            body.markRemoved();removed.add(body.getUniqueId());org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE BOARD removed-before-restore tick {} body {}",player.level().getGameTime(),body.getUniqueId());
        });
    }
    private DevBoardingProbe(){}
}
