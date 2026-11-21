// Transfers the existing Stage 2 output between real Create tank block entities.
ServerEvents.commandRegistry(event => {
  var Commands = Java.loadClass('net.minecraft.commands.Commands');
  var Pos = Java.loadClass('net.minecraft.core.BlockPos');
  var IntegerArgument = Java.loadClass('com.mojang.brigadier.arguments.IntegerArgumentType');
  var Dir = Java.loadClass('net.minecraft.core.Direction');
  var Fluid = Java.loadClass('net.neoforged.neoforge.capabilities.Capabilities$FluidHandler');
  var Action = Java.loadClass('net.neoforged.neoforge.fluids.capability.IFluidHandler$FluidAction');
  var Registry = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
  var root = Commands.literal('loopfuel').requires(source => source.hasPermission(2));
  [[22038, 147, 1554, 22060, 157, 1553, 'diesel'],
   [22038, 148, 1556, 22060, 157, 1555, 'gasoline']].forEach(spec => {
    root.then(Commands.literal(spec[6]).executes(context => {
      var level = context.getSource().getLevel();
      var from = level.getCapability(Fluid.BLOCK, new Pos(spec[0], spec[1], spec[2]), Dir.UP);
      var to = level.getCapability(Fluid.BLOCK, new Pos(spec[3], spec[4], spec[5]), Dir.UP);
      if (from == null || to == null) throw new Error('missing source or hull tank');
      var transfer = from['drain(int,net.neoforged.neoforge.fluids.capability.IFluidHandler$FluidAction)'](1000, Action.SIMULATE);
      var id = String(Registry.FLUID.getKey(transfer.getFluid()));
      if (id !== 'createdieselgenerators:' + spec[6] || Number(transfer.getAmount()) !== 1000 ||
          Number(to.fill(transfer, Action.SIMULATE)) !== 1000) throw new Error('unexpected tank contents or capacity: ' + transfer);
      var drained = from['drain(net.neoforged.neoforge.fluids.FluidStack,net.neoforged.neoforge.fluids.capability.IFluidHandler$FluidAction)'](transfer, Action.EXECUTE);
      var filled = Number(to.fill(drained, Action.EXECUTE));
      if (filled !== 1000 || !from.getFluidInTank(0).isEmpty()) throw new Error('fuel transfer did not reconcile');
      console.info('SURVIVAL_LOOP_FUEL ' + id + ' moved=' + filled + ' sourceRemaining=0');
      return filled;
    }));
  });
  event.register(root);

  // Supply the platform and coal as fixtures. Ore must come from the platform's real output.
  var groundHeight = Java.loadClass('net.minecraft.world.level.levelgen.Heightmap$Types').MOTION_BLOCKING_NO_LEAVES;
  var miningPos = null;
  var itemCapability = Java.loadClass('net.neoforged.neoforge.capabilities.Capabilities$ItemHandler').BLOCK;
  var ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
  var Items = Java.loadClass('net.minecraft.world.item.Items');
  var mine = Commands.literal('loopmine').requires(source => source.hasPermission(2));
  ['setup', 'start', 'collect'].forEach(action => mine.then(Commands.literal(action).executes(context => {
    var source = context.getSource();
    var level = source.getLevel();
    var player = source.getPlayerOrException();
    if (action === 'setup') {
      if (!level.hasChunkAt(new Pos(65688, 152, 16561))) throw new Error('platform chunk unavailable');
      miningPos = new Pos(65688, level.getHeight(groundHeight, 65688, 16561), 16561);
    }
    if (miningPos == null || player.blockPosition().distSqr(miningPos) > 100 || !level.hasChunkAt(miningPos))
      throw new Error('player out of range of Fleck platform');
    if (action === 'setup') {
      if (!level.getBlockState(miningPos).isAir()) throw new Error('platform site occupied');
      var platformBlock = Registry.BLOCK.get(Java.loadClass('net.minecraft.resources.ResourceLocation').parse('starfold_mining:mining_platform'));
      if (!level.setBlock(miningPos, platformBlock.defaultBlockState(), 3)) throw new Error('platform placement failed');
      var placed = level.getBlockEntity(miningPos);
      placed.placedBy(player.uuid);
      var fuel = level.getCapability(itemCapability, miningPos, null);
      if (!fuel.insertItem(0, new ItemStack(Items.COAL, 24), false).isEmpty()) throw new Error('coal insertion failed');
      placed.command(3);
      placed.command(0);
      if (!placed.enabled()) throw new Error('platform scan rejected');
      console.info('SURVIVAL_LOOP_MINING_SETUP ' + miningPos);
      return 1;
    }
    var platform = level.getBlockEntity(miningPos);
    if (platform == null || !player.uuid.equals(platform.owner())) throw new Error('platform owner mismatch');
    if (action === 'start') {
      if (!platform.scanned() || platform.enabled()) throw new Error('platform survey incomplete');
      console.info('SURVIVAL_LOOP_MINING_SURVEY ' + platform.summary());
      platform.command(1);
      return 1;
    }
    var output = level.getCapability(itemCapability, miningPos, null);
    var ore = 0;
    var waste = 0;
    for (var slot = 1; slot < output.getSlots(); slot++) {
      var stack = output.getStackInSlot(slot);
      if (stack.isEmpty()) continue;
      var name = String(Registry.ITEM.getKey(stack.getItem()));
      if (name === 'mekanism:raw_osmium') {
        ore += stack.getCount();
      } else {
        waste += output.extractItem(slot, stack.getCount(), false).getCount();
      }
    }
    if (ore >= 8) {
      if (platform.enabled()) platform.command(1);
      var remaining = 8;
      for (var slot = 1; slot < output.getSlots() && remaining > 0; slot++) {
        var stack = output.getStackInSlot(slot);
        if (String(Registry.ITEM.getKey(stack.getItem())) !== 'mekanism:raw_osmium') continue;
        var cargo = output.extractItem(slot, remaining, false);
        remaining -= cargo.getCount();
        if (!player.inventory.add(cargo)) throw new Error('no player inventory room for platform output');
      }
      if (remaining !== 0) throw new Error('platform extraction mismatch');
      console.info('SURVIVAL_LOOP_MINING_COLLECTED rawOsmium=8 cursor=' + platform.cursor());
    }
    console.info('SURVIVAL_LOOP_MINING_PROGRESS ore=' + ore + ' waste=' + waste + ' cursor=' + platform.cursor() + ' status=' + platform.status());
    return Math.min(ore, 8);
  })));
  event.register(mine);

  event.register(Commands.literal('loopworkshop').requires(source => source.hasPermission(2)).executes(context => {
    var source = context.getSource();
    var player = source.getPlayerOrException();
    var level = player.serverLevel();
    var feet = player.blockPosition();
    var neighbors = [feet.north(), feet.east(), feet.south(), feet.west()];
    var sites = neighbors.map(at => {
      if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Dir.UP)) return at;
      if (level.getBlockState(at.above()).isAir()) return at.above();
      return null;
    }).filter(at => at != null);
    if (sites.length < 2) throw new Error('workshop requires two reachable spots beside the player');
    var Id = Java.loadClass('net.minecraft.resources.ResourceLocation');
    var wall = Registry.BLOCK.get(Id.parse('minecraft:cobblestone')).defaultBlockState();
    // Enclose the small fixture so mobs cannot walk in while a machine screen is open.
    for (var dx = -2; dx <= 2; dx++) {
      for (var dz = -2; dz <= 2; dz++) {
        var edge = Math.max(Math.abs(dx), Math.abs(dz)) === 2;
        for (var dy = edge ? -1 : 3; dy <= 3; dy++) {
          var at = feet.offset(dx, dy, dz);
          if (level.getBlockState(at).isAir()) level.setBlockAndUpdate(at, wall);
        }
      }
    }
    var furnace = Registry.BLOCK.get(Id.parse('minecraft:furnace')).defaultBlockState();
    var infuserBlock = Registry.BLOCK.get(Id.parse('mekanism:metallurgic_infuser')).defaultBlockState();
    level.setBlockAndUpdate(sites[0], furnace);
    level.setBlockAndUpdate(sites[1], infuserBlock);
    var infuser = level.getBlockEntity(sites[1]);
    if (level.getBlockEntity(sites[0]) == null || infuser == null) throw new Error('workshop fixture placement failed');
    var energy = infuser.getEnergyContainer();
    energy.setEnergy(energy.getMaxEnergy());
    console.info('SURVIVAL_LOOP_INFUSER_POWER ' + energy.getEnergy());
    return 1;
  }));

  var Sable = Java.loadClass('dev.starfold.ship.SableFrames');
  var Landing = Java.loadClass('dev.starfold.ship.HullLanding');
  var Access = Java.loadClass('dev.starfold.planet.PlanetAccess');
  var Storage = Java.loadClass('dev.starfold.planet.PlanetStorage');
  var Shape = Java.loadClass('dev.starfold.chart.PlanetShape');
  var Anomaly = Java.loadClass('dev.starfold.planet.Anomaly');
  var Point = Java.loadClass('dev.starfold.geometry.BentStripGeometry$Point');
  event.register(Commands.literal('loopfold').requires(source => source.hasPermission(2)).executes(context => {
    var player = context.getSource().getPlayerOrException();
    var hull = Sable.aboard(player);
    if (hull == null) return 0;
    var pos = hull.logicalPose().position();
    var planet = Storage.find(pos.x(), pos.z());
    if (planet !== 8 && planet !== 0) return 0;
    var access = Access.of(player.serverLevel());
    var chart = access.body(new Point(pos.x(), pos.y(), pos.z()));
    var shape = Shape.of(planet);
    var bounds = Landing.bounds(hull);
    var distance = Infinity;
    for (var x of [bounds.minX(), pos.x(), bounds.maxX()]) {
      for (var z of [bounds.minZ(), pos.z(), bounds.maxZ()]) {
        if (Storage.find(x, z) !== planet) return 0;
        var corner = access.body(new Point(x, pos.y(), z));
        if (corner.face() !== chart.face()) return 0;
        var uv = corner.point();
        distance = Math.min(distance, Anomaly.distance(shape.direction(chart.face(), uv.x(), uv.z()), shape));
      }
    }
    distance = Math.floor(distance);
    console.info('SURVIVAL_LOOP_FOLD planet=' + planet + ' footprintSampleMin=' + distance + ' x=' + pos.x() + ' z=' + pos.z());
    return distance;
  }));
  event.register(Commands.literal('loopsite').requires(source => source.hasPermission(2)).executes(context => {
    var player = context.getSource().getPlayerOrException();
    var level = player.serverLevel();
    var ship = Sable.aboard(player);
    if (ship == null) throw new Error('player is not on the loop hull');
    var pos = ship.logicalPose().position();
    var planet = Storage.find(pos.x(), pos.z());
    if (planet !== 8 && planet !== 0) throw new Error('Fleck or Hearth ground chart required');
    var access = Access.of(level);
    var shape = Shape.of(planet);
    var current = access.body(new Point(pos.x(), pos.y(), pos.z()));
    var originX = Math.floor(pos.x());
    var originZ = Math.floor(pos.z());
    console.info('SURVIVAL_LOOP_SITE start=' + pos + ' planet=' + planet + ' face=' + current.face());
    function survey(x, z, stride) {
      var min = Infinity;
      var max = -Infinity;
      for (var dx = -6; dx <= 6; dx += stride) {
        for (var dz = -6; dz <= 6; dz += stride) {
          var y = Landing.surface(level, x + dx, z + dz);
          if (!Number.isFinite(y) || !level.getFluidState(new Pos(x + dx, Math.floor(y - 0.01), z + dz)).isEmpty()) return null;
          min = Math.min(min, y);
          max = Math.max(max, y);
          if (max - min > 2) return null;
        }
      }
      return [min, max];
    }
    var present = current.point();
    var presentFold = Anomaly.distance(shape.direction(current.face(), present.x(), present.z()), shape);
    var presentHeights = survey(originX, originZ, 1);
    console.info('SURVIVAL_LOOP_SITE below=' + originX + ',' + originZ + ' fold=' + presentFold + ' dryFootprint=' + presentHeights);
    for (var radius = 0; radius <= 96; radius += 8) {
      for (var dx = -radius; dx <= radius; dx += 8) {
        for (var dz = -radius; dz <= radius; dz += 8) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) !== radius) continue;
          var x = originX + dx;
          var z = originZ + dz;
          if (Storage.find(x, z) !== planet) continue;
          var pose = access.body(new Point(x, pos.y(), z));
          if (pose.face() !== current.face()) continue;
          var uv = pose.point();
          if (Math.min(uv.x(), uv.z(), shape.size() - uv.x(), shape.size() - uv.z()) < 112) continue;
          var fold = Anomaly.distance(shape.direction(pose.face(), uv.x(), uv.z()), shape);
          if (fold < Anomaly.ZONE + 48 || survey(x, z, 4) == null) continue;
          var heights = survey(x, z, 1);
          if (heights == null) continue;
          console.info('SURVIVAL_LOOP_SITE found=' + x + ',' + heights[1] + ',' + z + ' minY=' + heights[0] + ' fold=' + fold + ' footprint=13x13');
          return 1;
        }
      }
    }
    console.info('SURVIVAL_LOOP_SITE no loaded broad dry site within 96 blocks');
    return 0;
  }));
});
