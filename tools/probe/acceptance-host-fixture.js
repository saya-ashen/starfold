ServerEvents.commandRegistry(event => {
  var Anomaly = Java.loadClass('dev.starfold.planet.Anomaly')
  var Commands = Java.loadClass('net.minecraft.commands.Commands')
  var Storage = Java.loadClass('dev.starfold.planet.PlanetStorage')
  var Coordinates = Java.loadClass('dev.starfold.planet.GroundCoordinates')
  var Point = Java.loadClass('dev.starfold.geometry.BentStripGeometry$Point')
  event.register(Commands.literal('acceptancehive').requires(source => source.hasPermission(2)).executes(ctx => {
    var player = ctx.source.getPlayerOrException()
    var level = player.serverLevel()
    var planet = level.getChunkSource().getGenerator()['planet(int)'](12)
    if (!planet.planReady()) throw new Error('Flesh plan unavailable')
    var terrain = planet.terrain()
    var shape = terrain.shape()
    var nests = terrain.terrainPlan().landforms()
    var nest = null
    for (var i = 0; i < nests.size(); i++) {
      var candidate = nests.get(i)
      if (String(candidate.type()) === 'starfold:hive') { nest = candidate; break }
    }
    if (nest === null) throw new Error('No planned hive on Flesh')
    var margin = Anomaly.distance(nest.center(), shape)
    if (margin <= Anomaly.ZONE + nest.radius()) throw new Error('Hive is too close to a fold: ' + margin)
    var pose = shape.locate(nest.center().scale(shape.radius()))
    var u = Math.trunc(pose.point().x()), v = Math.trunc(pose.point().z())
    var site = Coordinates.world(pose.face(), new Point(u + .5,
      terrain.groundY(pose.face(), u, v) + 1 - Coordinates.FLOOR, v + .5)).add(Storage.origin(12))
    player['teleportTo(net.minecraft.server.level.ServerLevel,double,double,double,float,float)'](level,
      site.x(), site.y(), site.z(), 0, 10)
    console.info('ACCEPTANCE_HIVE site=' + site + ' foldDistance=' + margin + ' radius=' + nest.radius() + ' player=' + player.position())
    return 1
  }))
})
