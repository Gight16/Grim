# AGENTS.md

This file applies to the entire repository. It is a working guide for coding agents that modify
GrimAC, especially when adding or changing anti-cheat checks.

## Project Priorities

GrimAC is a packet-based, asynchronous Minecraft anti-cheat built around movement simulation,
per-player world replication, and transaction-based latency compensation. Correctness and avoiding
false positives matter more than adding broad detections quickly.

Preserve these project constraints:

- Support Spigot, Paper, Folia, and Fabric where the affected code is shared.
- Preserve Minecraft 1.8+ client compatibility unless a check is explicitly version-gated.
- Keep common/Bukkit runtime code compatible with Java 17.
- Do not add heuristic-only checks. Prefer protocol invariants, vanilla behavior, simulation, packet
  cancellation, or corrective resynchronization.
- Do not add large dependencies for behavior that can be implemented with existing utilities.
- Keep hot packet paths allocation-conscious and non-blocking.
- Geyser/Bedrock players are globally exempt; do not add separate Bedrock assumptions to checks.

Read `CONTRIBUTING.md` before making behavior or compatibility changes. Report serious bypasses and
security issues according to `SECURITY.md`.

## Repository Map

| Path | Purpose |
| --- | --- |
| `common/` | Cross-platform anti-cheat core, checks, packet processing, prediction, compensated state, config, commands, and platform interfaces. |
| `bukkit/` | Bukkit/Spigot/Paper/Folia adapter and shaded plugin artifact. |
| `fabric/shared/` | NMS-independent Fabric services shared by mapping variants. |
| `fabric/intermediary/` | Fabric implementation for intermediary mappings and pre-26 Minecraft releases. |
| `fabric/intermediary/mc*/` | Cumulative version-specific Fabric compatibility layers. |
| `fabric/official/` | Official-mapped Fabric implementation for the 26.x line. |
| `fabric/official/mc261/` | Minecraft 26.1/26.2-specific implementation. |
| `fabric/` | Final Fabric aggregator and jar-in-jar artifact. |
| `buildSrc/` | Gradle conventions, versioning, shading, relocation, and Fabric jar-in-jar logic. |

Reusable anti-cheat behavior belongs in `common`. Platform operations belong behind interfaces in
`common/src/main/java/ac/grim/grimac/platform/api` and are implemented in the relevant Bukkit or
Fabric module. Put Minecraft-version-specific Fabric code in the narrowest applicable `mc*` module.
Older intermediary modules are inherited by later variants, so changes there can affect every later
supported version.

Some `ac.grim.grimac.api.*` and `ac.grim.grimac.internal.*` types are external dependencies, not
missing source. Check `libs.versions.toml` and `common/build.gradle.kts` before recreating them.

## Core Runtime Flow

Important entry points and owners:

- `common/src/main/java/ac/grim/grimac/GrimAPI.java`
- `common/src/main/java/ac/grim/grimac/manager/InitManager.java`
- `common/src/main/java/ac/grim/grimac/utils/anticheat/PlayerDataManager.java`
- `common/src/main/java/ac/grim/grimac/player/GrimPlayer.java`
- `common/src/main/java/ac/grim/grimac/manager/CheckManager.java`
- `common/src/main/java/ac/grim/grimac/events/packets/CheckManagerListener.java`
- `common/src/main/java/ac/grim/grimac/predictionengine/MovementCheckRunner.java`

The high-level lifecycle is:

1. A platform loader initializes `GrimAPI` and PacketEvents.
2. `PlayerDataManager` creates one `GrimPlayer` for each eligible connection.
3. `GrimPlayer` creates compensated state, processors, `CheckManager`, `PunishmentManager`, and the
   movement runner.
4. `CheckManager` constructs per-player check instances and fixed listener arrays.
5. Packet listeners update transaction-compensated state and dispatch packet/domain events.
6. Movement packets run prediction and produce `PredictionComplete` for post-prediction checks.
7. Successful flags update violation state, fire API events, and feed punishment/alert/storage logic.
8. Setbacks are sent and tracked by `SetbackTeleportUtil` until acknowledged.

## Check Fundamentals

Normal checks extend `Check`. Block-placement checks that need `cancelVL` and collision helpers may
extend `BlockPlaceCheck`.

Every active built-in detector should have `@CheckData` with:

- A stable user-facing `name`.
- A unique, permanent `stableKey`. Current built-ins use the form
  `grim.<domain>.<lower_snake_case_identity>`.
- An accurate `description`.
- Optional `configName`, `alternativeName`, `decay`, `setback`, and `experimental` values.

Do not change a published stable key when renaming a check or display name. Stable keys identify
stored violation/verbose data. Keep `Verbose` schemas stable for the same reason; field order and
wire types are persistence contracts.

Use the standard constructor:

```java
public NewCheck(GrimPlayer player) {
    super(player);
}
```

`Check` calls `reload()` from its constructor. An overridden `onReload()` can therefore run before
subclass construction is complete. Only assign primitive/simple configuration there; do not depend
on subclass fields initialized after `super(player)`.

## Registration And Ordering

`@CheckData` does not register a check. Add every built-in check explicitly to the
`ImmutableClassToInstanceMap.Builder` in `CheckManager`:

```java
.put(NewCheck.class, new NewCheck(player))
```

Registration order becomes listener dispatch order. There is no priority annotation. Place checks
after processors whose state they consume and before processors that must observe final state.
Preserve ordering comments. In particular:

- `AimProcessor` must run before aim checks that consume its output.
- `SetbackBlocker` has receive-order requirements.
- `SetbackTeleportUtil` intentionally runs late in post-prediction handling so unsafe movement is
  evaluated before safe position advances.
- Listenerless checks still belong in `allChecks` and must be invoked explicitly by their owner, as
  `Reach` invokes `Hitboxes`.

`Check.isApplicable()` is evaluated once while dispatch arrays are built. Use it only for immutable
connection properties such as client/server protocol support. Do not use config, permissions, game
mode, world, or other mutable state there.

## Listener Selection

Listener interfaces live in `common/src/main/java/ac/grim/grimac/checks/type`.

| Listener | Use |
| --- | --- |
| `PreViaPacketReceiveListener` | Raw client packet semantics before ViaVersion translation. |
| `PreViaPacketSendListener` | Raw outgoing semantics before ViaVersion translation. |
| `PrePredictionPacketReceiveListener` | State checks or cancellation that must happen before movement prediction. |
| `PacketReceiveListener` | Normal receive handling after movement/domain preparation. |
| `PacketSendListener` | Normal server packet tracking. |
| `PositionListener` | Claimed position updates before movement simulation completes. |
| `RotationListener` | Rotation updates; processors may attach derived rotation data. |
| `VehicleCheck` | Vehicle position updates. |
| `PostPredictionListener` | Checks based on final prediction state and offset. |
| `BlockPlaceListener` | Early block placement validation; can cancel/resync. |
| `PostFlyingBlockPlaceListener` | Placement analysis after position/look reconciliation; usually too late for ordinary cancellation. |
| `BlockBreakListener` | Early digging validation; can cancel/resync. |
| `PostFlyingBlockBreakListener` | Digging analysis after position/look reconciliation. |

Do not select pre-Via by default. Use it only when protocol representation before translation is
part of the invariant.

For packet listeners, test packet type before constructing a wrapper. Configuration-state packets
may also reach check dispatch. Do not retain `PacketReceiveEvent`, `PacketSendEvent`, or wrapper
instances after the callback; extract immutable values before scheduling delayed work.

Main movement receive order is broadly:

1. Resolve teleport and duplicate-packet state.
2. Prepare possible knockback/explosion state.
3. Dispatch pre-prediction packet listeners.
4. Stop if a movement packet was cancelled.
5. Reconcile queued block actions and update claimed position/rotation.
6. Dispatch rotation, position, or vehicle listeners.
7. Dispatch early digging/placement domain events.
8. Dispatch normal packet listeners.
9. Run movement prediction and post-prediction listeners where applicable.

Read `CheckManagerListener` before depending on precise same-packet state.

## Detection And Enforcement

Preferred detection APIs are `flag(...)`, `flagWithSetback(...)`, and `reward()`.

- `flag(...)` may return `false` because Grim/check permissions suppress it or an API `FlagEvent`
  listener cancels it. Perform enforcement only after a successful flag.
- `reward()` is explicit. Decay is not automatic or time-based.
- Raw per-check `violations` and punishment-group rolling violations are different counters.
- A setback occurs when `violations > setbackVL`, not `>=`.
- `setbackvl: 0` therefore setbacks on the first successful flag.
- `setbackvl: -1` disables violation setbacks.
- `BlockPlaceCheck.shouldCancel()` uses `violations >= cancelVL` and is inclusive.
- `isEnabled` does not stop dispatch or `flag()` by itself. It participates in packet-modification
  decisions. Ensure punishment selectors include a new check and never assume an unmatched check is
  inert.

Use structured verbose data for new checks:

```java
private static final Verbose V = Verbose.of("value={f64}");

flag(V.write(verbose()).f64(value));
```

Keep writer method types and order identical to the template. Use `VerboseCodecs` for version-aware
block, item, packet, entity, or enum values. Structured verbose is stored at flag time; plain strings
depend on configured `[log]` actions.

Packet modification pattern:

```java
if (invalid && flag(writer) && shouldModifyPackets()) {
    event.setCancelled(true);
    player.onPacketCancel();
}
```

When mutating a wrapper instead of cancelling it, call `event.markForReEncode(true)` where required.
Never cancel packet state without considering client correction and downstream check state.

Use domain correction methods where available:

- `BlockPlace.resync()` for invalid placement.
- `BlockBreak.cancel()` for invalid digging.
- `flagWithSetback(...)` for normal corrective teleports.
- `executeForceResync()` only for desynchronization/safety behavior, not as a punishment shortcut.

Respect global and per-check permissions managed by `GrimPlayer` and `Check`:

- `grim.exempt`
- `grim.disabled`
- `grim.nomodifypacket`
- `grim.nosetback`
- `grim.exempt.<configName>`
- `grim.nomodifypacket.<configName>`
- `grim.nosetback.<configName>`

## Player State And Threading

Treat per-player check state as owned by the player's anti-cheat/channel event loop. Most mutable
collections and player fields are not safe for arbitrary async access.

- Put raw Netty packet-lifecycle fields in `PacketStateData`.
- Put lag-compensated state in a dedicated compensated class.
- Use `GrimPlayer.runSafely(...)` when returning to player state from another thread.
- Use `PlatformScheduler` entity schedulers for native entity work.
- Use region schedulers for native block/world work.
- Do not use a global scheduler as an entity-thread substitute on Folia.
- Use async schedulers only for thread-safe computation or I/O.
- Never block Netty/player event loops with file, database, network, or server-thread waits.
- Do not directly mutate `GrimPlayer`, check instances, compensated maps, or native Minecraft state
  from arbitrary async tasks.

`TickManager.tickSync` is not guaranteed to own every player region on Folia. Follow existing
scheduler bridges instead of relying on method names.

## Compensated State And Transactions

Checks should reason about state the client could have known, not current native server state.

- Use `player.compensatedWorld` for blocks, chunks, fluids, and world queries.
- Use `player.compensatedEntities` for entities and attributes.
- Use `player.inventory` (`CompensatedInventory`) for inventory and held-item state.
- Use transaction counters and `LatencyUtils` for server changes that become visible later.
- Use transaction ping, not keepalive ping, for client-observed state timing.
- Avoid `GrimPlayer.getLocation()` for packet decisions; it uses native, non-lag-compensated world
  state.

`LatencyUtils.addRealTimeTask(...)` runs immediately on the caller thread if the target transaction
was already acknowledged. Use `addRealTimeTaskAsync(...)` when scheduling from server, region,
piston, or other external callbacks so the immediate path returns through `player.runSafely(...)`.
Keep transaction tasks in chronological insertion order.

Missing or unloaded compensated chunks can read as air or sentinel IDs. Check
`isChunkLoaded(...)`/`areChunksUnloadedAt(...)` before treating absence as proof. Do not replace
compensated queries with Bukkit/Fabric native world reads in hot checks.

## Prediction And Movement

`MovementCheckRunner` owns simulation orchestration. `PredictionEngine` generates possible movement
vectors, collision logic tests them, and `UncertaintyHandler` reduces offsets for known ambiguous
vanilla/network cases.

Post-prediction checks should normally begin with:

```java
if (!predictionComplete.isChecked()) return;
```

Use existing uncertainty signals instead of broad ping exemptions. Important ambiguity includes
teleports, duplicate movement packets, skipped 0.03 movement, pistons, hard entity collisions,
bouncy/glitchy blocks, vehicle transitions, flying/gliding transitions, unloaded chunks, respawn,
and world changes.

Post-prediction does not mean every client tick. Sleeping, teleports, unsupported movement states,
and packets without simulated position can bypass normal post-prediction dispatch. For packet tick
semantics use `Check.isTickPacket(...)` or `isTickPacketIncludingNonMovement(...)`.

Useful state/reference classes:

- `GrimPlayer`
- `PacketStateData`
- `PredictionComplete`
- `PositionUpdate`, `RotationUpdate`, and `VehiclePositionUpdate`
- `MovementCheckRunner`
- `UncertaintyHandler`
- `LastInstance` and `LastInstanceManager`
- `VectorData` for movement-vector provenance

Do not infer knockback, explosion, jump, input, or 0.03 provenance from numeric vector values alone;
use `VectorData` metadata.

## Math, Collision, And Ray Utilities

Use existing vanilla/version-aware utilities instead of generic math:

- `GrimMath` for floor, ceil, clamp, lerp, and vanilla-sensitive numeric behavior.
- `VectorUtils.normalize(ClientVersion, ...)` for client-version precision and near-zero handling.
- `ReachUtils.getLook(...)` for client-version and FastMath-aware look vectors.
- `Collisions` for movement collisions and material/collision-box traversal.
- `CollisionData` and `HitboxData` for version-aware shapes.
- `WorldRayTrace` and `ReachUtils.calculateIntercept(...)` for ray tests.

`Vector3dm` is mutable. Methods such as `add`, `subtract`, `multiply`, `normalize`, `midpoint`, and
rotations mutate the receiver. Clone/copy player-owned vectors before calculations.

`SimpleCollisionBox` is mutable. Methods such as `expand`, `offset`, `sort`, and coordinate expansion
mutate the receiver. Call `copy()` before changing shared/player boxes. `isCollided` includes touching
boundaries; `isIntersected` requires overlap using collision epsilon.

Some collision helpers update uncertainty or temporarily replace player state and are not pure.
Only call them on the owning player event loop.

## Version Handling

Use `player.getClientVersion()` for client behavior and PacketEvents `ServerVersion` for server
behavior. Check both when packet existence or ViaVersion translation depends on both endpoints.

- Use comparison helpers such as `isOlderThan` and `isNewerThanOrEquals`.
- Prefer `isApplicable()` for immutable whole-check support boundaries.
- Use runtime guards when behavior changes within an otherwise applicable check.
- Account for ViaVersion replacement blocks, packet forms, hitboxes, and precision behavior.
- Do not assume server and client versions match.

## Configuration And Punishments

Canonical bundled defaults live in:

- `common/src/main/resources/config/en.yml`
- `common/src/main/resources/messages/en.yml`
- `common/src/main/resources/punishments/en.yml`

Base `Check.reload(...)` reads `<configName>.decay`, `.setbackvl`, `.displayname`, and `.description`.
Read custom settings in `onReload(ConfigManager)` with typed `get*Else` methods and defaults matching
bundled config.

When changing config shape, inspect all language resources and
`manager/config/update/GrimConfigSpecs.java`. Bump schema versions and add migrations when required
so existing operator files update safely. `punishments.yml` is intentionally open-ended and not
schema-migrated.

Punishment check selectors are case-insensitive substring matches against `checkName` and
`alternativeName`; they do not match `stableKey` or `configName`. Broad names can activate unintended
groups. Verify a new check matches exactly the intended default group and does not accidentally match
multiple groups.

## Adding A New Check

Use this sequence:

1. Define the protocol/vanilla invariant and desired response. Avoid heuristic-only evidence.
2. Identify the exact dispatch phase and state available there.
3. Read two or more neighboring active checks and their processors before coding.
4. Extend `Check` or `BlockPlaceCheck` and add complete `@CheckData`.
5. Keep mutable detector state on the per-player check instance; never static.
6. Add precise version, teleport, duplicate, vehicle, chunk, and uncertainty guards.
7. Use compensated world/entity/inventory state.
8. Add structured `Verbose` data with stable field order.
9. Choose `flag`, packet cancellation, block resync, or setback explicitly.
10. Register the check in `CheckManager` at the correct order position.
11. Add config defaults and config migration/version updates when needed.
12. Verify punishment selectors enable the check and produce intended actions.
13. Add explicit reset calls for respawn/world changes if state crosses those boundaries; there is no
    universal lifecycle reset callback for every check.
14. Test phase, protocol, permission, reload, and false-positive boundaries.

Good references:

| Check | Pattern |
| --- | --- |
| `BadPacketsA` | Small pre-Via packet-state check and cancellation. |
| `BadPacketsD` | Small normal packet check, structured verbose, teleport guard. |
| `AimModulo360` | Rotation listener and reward behavior. |
| `SprintA` | Basic post-prediction check and setback. |
| `InvalidPlaceA` | Minimal block placement cancellation/resync. |
| `AirLiquidPlace` | Compensated block history and desync protection. |
| `FastBreak` | Cross-phase block timing and version translation. |
| `ElytraB` | Packet finding carried into post-prediction enforcement. |
| `Timer` | Pre-prediction timing, transaction clock, and packet cancellation. |
| `Reach` | Advanced compensated entity/ray analysis and queued attacks. |
| `KnockbackHandler` | Advanced transaction-sandwiched velocity handling. |

Do not copy `FlightA`, `Baritone`, or `PacketOrderP` as active patterns. They are unregistered,
disabled, or intentionally excluded.

## Build, Format, And Test

Run commands from repository root. On Windows use `gradlew.bat`; on POSIX use `./gradlew`.

```powershell
./gradlew.bat printVersion
./gradlew.bat spotlessCheck
./gradlew.bat :common:test
./gradlew.bat check
./gradlew.bat build -PmavenLocalOverride=false
```

Useful focused test:

```powershell
./gradlew.bat :common:test --tests ac.grim.grimac.checks.VerboseTemplateAuditTest
```

Full current builds are safest with Java 25 because official Fabric modules require it, although the
base toolchain is Java 21 and most production bytecode targets Java 17. Foojay toolchain resolution
can provision missing toolchains.

Important build behavior:

- `build` depends on `spotlessApply` and can modify source files. Prefer `spotlessCheck` first and
  inspect the diff after a build.
- Local Maven artifacts take precedence by default through `mavenLocalOverride=true`. Use
  `-PmavenLocalOverride=false` for CI-like dependency resolution.
- `--scan` publishes a Develocity build scan; add it only intentionally.
- Bukkit artifacts appear in `bukkit/build/libs`.
- Fabric artifacts appear in `fabric/build/libs`.

The tracked automated test suite is small. `VerboseTemplateAuditTest` validates structured verbose
templates and writer types, not detector behavior. Add focused unit/source tests where feasible and
manually reason through these boundaries for check changes:

- Supported minimum and maximum client/server versions.
- ViaVersion client/server mismatch.
- Teleports and duplicate movement packets.
- Missing position and tick-end packets.
- Vehicles, death, spectator/flying/gliding states.
- Unloaded chunks, ghost blocks, and block history.
- High latency and transaction ordering.
- Config reload and constructor-time reload.
- Experimental checks disabled/enabled.
- Global and per-check exemption/modification/setback permissions.
- Respawn and world changes.

## Style And Change Boundaries

- Follow `.editorconfig`: UTF-8, four spaces, final newline, trimmed trailing whitespace, and a
  nominal 100-column limit.
- Match nearby Java style; Spotless does not enforce wrapping or a full opinionated formatter.
- Use comments for version-specific, threading-sensitive, or mathematically non-obvious behavior.
- Preserve attribution and license comments in upstream-derived code.
- Do not edit generated `build/`, `.gradle/`, runtime `run/`, or IDE output.
- Do not replace `${...}` placeholders in resource templates; Gradle expands them.
- Treat the marked generated expression in `Materials.isCompostable` as generated code.
- Keep unrelated working-tree changes intact.

Before finishing, inspect the diff, run the narrowest relevant tests, then run `spotlessCheck` and
broader checks when practical. State clearly when full platform/version testing was not possible.
