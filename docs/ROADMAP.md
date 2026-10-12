# Roadmap

[简体中文](ROADMAP_zh-CN.md)

What is planned beyond the current release, and how far each item has been thought through. Nothing here is a
promise or a date. An item marked *decided* has an agreed direction; *idea* means it was discussed and judged
workable on paper; *unknown* names a question that has to be answered before the item can be built.

## In progress: making the code easier to work in

The code is being reorganised on the branch `refactor`, with no change in behaviour: packages by subsystem, one
formatting style, and dependencies that point one way. The aim is that no package depends on a package above it;
small cycles inside one package are accepted. What is left is the server runtime, where ground, personal flight,
ships and the space region call each other directly. The plan for it:

- One record for each player of which frame they are in and who owns it (on foot on a planet, in personal flight,
  aboard a hull, in the space region), kept low and read by everyone, in place of asking each subsystem.
- Lower layers announce what happened; the wiring class connects whoever listens.
- Order from the bottom up: geometry, chart, planet, sky, worldgen, universe, ground, flight, ship, space, then
  waypoint, render and dev, with the wiring on top. The space region knows ships; ships do not know space.

## Next: one model for every body *(decided)*

Today a sun is a position with a radius, a planet is an orbit with storage and generation, and a ship is a physics
body that the celestial model does not know. The first two planets of a system are written as a fixed pair.

Planned is one body type built from parts:

| Part | Holds | Present |
|---|---|---|
| Identity | region and index, name, kind | always |
| Motion | fixed, an orbit about another body, or driven by physics | always, one of the three |
| Extent | radius, strength and range of gravity, the heights at which a ship is handed over | always |
| Appearance | how it is drawn from afar | always |
| Surface | real chunks: storage, shape, generation | optional |
| Behaviours | giving light, a hazard zone, a satellite's function | optional |

Kinds are combinations: a sun is fixed and gives light; a planet orbits a sun and has a surface; a moon orbits a
planet; a gas planet has no surface; a ship is driven by physics and carries its blocks in a hull.

- Worlds saved by 0.1.x will not load after this change. *(decided)*
- A body's identity is its region and its index in it, not one number for the whole universe, so that regions can
  later be separated.
- The fixed pair of home planets goes away with it.

## Built on the body model

- **Moons.** Needs an orbit whose centre is another body. *(idea)*
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
  space region. *(idea)* *Unknown:* how Sable keeps and restores a hull nobody is near.
- **Gravity with a range.** Each body pulls within a distance and not beyond it; only the nearest body counts.
  Ship weight already fades with height above a planet and is zero in space, with heights that are global constants;
  they become properties of the body and reach into the space region. A ship nobody pilots stays where it is
  relative to the nearest body and turns with it. *(decided)* The work is in the feel: arrival and hovering have to
  be retuned by flying.

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
