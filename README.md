# BessaKit KitBot (Fabric mod, MC 1.20.1)

Watches chat for `##bessakit`, walks to a chest named "kits", grabs the item
in slot 0, `/tpa`'s to whoever sent the trigger, whispers them, waits for
the teleport, walks to them, drops the item, `/kill`'s itself, and waits to
respawn before accepting the next request.

## Building

You need this on your own machine (this can't be built in a sandboxed
environment without internet access to Fabric's Maven repo):

1. Install a JDK 17+ (e.g. Temurin 17).
2. From this project folder, run:
   - Windows: `gradlew.bat build`
   - Mac/Linux: `./gradlew build`
   (First run downloads Fabric Loom + mappings + Fabric API — needs internet.)
3. The built jar appears at `build/libs/bessakit-kitbot-1.0.0.jar`.

> Note: this project is missing the Gradle wrapper jar
> (`gradle/wrapper/gradle-wrapper.jar`), which binary files can't be
> generated here. Easiest fix: open this folder once in IntelliJ IDEA with
> the Fabric/Gradle plugins (it'll offer to generate the wrapper), or run
> `gradle wrapper --gradle-version 8.5` if you have Gradle installed
> separately, then re-run the build command above.

## Installing

1. Make sure your Prism instance has **Fabric Loader** for 1.20.1 installed.
2. Install **Fabric API** for 1.20.1 into the instance's `mods` folder
   (download from Modrinth/CurseForge) — this mod depends on it.
3. Drop `bessakit-kitbot-1.0.0.jar` into the instance's `mods` folder too.
4. Launch the instance normally through Prism. The mod loads automatically
   and starts watching chat once you're in-game — no extra setup needed.

## Configuring

Edit `src/main/java/com/bessakit/BessaKitConfig.java` before building:

- `CHEST_NAME` — the chest must be renamed to this via anvil (default `"kits"`)
- `KIT_SLOT_INDEX` — which chest slot to take from (default `0`, first slot)
- `CHEST_SEARCH_RADIUS` — how far to look for the chest
- `TP_TIMEOUT_TICKS` — how long to wait for the player to accept `/tpa`

If your server's commands aren't `/tpa` and `/w`, edit the `sendCommand(...)`
calls in `BessaKitMod.java`'s `REQUEST_TP` state.

## Known limitations

- **Movement is a simple "walk straight at the target + jump" steer, not
  real pathfinding.** It works for a flat, open kit room. It will get
  stuck on stairs, gaps, water, or multi-level layouts. If you need
  reliable navigation through complex terrain, this needs to be wired up
  to Baritone's pathing API instead of `SimpleNavigator`.
- **Teleport detection is a heuristic** (a sudden >8 block position jump).
  Unusual `/tpa` plugins or lag could trigger false positives/negatives —
  tune `TELEPORT_JUMP_THRESHOLD` / `TP_TIMEOUT_TICKS` if needed.
- **Username extraction from chat is naive** — it just checks whether any
  online player's name appears in the trigger message. If your server's
  chat format is unusual (colored ranks, prefixes that also look like
  names, etc.) this may need adjusting in `extractUsername(...)`.
- This hasn't been build-tested end-to-end (no Fabric Maven access in the
  environment that generated it), so expect to fix a small compile issue
  or two on first build.
