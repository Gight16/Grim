package ac.grim.grimac.checks.type;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared geometry for the block line-of-sight family (place / break / interact).
 *
 * <p>The invariant is the same one {@code AttackLineOfSight} enforces for entities: the client
 * picks its block target with a ray trace from the eye, so a collidable block sitting between the
 * eye and the clicked hitbox makes the action impossible in vanilla.
 *
 * <p>Every ambiguity is resolved in the player's favour. Both the current and previous claimed
 * position, every possible eye height, and every look vector the client could still be a tick
 * behind on are tried; a single unobstructed candidate exonerates the action. Rays that never
 * touch the clicked hitbox are not evidence here (that is what the Rotation checks are for), so
 * they never flag.
 */
public abstract class BlockLineOfSightCheck extends Check {
    private static final Verbose V =
            Verbose.of("block={block}, pos={mcpos}, target={mcpos}, distance={f64:%.3f}, [pre-flying|post-flying]");

    /** Collision-epsilon guard so a ray grazing the block it starts/ends in isn't an occluder. */
    private static final double BLOCK_HIT_EPSILON = 1.0E-7;
    /** Analysis cap only. Distance is FarPlace/FarBreak's job; this can never turn into a flag. */
    private static final double MAX_TRACE_DISTANCE = 16;
    private static final HitData UNLOADED = new HitData(null, null, null, null);
    /** Two dispatch phases per action, so this allows a burst of eight actions in a tick. */
    private static final int MAX_ANALYZED_ACTIONS_PER_TICK = 16;

    private final SimpleCollisionBox[] targetBoxes =
            new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];
    private final SimpleCollisionBox[] occluderBoxes =
            new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];

    private int analyzedTick = -1;
    private int analyzedActions;

    private @Nullable HitData blockingHit;
    private double blockingDistance;

    /**
     * The early phase already reached a verdict for the pending action, so the post-flying pass
     * must not re-judge it. Reset by the next early dispatch, so a cancelled action can only ever
     * skip one following post-flying pass.
     */
    protected boolean ignorePost;

    /** Master switch for packet cancellation / resync. */
    protected boolean blockInvalidActions;
    /** Cancel once {@code violations >= cancelVL}; {@code -1} never cancels. */
    protected int cancelVL;
    /** Treat every pickable hitbox as an occluder instead of only movement-collidable blocks. */
    protected boolean strict;
    /** Extra margin on the clicked hitbox, used only by the aim test. */
    protected double hitboxExpansion;

    protected BlockLineOfSightCheck(GrimPlayer player) {
        super(player);
    }

    protected enum Sight {
        /** At least one candidate ray reached the clicked hitbox unobstructed. */
        CLEAR,
        /** No candidate ray reached the clicked hitbox; not this check's invariant. */
        NO_AIM,
        /** Every candidate ray that reached the clicked hitbox crossed a block first. */
        BLOCKED,
        /** Unloaded chunks or an unusable target shape; no conclusion possible. */
        EXEMPT
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        String name = getConfigName();
        if (name == null) return;
        blockInvalidActions = config.getBooleanElse(name + ".block-invalid-actions", true);
        cancelVL = config.getIntElse(name + ".cancelvl", 0);
        strict = config.getBooleanElse(name + ".strict", true);
        hitboxExpansion = Math.max(0, config.getDoubleElse(name + ".hitbox-expansion", 0.03));
    }

    /** Cancellation is opt-out and gated on its own violation threshold. */
    protected boolean shouldCancelAction() {
        return blockInvalidActions && cancelVL >= 0 && violations >= cancelVL && shouldModifyPackets();
    }

    /** Guards shared by every subclass; none of these states let us reason about the ray. */
    protected boolean canCheck() {
        return isEnabled()
                && !player.disableGrim
                && player.cameraEntity.isSelf()
                && !player.inVehicle()
                && player.gamemode != GameMode.SPECTATOR
                && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1)
                && !player.getSetbackTeleportUtil().shouldBlockMovement();
    }

    /**
     * Ray traces from every possible eye/look candidate to the clicked block's hitbox.
     *
     * @param target        the clicked block position
     * @param targetState   the block state the client clicked, snapshotted at packet time
     * @param heldPlacedType placed-type of the held item, for hitboxes that depend on it
     */
    protected Sight trace(Vector3i target, WrappedBlockState targetState, @Nullable StateType heldPlacedType) {
        blockingHit = null;
        blockingDistance = 0;

        // Bounded analyses per tick, so packet spam can't turn ray tracing into a CPU sink.
        // The cap only exempts; it can never produce a flag.
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (tick != analyzedTick) {
            analyzedTick = tick;
            analyzedActions = 0;
        }
        if (++analyzedActions > MAX_ANALYZED_ACTIONS_PER_TICK) return Sight.EXEMPT;

        if (!player.compensatedWorld.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return Sight.EXEMPT;

        int targetSize = HitboxData.getBlockHitbox(
                player,
                heldPlacedType,
                player.getClientVersion(),
                targetState,
                true,
                target.getX(),
                target.getY(),
                target.getZ()).downCast(targetBoxes);
        if (targetSize == 0) return Sight.EXEMPT; // Nothing pickable to aim at, e.g. a desynced air block

        final double[] eyeHeights = player.getPossibleEyeHeights();
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = -Double.MAX_VALUE;
        for (double height : eyeHeights) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        // Expansion only widens the aim test; occlusion is still judged per candidate ray.
        double expansion = hitboxExpansion;
        if (!player.packetStateData.didLastMovementIncludePosition || player.canSkipTicks()) {
            expansion += player.getMovementThreshold();
        }

        // A player standing inside the block can pick its far side; never our invariant.
        SimpleCollisionBox eyePositions = new SimpleCollisionBox(
                Math.min(player.x, player.lastX), Math.min(player.y, player.lastY) + minEyeHeight, Math.min(player.z, player.lastZ),
                Math.max(player.x, player.lastX), Math.max(player.y, player.lastY) + maxEyeHeight, Math.max(player.z, player.lastZ));
        eyePositions.expand(player.getMovementThreshold());
        for (int i = 0; i < targetSize; i++) {
            if (eyePositions.isIntersected(targetBoxes[i].copy().expand(expansion))) return Sight.CLEAR;
        }

        double range = Math.min(MAX_TRACE_DISTANCE,
                player.compensatedEntities.self.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1);

        // 1.7 clients are always on the latest look, 1.8 can be a rotation behind, 1.9+ can also skip ticks.
        int lookCount = player.getClientVersion().isOlderThan(ClientVersion.V_1_8) ? 1
                : player.getClientVersion().isOlderThan(ClientVersion.V_1_9) ? 2 : 3;

        boolean aimed = false;
        boolean unloaded = false;
        HitData closestBlock = null;
        double closestBlockDistance = Double.MAX_VALUE;

        for (int positionIndex = 0; positionIndex < 2; positionIndex++) {
            double x = positionIndex == 0 ? player.x : player.lastX;
            double y = positionIndex == 0 ? player.y : player.lastY;
            double z = positionIndex == 0 ? player.z : player.lastZ;

            for (int lookIndex = 0; lookIndex < lookCount; lookIndex++) {
                float yaw = lookIndex == 0 ? player.yaw : player.lastYaw;
                float pitch = lookIndex == 2 ? player.lastPitch : player.pitch;
                Vector3dm direction = ReachUtils.getLook(player, yaw, pitch);

                for (double eyeHeight : eyeHeights) {
                    Vector3d eye = new Vector3d(x, y + eyeHeight, z);
                    Vector3d end = eye.add(
                            direction.getX() * range,
                            direction.getY() * range,
                            direction.getZ() * range);

                    Vector3d targetHit = null;
                    double targetHitDistance = Double.MAX_VALUE;
                    for (int i = 0; i < targetSize; i++) {
                        SimpleCollisionBox box = targetBoxes[i].copy().expand(expansion);
                        Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, eye, end);
                        if (intercept.first() == null) continue;

                        double distance = eye.distance(intercept.first());
                        if (distance < targetHitDistance) {
                            targetHitDistance = distance;
                            targetHit = intercept.first();
                        }
                    }
                    if (targetHit == null) continue; // This candidate never aimed at the block
                    aimed = true;

                    HitData blocking = findBlockingHit(eye, targetHit, target);
                    if (blocking == UNLOADED) {
                        unloaded = true;
                        continue;
                    } else if (blocking == null) {
                        return Sight.CLEAR;
                    }

                    double distance = Vector3dm.from(eye).distance(blocking.blockHitLocation());
                    if (distance < closestBlockDistance) {
                        closestBlockDistance = distance;
                        closestBlock = blocking;
                    }
                }
            }
        }

        if (unloaded) return Sight.EXEMPT;
        if (!aimed || closestBlock == null) return Sight.NO_AIM;

        blockingHit = closestBlock;
        blockingDistance = closestBlockDistance;
        return Sight.BLOCKED;
    }

    private @Nullable HitData findBlockingHit(Vector3d eye, Vector3d targetHit, Vector3i target) {
        final double targetDistance = eye.distance(targetHit);
        // The ray starts inside this block; its exit face is not something the client could see.
        final int eyeX = GrimMath.floor(eye.getX());
        final int eyeY = GrimMath.floor(eye.getY());
        final int eyeZ = GrimMath.floor(eye.getZ());

        return WorldRayTrace.traverseBlocks(player, eye, targetHit, (state, pos) -> {
            if (pos.getX() == target.getX() && pos.getY() == target.getY() && pos.getZ() == target.getZ()) return null;
            if (pos.getX() == eyeX && pos.getY() == eyeY && pos.getZ() == eyeZ) return null;
            if (!player.compensatedWorld.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return UNLOADED;

            if (!strict) {
                // Lenient mode only trusts blocks that also stop movement, so mismodelled
                // outline-only shapes (plants, carpets, ViaVersion replacements) can't false.
                CollisionBox movementCollision = CollisionData.getData(state.getType())
                        .getMovementCollisionBox(
                                player,
                                player.getClientVersion(),
                                state,
                                pos.getX(),
                                pos.getY(),
                                pos.getZ());
                if (movementCollision.isNull()) return null;
            }

            // Use ray-pick geometry only after confirming this block occludes at all.
            CollisionBox collision = HitboxData.getBlockHitbox(
                    player,
                    null,
                    player.getClientVersion(),
                    state,
                    false,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ());
            int size = collision.downCast(occluderBoxes);
            double closestDistance = Double.MAX_VALUE;
            Vector3d closestHit = null;
            BlockFace closestFace = null;

            for (int i = 0; i < size; i++) {
                SimpleCollisionBox box = occluderBoxes[i];
                Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, eye, targetHit);
                if (intercept.first() == null) continue;
                double distance = eye.distance(intercept.first());
                if (distance <= BLOCK_HIT_EPSILON
                        || distance + BLOCK_HIT_EPSILON >= targetDistance
                        || distance >= closestDistance) {
                    continue;
                }

                closestDistance = distance;
                closestHit = intercept.first();
                closestFace = intercept.second();
            }

            return closestHit == null
                    ? null
                    : new HitData(pos, Vector3dm.from(closestHit), closestFace, state);
        });
    }

    /** Only valid right after {@link #trace} returned {@link Sight#BLOCKED}. */
    protected boolean flagBlocked(Vector3i target, boolean preFlying) {
        HitData hit = blockingHit;
        if (hit == null) return false;
        Vector3i pos = hit.position();
        var writer = V.write(verbose())
                .sint(VerboseCodecs.block(hit.state().getType(), player.getClientVersion()))
                .mcPos(pos.getX(), pos.getY(), pos.getZ())
                .mcPos(target.getX(), target.getY(), target.getZ())
                .f64(blockingDistance)
                .bool(preFlying);
        // The pre-flying pass cancels the action outright, so enforcement is already done there.
        // A post-flying detection arrives after the action was applied; a setback is the only
        // enforcement left, and without it these flags are logged but never acted on.
        return preFlying ? flag(writer) : flagWithSetback(writer);
    }
}
