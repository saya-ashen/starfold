# Roadmap

[简体中文](ROADMAP_zh-CN.md)

What is planned beyond the current release, and how far each item has been thought through. Nothing here is a
promise or a date. An item marked *decided* has an agreed direction; *idea* means it was discussed and judged
workable on paper; *unknown* names a question that has to be answered before the item can be built.

## Done in 0.2.0

- **The code is easier to work in.** Packages by subsystem, one formatting style, and dependencies that point one
  way: geometry, chart, planet, sky, worldgen, universe, ground, flight, ship, space, then waypoint, render and dev,
  with the wiring on top. A lower layer asks the layers above it only through a host interface that the wiring
  installs. The only references left that point up are a few into mixins and one from sky into universe.
- **The first part of the body model.** Suns, planets and moons are one kind of body: a motion (fixed, or an orbit
  about another body), a radius, whether it gives light, and a reach for gravity, hand-over and hull weight. The
  fixed pair of home planets is gone, a system may hold a single planet, and every body of a world is in one level.
- **Moons**, defined in datapacks, with full surfaces, and other bodies drawn in the sky seen from the ground.
- **Ships nobody pilots in space** coast to a stop and then hold their place relative to the nearest body, also
  across saving and reloading.

## Next: finish the body model *(decided)*

Planned is one body type built from parts:

| Part | Holds | Present | State |
|---|---|---|---|
| Identity | region and index, name, kind | always | open: a body is still one number for the whole universe |
| Motion | fixed, an orbit about another body, or driven by physics | always, one of the three | fixed and orbit done; physics open |
| Extent | radius, strength and range of gravity, the heights at which a ship is handed over | always | done |
| Appearance | how it is drawn from afar | always | open |
| Surface | real chunks: storage, shape, generation | optional | done |
| Behaviours | giving light, a hazard zone, a satellite's function | optional | light done |

Kinds are combinations: a sun is fixed and gives light; a planet orbits a sun and has a surface; a moon orbits a
planet; a gas planet has no surface; a ship is driven by physics and carries its blocks in a hull. Ships are not
bodies yet: they are kept as records of their own.

- A body's identity is its region and its index in it, not one number for the whole universe, so that regions can
  later be separated.

## Built on the body model

- **Space stations as one-faced bodies.** A station is a body with a single flat face and no terrain. It has
  chunks of its own, is reached and landed on like a planet, and exists whether or not a ship is near. It circles
  a planet and keeps the same side towards it; gravity on it points at that planet, so the planet is underfoot and
  a ship lands from the side facing away. It does not move otherwise. It has no seams and no fold points: it is an
  ordinary man-made structure. Walking off its edge means falling towards the planet. Stations are written in a
  datapack or founded by a player in play, the datapack first. *(decided)* A weightless station is possible later
  by giving the body no gravity and its crew another way to move. *(idea)* Open: how small a face may be, and how a
  structure with gaps is drawn from afar.
- **Artificial satellites as orbit records.** Built and carried up as blocks, turned into a saved record when
  released, turned back into items when retrieved; drawn as a small model at the computed place. The first version
  is the structure alone: launch, orbit, retrieval and a point of light in the night sky. *(decided)* What a
  satellite does comes later; navigation, mapping and relaying were named. *(idea)*
- **Ships that stay.** A ship with nobody near becomes a record with an identity and a place, so that it can be
  left and found again and several ships can be apart at once. This also lifts today's limit of one lead ship in the
  space region. *(idea)* Built so far, behind a development switch and off in releases: a hull in space is
  recorded with its place relative to its body when it unloads and loaded there again. In the release tests waking
  it at its current place failed, so it stays off until that is understood.
- **Gravity with a range.** Each body pulls within a distance and not beyond it; only the nearest body counts.
  *(decided)* The reach is now a property of the body and sets hand-over and hull weight, and a ship nobody pilots
  holds still relative to the nearest body. Left: gravity in the space region itself, and retuning arrival and
  hovering by flying.

## Far ahead: large multiplayer *(idea, nothing is to be built for it now)*

A vanilla server ticks every dimension on one thread, so regions as dimensions would not use more cores, and
threads per region cannot be made safe together with Create and Sable. If large servers become a goal, the workable
form is separate processes:

- Each region, such as a star system, is an ordinary server process. A jump moves the players and their ship to
  another process; players connect to it directly.
- A light coordinator holds the registry (which process runs which region, where each player and ship is), carries
  out the hand-over, and keeps what regions share: one clock, and records seen across regions such as left ships,
  stations and satellites. It runs no world.
- A hand-over must never lose a ship or make two of it: it is recorded durably and is complete only when both sides
  confirm.
- Regions keep running when the coordinator is down; only jumps stop.
- Single player and small servers stay one process. Handing a ship over is one operation that runs directly there
  and through the coordinator on a cluster.

*Unknown:* whether a Sable hull with its blocks, entities and crew can be saved whole and restored in another
process. Ships that stay need the same ability, so the body model answers it. The body model keeps the three
properties this depends on: identity by region and index, ships that can be stored as records, and positions that
follow from time alone.

Regions as dimensions joined by a jump inside one process were considered and set aside: server cost would be
about the same as today's single level.
