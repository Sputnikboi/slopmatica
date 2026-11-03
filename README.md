# Slopmatica

A ground-up Litematica replacement for Minecraft 26.3 (Fabric, client-side).

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

Loads `.litematic` files from the game's `schematics` folder and shows them as a hologram:
vanilla-textured, slightly see-through ghost blocks with outlines traced from each block's
actual model:

- **blue**: missing
- **green**: correct
- **yellow**: right block, wrong orientation or variant (including look-alikes such as waxed vs
  unwaxed copper); outlines the shape the schematic wants
- **red**: a different block; outlines the block that is there

## Use

- **M** opens the menu: load schematics (with search across subfolders), manage placements,
  options.
- **J** opens the Quick Menu (a hotbar or a wheel of actions), **K** the build view (free camera,
  move/select/crop tools; **V** saves the selection there), **B** sets a selection corner.
- Every keybind lives in vanilla Options > Controls > Key Binds > Slopmatica (or the menu's
  Options > Key Binds...).
- The local-only edition (`slopmatica-local-<version>.jar`) has no online features at all.
- `/slopmatica` has commands for load, move, nudge, rotate, mirror, show/hide and opacity.

Requires Fabric Loader 0.19.5+ and Fabric API.

## Build

    ./gradlew build        # jar in build/libs
    ./gradlew test
