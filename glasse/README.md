# Glassé (vendored)

A copy of `packages/android/liquidglass` from [BenItBuhner/glasse](https://github.com/BenItBuhner/glasse)
(`src/main` and its unit tests), built here as the `:glasse` module against this app's own Compose
(BOM 2024.12 / foundation 1.10) instead of being pulled from JitPack.

Upstream commit: e5d004f (branch `claude/project-thread-xpj7mo`, to be merged to `main`).

Do not edit it here: change Glassé upstream, then copy `src/main` across again.

Debug aid: launch the app with `--ef glasse.fixedStep 0.0333` and every drawn frame advances Glassé's
springs by 1/30 s, so a screen recording from a slow emulator plays the menu morphs back frame-accurately
at 30 fps.
