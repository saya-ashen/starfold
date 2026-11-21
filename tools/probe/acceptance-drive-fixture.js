// TEST INSTANCE ONLY. No recipes or production data are registered here.
// In-game OP player, in starfold:native_planet near 5350 150 900:
// /kpfixtures setupcraft ; wait >= 1 second ; /kpfixtures startcraft
// /kpfixtures readcraft after ~10 seconds (repeat read if still processing).
// /kpfixtures setupspout ; wait ~3 seconds ; /kpfixtures readspout
// Each setup and input supply is ONCE per world, persisted across /reload.
// Evidence is JSON on server/KubeJS console lines prefixed KP_MACHINES.
// Nothing here supplies a drive or filled bucket output. Do not pick up outputs
// or add items to the fixtures until read commands have recorded acceptance.
// forceload remains in effect for inspection; remove the SAME region manually
// afterwards: /execute in starfold:native_planet run forceload remove 5348 898 5362 904
// Create 6.0.10: generatedSpeed is the Java field; its actual NBT is ScrollValue.
(() => {
  var Pos = Java.loadClass('net.minecraft.core.BlockPos');
  var Direction = Java.loadClass('net.minecraft.core.Direction');
  var RL = Java.loadClass('net.minecraft.resources.ResourceLocation');
  var Registries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
  var ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
  var FluidStack = Java.loadClass('net.neoforged.neoforge.fluids.FluidStack');
  var FluidAction = Java.loadClass('net.neoforged.neoforge.fluids.capability.IFluidHandler$FluidAction');
  var ItemsCap = Java.loadClass('net.neoforged.neoforge.capabilities.Capabilities$ItemHandler');
  var FluidsCap = Java.loadClass('net.neoforged.neoforge.capabilities.Capabilities$FluidHandler');
  var Crafter = Java.loadClass('com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity');
  var CrafterBlock = Java.loadClass('com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock');
  var Grid = Java.loadClass('com.simibubi.create.content.kinetics.crafter.RecipeGridHandler');
  var MechanicalRecipe = Java.loadClass('com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe');
  var Motor = Java.loadClass('com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity');
  var ItemEntity = Java.loadClass('net.minecraft.world.entity.item.ItemEntity');
  var AABB = Java.loadClass('net.minecraft.world.phys.AABB');
  var Component = Java.loadClass('net.minecraft.network.chat.Component');
  var dimension = 'starfold:native_planet';
  var recipeId = 'escape_velocity:interstellar_drive';
  var prefix = 'kp_machines_20261112_';
  var pattern = ['HCH', 'CDC', 'HCH', ' G '];
  var ingredients = {
    H: 'starfold_materials:osmium_pressure_hull',
    C: 'mekanism:elite_control_circuit',
    D: 'mekanismgenerators:deuterium_bucket',
    G: 'starfold_biotech:host_ganglion'
  };
  var cells = [];
  for (var row = 0; row < 4; row++) {
    for (var col = 0; col < 3; col++) {
      if (pattern[row][col] !== ' ') cells.push({
        p: [5350 + col, 154 - row, 900], row: row, col: col,
        item: ingredients[pattern[row][col]],
        target: col === 0 ? Direction.EAST : col === 2 ? Direction.WEST : Direction.DOWN
      });
    }
  }
  var motorPos = [5350, 155, 899];
  var chestPos = [5351, 150, 900];
  var depotPos = [5358, 150, 900];
  var spoutPos = [5358, 152, 900];
  function requireThat(ok, message) { if (!ok) throw new Error(message); }
  function pos(p) { return new Pos(p[0], p[1], p[2]); }
  function block(level, p) { return level.getBlock(p[0], p[1], p[2]); }
  function be(level, p) {
    var value = level.getBlockEntity(pos(p));
    requireThat(value !== null, 'Missing block entity at ' + p.join(','));
    return value;
  }
  function stack(id) {
    var key = RL.parse(id);
    requireThat(Registries.ITEM.containsKey(key), 'Unregistered input ' + id);
    return new ItemStack(Registries.ITEM.get(key), 1);
  }
  function itemInfo(s) {
    return {id: String(Registries.ITEM.getKey(s.getItem())), count: s.getCount(), components: String(s.getComponentsPatch())};
  }
  function itemCap(level, p) { return level.getCapability(ItemsCap.BLOCK, pos(p), Direction.UP); }
  function fluidCap(level, p) { return level.getCapability(FluidsCap.BLOCK, pos(p), Direction.UP); }
  function emit(action, payload) {
    console.info('KP_MACHINES ' + JSON.stringify({action: action, evidence: payload}));
  }
  function guard(source) {
    var player = source.getPlayerOrException();
    var level = source.getLevel();
    requireThat(String(level.dimension) === dimension, 'Use native home dimension ' + dimension);
    requireThat(player.distanceToSqr(5354, 151, 902) <= 64 * 64, 'Player must be within 64 blocks of fixture');
    return level;
  }
  function forceLoad(server) {
    server.runCommandSilent('execute in ' + dimension + ' run forceload add 5348 898 5362 904');
  }
  function emptyVolume(level, x0, x1, y0, y1, z0, z1) {
    for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) for (var z = z0; z <= z1; z++) {
      requireThat(level.getBlockState(new Pos(x, y, z)).isAir(), 'Fixture space is occupied at ' + [x, y, z].join(','));
    }
    var existing = level.getEntitiesOfClass(ItemEntity, new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1));
    requireThat(existing.isEmpty(), 'Remove existing dropped items from fixture space first');
  }
  function place(level, p, id, properties) { block(level, p).set(id, properties || {}); }
  function pointingFor(target) {
    // pointingFromFacing takes a placement face, not an output direction.
    // Resolve the four public states using the actual target-direction API.
    var base = Registries.BLOCK.get(RL.parse('create:mechanical_crafter')).defaultBlockState()
      .setValue(CrafterBlock.HORIZONTAL_FACING, Direction.SOUTH);
    var choices = CrafterBlock.POINTING.getPossibleValues().iterator();
    while (choices.hasNext()) {
      var pointing = choices.next();
      if (CrafterBlock.getTargetDirection(base.setValue(CrafterBlock.POINTING, pointing)).equals(target)) {
        return String(pointing.getSerializedName());
      }
    }
    throw new Error('No crafter pointing for ' + String(target));
  }
  function validateRecipe(server, level) {
    var found = server.getRecipeManager().byKey(RL.parse(recipeId));
    requireThat(found.isPresent(), 'Missing runtime recipe ' + recipeId);
    var recipe = found.get().value();
    requireThat(recipe instanceof MechanicalRecipe, 'Runtime drive recipe is not mechanical crafting');
    requireThat(recipe.getWidth() === 3 && recipe.getHeight() === 4 && !recipe.acceptsMirrored(), 'Runtime recipe shape/mirroring changed');
    var input = recipe.getIngredients();
    requireThat(input.size() === 12, 'Expected 12-cell runtime pattern');
    for (var row = 0; row < 4; row++) for (var col = 0; col < 3; col++) {
      var symbol = pattern[row][col];
      var expected = symbol === ' ' ? ItemStack.EMPTY : stack(ingredients[symbol]);
      requireThat(input.get(row * 3 + col).test(expected), 'Runtime recipe ingredient mismatch at ' + row + ',' + col);
    }
    var result = itemInfo(recipe.getResultItem(level.registryAccess()));
    requireThat(result.id === 'starfold:interstellar_drive' && result.count === 1, 'Unexpected runtime recipe output');
    return {id: recipeId, pattern: pattern, inputs: ingredients, output: result};
  }
  function inspectBlock(level, p) {
    var entity = level.getBlockEntity(pos(p));
    var result = {pos: p, state: String(level.getBlockState(pos(p))), items: [], fluids: []};
    if (entity !== null) result.nbt = String(entity.saveWithFullMetadata(level.registryAccess()));
    var inv = itemCap(level, p);
    if (inv !== null) for (var i = 0; i < inv.getSlots(); i++) {
      var s = inv.getStackInSlot(i);
      if (!s.isEmpty()) result.items.push({slot: i, stack: itemInfo(s)});
    }
    var tank = fluidCap(level, p);
    if (tank !== null) for (var i = 0; i < tank.getTanks(); i++) {
      var fluid = tank.getFluidInTank(i);
      result.fluids.push({tank: i, fluid: String(Registries.FLUID.getKey(fluid.getFluid())), amount: fluid.getAmount()});
    }
    if (entity instanceof Crafter || entity instanceof Motor) result.kinetic = {
      speed: entity.getSpeed(), theoreticalSpeed: entity.getTheoreticalSpeed(), overstressed: entity.isOverStressed()
    };
    if (entity instanceof Motor) result.generatedSpeed = entity.getGeneratedSpeed();
    return result;
  }
  function snapshot(source, kind) {
    var level = source.getLevel();
    var craft = kind === 'craft';
    var points = craft ? cells.map(c => c.p).concat([chestPos, motorPos]) : [depotPos, spoutPos];
    var blocks = points.map(p => inspectBlock(level, p));
    var box = craft ? new AABB(5348, 149, 898, 5355, 157, 903) : new AABB(5356, 149, 898, 5361, 155, 903);
    var entities = level.getEntitiesOfClass(ItemEntity, box);
    var drops = [];
    var counts = {};
    function count(s) { counts[s.id] = (counts[s.id] || 0) + s.count; }
    blocks.forEach(b => b.items.forEach(s => count(s.stack)));
    for (var i = 0; i < entities.size(); i++) {
      var e = entities.get(i);
      var s = itemInfo(e.getItem());
      drops.push({uuid: String(e.getUUID()), pos: [e.getX(), e.getY(), e.getZ()], stack: s});
      count(s);
    }
    var data = source.getServer().persistentData;
    var started = data.getBoolean(prefix + (craft ? 'craft_started' : 'spout_setup2'));
    var elapsed = started ? level.time - data.getLong(prefix + (craft ? 'craft_tick' : 'spout_tick')) : null;
    var fluidsLeft = blocks.reduce((sum, b) => sum + b.fluids.reduce((n, f) => n + f.amount, 0), 0);
    var ready = craft
      ? counts['starfold:interstellar_drive'] === 1 && counts['minecraft:bucket'] === 1
      : counts['mekanismgenerators:deuterium_bucket'] === 1 && !counts['minecraft:bucket'] && fluidsLeft === 0;
    var result = {
      dimension: dimension, tick: level.time, elapsedTicks: elapsed,
      started: started, status: started && ready ? 'PASS' : elapsed !== null && elapsed >= 600 ? 'TIMEOUT_OR_DISTURBED' : 'PENDING',
      expected: craft ? {'starfold:interstellar_drive': 1, 'minecraft:bucket': 1} : {'mekanismgenerators:deuterium_bucket': 1},
      counts: counts, blocks: blocks, droppedItems: drops,
      note: 'Observed inventories/entities only; no outputs supplied. Keep player inventory and automation away.'
    };
    emit('read' + kind, result);
    return result.status;
  }
  function supplyItem(level, p, id, simulate) {
    var inv = itemCap(level, p);
    requireThat(inv !== null, 'No UP item capability at ' + p.join(','));
    // Crafter has one slot; Depot has nine (main slot 0 + eight output-buffer slots).
    var expectedSlots = p === depotPos ? 9 : 1;
    requireThat(inv.getSlots() === expectedSlots, 'Unexpected input capability size at ' + p.join(','));
    for (var i = 0; i < inv.getSlots(); i++) requireThat(inv.getStackInSlot(i).isEmpty(), 'Input/output buffer already occupied at ' + p.join(','));
    requireThat(inv.insertItem(0, stack(id), simulate).isEmpty(), 'Input rejected at ' + p.join(','));
    if (!simulate) emit('supply_item', {pos: p, item: id, count: 1, via: 'ItemHandler.BLOCK/UP.insertItem'});
  }
  function run(source, action) {
    try {
      var level = guard(source);
      var server = source.getServer();
      var data = server.persistentData;
      if (action === 'setupcraft') {
        requireThat(!data.getBoolean(prefix + 'craft_setup'), 'Craft setup already used in this world');
        var recipe = validateRecipe(server, level);
        forceLoad(server);
        emptyVolume(level, 5348, 5354, 149, 156, 898, 902);
        // Reserve the one-shot budget before the first block mutation, including failed partial setups.
        data.putBoolean(prefix + 'craft_setup', true);
        for (var x = 5348; x <= 5354; x++) for (var z = 898; z <= 902; z++) place(level, [x, 149, z], 'minecraft:stone');
        place(level, chestPos, 'minecraft:chest', {facing: 'south'});
        cells.forEach(c => {
          place(level, c.p, 'create:mechanical_crafter', {
            facing: 'south', pointing: pointingFor(c.target)
          });
          requireThat(be(level, c.p).getTargetDirection().equals(c.target), 'Crafter arrow mismatch at ' + c.p.join(','));
        });
        place(level, [5350, 155, 900], 'create:cogwheel', {axis: 'z'});
        place(level, motorPos, 'create:creative_motor', {facing: 'south'});
        var motor = be(level, motorPos);
        var nbt = motor.saveWithFullMetadata(level.registryAccess());
        nbt.putInt('ScrollValue', 64);
        motor.loadWithComponents(nbt, level.registryAccess());
        motor.updateGeneratedRotation();
        motor.setChanged();
        motor.sendData();
        requireThat(motor.generatedSpeed.getValue() === 64 && Math.abs(motor.getGeneratedSpeed()) === 64, 'Motor did not generate 64 RPM');
        emit(action, {recipe: recipe, motor: motorPos, generatedSpeed: 64, actualNBTKey: 'ScrollValue', suppliedPower: 'create:creative_motor', inputsSupplied: 0, forceLoaded: [5348, 898, 5362, 904]});
        snapshot(source, 'craft');
      } else if (action === 'startcraft') {
        requireThat(data.getBoolean(prefix + 'craft_setup') && !data.getBoolean(prefix + 'craft_started'), 'Setup required; startcraft is once per world');
        validateRecipe(server, level);
        var chain = Grid.getAllCraftersOfChain(be(level, cells[0].p));
        requireThat(chain !== null && chain.size() === 10, 'Expected exactly ten connected crafters');
        cells.forEach(c => {
          var entity = be(level, c.p);
          requireThat(entity instanceof Crafter && entity.getTargetDirection().equals(c.target), 'Crafter topology changed');
          requireThat(Math.abs(entity.getSpeed()) === 64 && !entity.isOverStressed(), 'Wait for genuine 64 RPM kinetic network at ' + c.p.join(','));
          requireThat(String(entity.saveWithFullMetadata(level.registryAccess()).getString('Phase')) === 'IDLE', 'Crafter not idle');
          supplyItem(level, c.p, c.item, true);
        });
        var chest = itemCap(level, chestPos);
        requireThat(chest !== null, 'Missing output chest');
        for (var i = 0; i < chest.getSlots(); i++) requireThat(chest.getStackInSlot(i).isEmpty(), 'Output chest must be empty');
        data.putBoolean(prefix + 'craft_started', true);
        data.putLong(prefix + 'craft_tick', level.time);
        cells.forEach(c => supplyItem(level, c.p, c.item, false));
        // Final insertion normally invokes checkCompletedRecipe(false) itself.
        // Only explicitly trigger if still IDLE; beginning twice resets the grouped ingredients.
        var first = be(level, cells[0].p);
        var idle = String(first.saveWithFullMetadata(level.registryAccess()).getString('Phase')) === 'IDLE';
        if (idle) first.checkCompletedRecipe(false);
        emit(action, {trigger: idle ? 'MechanicalCrafterBlockEntity.checkCompletedRecipe(false)' : 'Inventory insertion auto-triggered native checkCompletedRecipe(false)', suppliedItems: 10, outputsSupplied: 0});
        snapshot(source, 'craft');
      } else if (action === 'setupspout') {
        requireThat(!data.getBoolean(prefix + 'spout_setup2'), 'Spout setup already used in this world');
        var fluidId = RL.parse('mekanismgenerators:deuterium');
        requireThat(Registries.FLUID.containsKey(fluidId), 'Missing deuterium fluid');
        forceLoad(server);
        emptyVolume(level, 5356, 5360, 149, 154, 898, 902);
        data.putBoolean(prefix + 'spout_setup2', true);
        data.putLong(prefix + 'spout_tick', level.time);
        for (var x = 5356; x <= 5360; x++) for (var z = 898; z <= 902; z++) place(level, [x, 149, z], 'minecraft:stone');
        place(level, depotPos, 'create:depot');
        // DepotBehaviour.tick looks two blocks UP for a belt processing behaviour.
        place(level, spoutPos, 'create:spout');
        var tank = fluidCap(level, spoutPos);
        requireThat(tank !== null, 'Spout has no UP fluid capability');
        var fluid = new FluidStack(Registries.FLUID.get(fluidId), 1000);
        requireThat(tank.fill(fluid, FluidAction.SIMULATE) === 1000, 'Spout cannot accept 1000 mB');
        supplyItem(level, depotPos, 'minecraft:bucket', true);
        requireThat(tank.fill(fluid, FluidAction.EXECUTE) === 1000, 'Spout fill failed');
        emit('supply_fluid', {pos: spoutPos, fluid: String(fluidId), amount: 1000, via: 'FluidHandler.BLOCK/UP.fill'});
        supplyItem(level, depotPos, 'minecraft:bucket', false);
        emit(action, {trigger: 'DepotBehaviour.tick -> Spout native FillingBySpout processing', depot: depotPos, spout: spoutPos, outputsSupplied: 0, forceLoaded: [5348, 898, 5362, 904]});
        snapshot(source, 'spout');
      } else {
        requireThat(data.getBoolean(prefix + (action === 'readcraft' ? 'craft_setup' : 'spout_setup2')), 'Run setup first');
        return snapshot(source, action === 'readcraft' ? 'craft' : 'spout') === 'PASS' ? 1 : 0;
      }
      source.sendSuccess(() => Component.literal('KP_MACHINES ' + action + ': evidence logged; inspect read status in server log.'), false);
      return 1;
    } catch (error) {
      emit('error', {requestedAction: action, message: String(error)});
      source.sendFailure(Component.literal('KP_MACHINES ' + action + ': ' + String(error)));
      return 0;
    }
  }
  ServerEvents.commandRegistry(event => {
    var commands = Java.loadClass('net.minecraft.commands.Commands');
    var root = commands.literal('kpfixtures').requires(source => source.hasPermission(2));
    ['setupcraft', 'startcraft', 'readcraft', 'setupspout', 'readspout'].forEach(action => {
      root = root.then(commands.literal(action).executes(context => run(context.getSource(), action)));
    });
    event.register(root);
  });
})();
