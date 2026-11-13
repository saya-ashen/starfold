# Roadmap

This describes development priorities, not release dates or compatibility promises. See the changelog for changes since 0.2.0 and the README for installation.

## Implemented in development

Closed cube planets, configurable systems and environmental rules; deterministic terrain and structure planning; ships and personal flight; fuel mass and delta-v; gas-giant pressure and skimming; Atlas surveys, navigation and saved-ship recovery; optional Worlds, Extras, Materials and Biotech content.

## Before a packaged public update

- Validate the final module/dependency combination in a clean installation and document new-world requirements.
- Repeat departure, arrival, landing, return and save/restart checks with that installation.
- Package all required local mod jars into a reproducible distribution; the survival pack source currently does not provide this.
- Resolve or omit third-party adaptations whose provenance review is still pending.

## Gameplay acceptance still needed

- Complete a fresh-world survival route, including equipment, oxygen, fuel production, ship construction and interstellar progression.
- Measure fuel, pressure, material and Host balance in real play.
- Improve planet-planning latency, far-view convergence and rendering consistency.
- Exercise failure recovery when a saved multi-ship group cannot finish loading.

Sodium 0.8 and Iris without a shader pack are supported and have no in-game acceptance run yet; Iris shader
packs, Embeddium and Canvas remain outside the current acceptance scope, as does multiplayer. Optional content
can evolve independently; no old-save migration is promised.
