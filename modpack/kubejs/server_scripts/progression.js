// All recipe quantities/yields below are first guesses; tune through survival play.
ServerEvents.recipes(event => {
  const replace = (output, pattern, keys) => {
    event.remove({ output: output })
    event.shaped(output, pattern, keys).id('escape_velocity:' + output.split(':')[1])
  }

  // No Nether: bootstrap heated brass and electronics entirely from home resources.
  replace('create:blaze_burner', [' I ', 'ICI', ' B '], {
    I: 'create:iron_sheet', C: 'minecraft:coal_block', B: 'minecraft:copper_ingot'
  })
  replace('create:rose_quartz', ['RRR', 'RGR', 'RRR'], {
    R: 'minecraft:redstone', G: 'minecraft:glass'
  })

  replace('starfold:thruster', [' B ', 'PMP', ' I '], {
    B: 'create:blaze_burner', P: 'create:brass_sheet',
    M: 'create:precision_mechanism', I: 'create:iron_sheet'
  })
  replace('starfold:helm', [' E ', 'PMP', ' C '], {
    E: 'create:electron_tube', P: 'create:brass_sheet',
    M: 'create:precision_mechanism', C: 'create:brass_casing'
  })
  replace('starfold:gyro', [' P ', 'EME', ' P '], {
    P: 'create:iron_sheet', E: 'create:electron_tube', M: 'create:precision_mechanism'
  })
  // The upstream spyglass ingredient needs amethyst, reserved for crystal planets here.
  replace('starfold:scope', [' G ', 'ICI', ' I '], {
    G: 'minecraft:glass', I: 'create:iron_sheet', C: 'minecraft:copper_ingot'
  })
  // First-trip target locking must be available before off-world osmium.
  replace('starfold:scope_nav', [' M ', 'BSB', ' E '], {
    M: 'create:precision_mechanism', B: 'create:brass_sheet',
    S: 'starfold:scope', E: 'create:electron_tube'
  })
  replace('starfold:scope_precise', [' F ', 'CSC', ' F '], {
    F: 'mekanism:fluorite_gem', C: 'mekanism:advanced_control_circuit', S: 'starfold:scope_nav'
  })

  // Mid-game intake follows the hydrogen machinery; quantities are first guesses.
  replace('starfold:gas_scoop', [' P ', 'CTC', ' H '], {
    P: 'create:precision_mechanism', C: 'mekanism:advanced_control_circuit',
    T: 'create:fluid_tank', H: 'mekanism:rotary_condensentrator'
  })
  // Chemical outputs are not item outputs: remove the heavy-water separator by ID.
  // Keep reversible rotary conversion: it only converts already-skimmed deuterium.
  event.remove({ id: 'mekanismgenerators:separator/heavy_water' })

  // Ten ingredients retain the original material counts and require mechanical crafting.
  event.remove({ output: 'starfold:interstellar_drive' })
  event.custom({
    type: 'create:mechanical_crafting',
    pattern: ['HCH', 'CDC', 'HCH', ' G '],
    key: {
      H: { item: 'starfold_materials:osmium_pressure_hull' },
      C: { item: 'mekanism:elite_control_circuit' },
      D: { item: 'mekanismgenerators:deuterium_bucket' },
      G: { item: 'starfold_biotech:host_ganglion' }
    },
    result: { id: 'starfold:interstellar_drive', count: 1 },
    accept_mirrored: false
  }).id('escape_velocity:interstellar_drive')
})
