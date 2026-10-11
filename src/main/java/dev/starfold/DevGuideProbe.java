package dev.starfold;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.Vec3;

/** Development driver: ordinary survival digging and container clicks, never creates items or blocks. */
final class DevGuideProbe {
    private static BlockPos digging;private static int left;private static double walkX,walkZ;private static boolean walking;
    private static final java.util.ArrayDeque<Runnable> clicks=new java.util.ArrayDeque<>();private static int delay;
    static boolean tick(Minecraft mc){
        if(mc.player==null)return false;
        if(!clicks.isEmpty()){if(delay-->0)return true;clicks.removeFirst().run();delay=3;return true;}
        if(digging!=null){
            aim(mc,Vec3.atCenterOf(digging));
            if(mc.level.getBlockState(digging).isAir()){log(mc,"mined "+digging);mc.gameMode.stopDestroyBlock();digging=null;return false;}
            if(--left<=0||mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(digging))>mc.player.blockInteractionRange()+.5)throw new IllegalStateException("Mining failed/out of reach "+digging);
            mc.gameMode.continueDestroyBlock(digging,Direction.UP);mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);return true;
        }
        if(walking){double dx=walkX-mc.player.getX(),dz=walkZ-mc.player.getZ();aim(mc,new Vec3(walkX,mc.player.getEyeY(),walkZ));boolean done=Math.hypot(dx,dz)<.15;mc.options.keyUp.setDown(!done);mc.options.keyJump.setDown(!done&&mc.player.horizontalCollision);if(done){walking=false;log(mc,"walked");return false;}if(--left<=0){mc.options.keyUp.setDown(false);mc.options.keyJump.setDown(false);walking=false;log(mc,"WALK FAILED timed out");return false;}return true;}
        return false;
    }
    static void command(Minecraft mc,String arg){
        var a=arg.split("\\s+",2);String value=a.length>1?a[1]:"";
        switch(a[0]){
            case "state"->log(mc,value);
            case "mine"->{var v=value.split(" ");digging=new BlockPos(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2]));left=200;aim(mc,Vec3.atCenterOf(digging));mc.gameMode.startDestroyBlock(digging,Direction.UP);log(mc,"dig-start "+digging+" "+mc.level.getBlockState(digging));}
            case "walk"->{var v=value.split(" ");walkX=Double.parseDouble(v[0]);walkZ=Double.parseDouble(v[1]);left=v.length>2?Integer.parseInt(v[2]):200;walking=true;}
            case "aim"->{var v=value.split(" ");aim(mc,new Vec3(Double.parseDouble(v[0]),Double.parseDouble(v[1]),Double.parseDouble(v[2])));}
            case "select"->{mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));var menu=mc.player.containerMenu;boolean found=false;for(var slot:menu.slots)if(slot.container==mc.player.getInventory()&&id(slot.getItem()).equals(value)){mc.gameMode.handleInventoryMouseClick(menu.containerId,slot.index,0,ClickType.SWAP,mc.player);found=true;break;}mc.player.getInventory().selected=0;mc.player.closeContainer();if(!found)throw new IllegalStateException("No item "+value);}
            case "craft"->{
                if(!(mc.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu))throw new IllegalStateException("Open a crafting table first");
                var grid=value.split(",",-1);if(grid.length!=9)throw new IllegalArgumentException("Nine cells required");
                for(int i=0;i<9;i++)if(!grid[i].isEmpty()){final int target=i+1;final String item=grid[i];clicks.add(()->{var menu=mc.player.containerMenu;int source=-1;for(var slot:menu.slots)if(slot.index>=10&&id(slot.getItem()).equals(item)){source=slot.index;break;}if(source<0)throw new IllegalStateException("Missing ingredient "+item);mc.gameMode.handleInventoryMouseClick(menu.containerId,source,0,ClickType.PICKUP,mc.player);mc.gameMode.handleInventoryMouseClick(menu.containerId,target,1,ClickType.PICKUP,mc.player);mc.gameMode.handleInventoryMouseClick(menu.containerId,source,0,ClickType.PICKUP,mc.player);});}
                clicks.add(()->{var menu=mc.player.containerMenu;log(mc,"craft-result "+menu.getSlot(0).getItem());if(menu.getSlot(0).getItem().isEmpty())throw new IllegalStateException("No crafting result");mc.gameMode.handleInventoryMouseClick(menu.containerId,0,0,ClickType.QUICK_MOVE,mc.player);});clicks.add(()->log(mc,"crafted"));
            }
            case "close"->mc.player.closeContainer();
            case "drop"->{mc.player.drop(false);}
            case "advancements"->mc.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(mc.player.connection.getAdvancements()));
            case "button"->{var v=value.split(" ");try{var method=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(mc.mouseHandler,mc.getWindow().getWindow(),Integer.parseInt(v[0]),Integer.parseInt(v[1]),0);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
            case "hover"->{var v=value.split(" ");try{var x=net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");var y=net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");x.setAccessible(true);y.setAccessible(true);x.setDouble(mc.mouseHandler,Double.parseDouble(v[0])*mc.getWindow().getGuiScale());y.setDouble(mc.mouseHandler,Double.parseDouble(v[1])*mc.getWindow().getGuiScale());mc.screen.mouseMoved(Double.parseDouble(v[0]),Double.parseDouble(v[1]));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
            case "pan"->{var v=value.split(" ");double x=mc.screen.width/2d,y=mc.screen.height/2d;mc.screen.mouseClicked(x,y,0);mc.screen.mouseDragged(x,y,0,Double.parseDouble(v[0]),0);mc.screen.mouseReleased(x,y,0);}
            case "language"->{mc.options.languageCode=value;mc.getLanguageManager().setSelected(value);mc.reloadResourcePacks();}
            default->throw new IllegalArgumentException(arg);
        }
    }
    private static String id(net.minecraft.world.item.ItemStack stack){return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}
    private static void aim(Minecraft mc,Vec3 target){var d=target.subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));mc.gameRenderer.pick(1);}
    private static void log(Minecraft mc,String label){var items=new java.util.TreeMap<String,Integer>();for(var item:mc.player.getInventory().items)if(!item.isEmpty())items.merge(id(item),item.getCount(),Integer::sum);org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE GUIDE {} wall {} mode {} pos {} storage {} inventory {} screen {}",label,System.currentTimeMillis(),mc.gameMode.getPlayerMode(),mc.player.position(),NativeGroundClient.storage(mc.player.getX(),mc.player.getY(),mc.player.getZ()),items,mc.screen==null?"none":mc.screen.getClass().getSimpleName());}
    private DevGuideProbe(){}
}
