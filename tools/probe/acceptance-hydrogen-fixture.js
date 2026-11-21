// Test-only command, staged in an isolated instance. Supply water and power; observe real machine output.
(() => {
  var Commands = Java.loadClass('net.minecraft.commands.Commands');
  var Pos = Java.loadClass('net.minecraft.core.BlockPos');
  var AutomationType = Java.loadClass('mekanism.api.AutomationType');
  var Stack = Java.loadClass('net.neoforged.neoforge.fluids.FluidStack');
  var Action = Java.loadClass('mekanism.api.Action');
  var Fluids = Java.loadClass('net.minecraft.world.level.material.Fluids');
  var Registry = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
  var Component = Java.loadClass('net.minecraft.network.chat.Component');
  var separator = new Pos(5350, 151, 900);
  var rotary = new Pos(5352, 151, 900);
  function run(source, operation) {
    try {
      var level = source.getLevel();
      if (String(level.dimension) !== 'starfold:native_planet' ||
          source.getPlayerOrException().distanceToSqr(5351, 151, 900) > 32 * 32) {
        throw new Error('wrong dimension or player out of fixture range: ' + source.getPosition());
      }
      var separatorTile = level.getBlockEntity(separator);
      var rotaryTile = level.getBlockEntity(rotary);
      if (separatorTile === null || rotaryTile === null || rotaryTile.getMode()) {
        throw new Error('missing machines or rotary in the wrong mode');
      }
      if (operation === 'supply') {
        var water = new Stack(Fluids.WATER, 100);
        var remaining = separatorTile.fluidTank.insert(water, Action.SIMULATE, AutomationType.INTERNAL);
        if (!remaining.isEmpty()) throw new Error('separator water tank rejected 100 mB');
        [separatorTile, rotaryTile].forEach(tile => {
          var energy = tile.getEnergyContainer();
          energy.setEnergy(energy.getMaxEnergy());
        });
        remaining = separatorTile.fluidTank.insert(water, Action.EXECUTE, AutomationType.INTERNAL);
        return remaining.isEmpty() ? 1 : 0;
      }
      if (operation === 'transfer') {
        var gas = separatorTile.leftTank.extract(100, Action.SIMULATE, AutomationType.INTERNAL);
        console.info('HYDROGEN_TEST separator gas=' + gas + ' water=' + separatorTile.fluidTank.getFluidAmount()
          + ' rotary gas=' + rotaryTile.gasTank.getStored() + ' fluid=' + rotaryTile.fluidTank.getFluid());
        if (gas.isEmpty()) return 0;
        if (!rotaryTile.gasTank.insert(gas, Action.SIMULATE, AutomationType.INTERNAL).isEmpty()) {
          throw new Error('rotary rejected separator output');
        }
        var extracted = separatorTile.leftTank.extract(gas.getAmount(), Action.EXECUTE, AutomationType.INTERNAL);
        return rotaryTile.gasTank.insert(extracted, Action.EXECUTE, AutomationType.INTERNAL).isEmpty() ? 1 : 0;
      }
      var stack = rotaryTile.fluidTank.getFluid();
      if (String(Registry.FLUID.getKey(stack.getFluid())) === 'mekanism:hydrogen') {
        console.info('HYDROGEN_TEST product=mekanism:hydrogen amount=' + stack.getAmount());
        return stack.getAmount();
      }
      return 0;
    } catch (error) {
      source.sendFailure(Component.literal('HYDROGEN_TEST error: ' + error));
      console.error('HYDROGEN_TEST error: ' + error);
      return 0;
    }
  }
  ServerEvents.commandRegistry(event => {
    var root = Commands.literal('hydrogentest').requires(source => source.hasPermission(2));
    ['supply', 'transfer', 'read'].forEach(operation => {
      root.then(Commands.literal(operation).executes(context => run(context.getSource(), operation)));
    });
    event.register(root);
  });
})();
