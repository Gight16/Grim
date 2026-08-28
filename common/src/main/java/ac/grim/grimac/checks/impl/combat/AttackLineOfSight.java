package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.dragon.PacketEntityEnderDragonPart;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "AttackLineOfSight",
        stableKey = "grim.combat.attack_line_of_sight",
        description = "Attacked an entity outside the player's line of sight",
        setback = 0)
public class AttackLineOfSight extends Check implements PacketReceiveListener {
    private static final int AIM_MISS = 0;
    private static final int BLOCKED = 1;
    private static final int MAX_ANALYZED_ATTACKS = 16;
    private static final double MAX_TRACE_DISTANCE = 16;
    private static final double DIRECTION_EPSILON = 1.0E-12;
    private static final double BLOCK_HIT_EPSILON = 1.0E-7;
    private static final HitData UNLOADED = new HitData(null, null, null, null);
    private static final Verbose V = Verbose.of("reason=miss, type={entity}")
            .or("reason=block, type={entity}, block={block}, pos={mcpos}, distance={f64:%.3f}");

    // Fractions of the target box's extent sampled by the full-occlusion test; center first so a
    // plainly visible target exits after a single ray.
    private static final double[] OCCLUSION_SAMPLE_FACTORS = {0.5, 0.05, 0.95};

    private final Int2ObjectMap<AttackPosition> attackQueue = new Int2ObjectOpenHashMap<>();
    private int analyzedAttacks;
    private final SimpleCollisionBox[] blockCollisionBoxes =
            new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];
    // Set by findBlockingHit when a ray is only blocked by the server-side state of an openable
    // whose client-side toggle the server has not confirmed yet.
    private HitData unconfirmedHit;
    private boolean blockInvalidHits;
    private boolean blockUnconfirmedOpenables;
    private boolean strict;
    private int cancelVL;
    private double hitboxExpansion;

    public AttackLineOfSight(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isEnabled() || player.disableGrim) {
            attackQueue.clear();
            analyzedAttacks = 0;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            queueAttack(event, new WrapperPlayClientAttack(event).getEntityId());
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                queueAttack(event, packet.getEntityId());
            }
        }

        // Match Reach's delayed processing so latest valid look and interpolation bounds are available.
        if (isUpdate(event.getPacketType())) {
            checkQueuedAttacks();
        }
    }

    private void queueAttack(PacketReceiveEvent event, int entityId) {
        if (event.isCancelled()) return;

        // A setback in progress means the claimed position is already rejected; forwarding
        // unanalyzed attacks here would turn every setback into a free-hit window.
        if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
            cancelUnanalyzed(event);
            return;
        }

        // Every attack is analyzed, including repeats on the same target — a repeat forwarded on
        // the first packet's verdict would sail through a hit that was just cancelled. The budget
        // bounds ray tracing per flush so packet spam cannot become a CPU sink, and no vanilla
        // client sends this many attacks between movement updates, so exceeding it is cancelled
        // instead of forwarded: the cap must not be a bypass either.
        if (analyzedAttacks >= MAX_ANALYZED_ATTACKS) {
            cancelUnanalyzed(event);
            return;
        }

        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        if (!canCheck(entity)) return;

        // Attack-time geometry is unreliable around teleports. Strict mode refuses to forward
        // what it cannot analyze; lenient mode keeps the exemption.
        if (player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1) || entity.hasRecentlyTeleported()) {
            if (strict) cancelUnanalyzed(event);
            return;
        }
        analyzedAttacks++;

        AttackPosition attack = new AttackPosition(
                player.x, player.y, player.z,
                player.lastX, player.lastY, player.lastZ);

        // Snapshot attack-time world/entity state. Delayed retry only adds lenience for packet-order ambiguity.
        SimpleCollisionBox targetBox = getTargetBox(entity);
        LineOfSightResult initialResult = checkLineOfSight(targetBox, attack);
        if (initialResult.unloaded()
                || initialResult.hitTarget() && initialResult.blockingHit() == null && !initialResult.unconfirmed()) {
            attackQueue.remove(entityId);
            reward();
            return;
        }

        if (initialResult.hitTarget()) {
            if (initialResult.unconfirmed()) {
                // Only the unconfirmed server-side state of a client-toggled openable blocks this
                // hit. It becomes legal if the server accepts the toggle, so cancel without a flag.
                if (blockInvalidHits && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else if (flagBlocked(entity, initialResult, false) && shouldCancelHit()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        // No candidate ray touched the target box. When the whole box is hidden from every possible
        // eye position, no look packet arriving later can make this hit legal either; drop it now
        // instead of forwarding it and enforcing retroactively. Cancel only: the sampled visibility
        // test is not exhaustive enough to be flag evidence.
        if (strict && blockInvalidHits && shouldModifyPackets() && isFullyOccluded(targetBox, attack)) {
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }

        attackQueue.put(entityId, attack);
    }

    private void checkQueuedAttacks() {
        for (Int2ObjectMap.Entry<AttackPosition> attack : attackQueue.int2ObjectEntrySet()) {
            PacketEntity entity = player.compensatedEntities.entityMap.get(attack.getIntKey());
            if (!canCheck(entity) || entity.hasRecentlyTeleported()
                    || player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1)) {
                continue;
            }

            LineOfSightResult result = checkLineOfSight(getTargetBox(entity), attack.getValue());
            if (result.unloaded() || result.unconfirmed()) {
                // Unloaded chunks, or a pending openable toggle the hit may become legal through.
                continue;
            } else if (result.hitTarget() && result.blockingHit() == null) {
                reward();
            } else if (result.hitTarget()) {
                flagBlocked(entity, result, true);
            } else {
                // The ATTACK packet is long gone by now, so a setback is the only enforcement left.
                flagWithSetback(V.write(verbose(), AIM_MISS)
                        .uint(VerboseCodecs.entity(entity.getType(), player.getClientVersion())));
            }
        }

        attackQueue.clear();
        analyzedAttacks = 0;
    }

    /**
     * @param retroactive the ATTACK packet has already been forwarded, so cancellation is no longer
     *                    possible and the flag must fall back to a setback to enforce anything
     */
    private boolean flagBlocked(PacketEntity entity, LineOfSightResult result, boolean retroactive) {
        HitData hit = result.blockingHit();
        Vector3i pos = hit.position();
        var writer = V.write(verbose(), BLOCKED)
                .uint(VerboseCodecs.entity(entity.getType(), player.getClientVersion()))
                .sint(VerboseCodecs.block(hit.state().getType(), player.getClientVersion()))
                .mcPos(pos.getX(), pos.getY(), pos.getZ())
                .f64(result.blockDistance());
        return retroactive ? flagWithSetback(writer) : flag(writer);
    }

    private boolean canCheck(PacketEntity entity) {
        if (entity == null || entity instanceof PacketEntityEnderDragonPart) return false;
        if (!entity.canHit() || entity.isDead || entity.riding != null) return false;
        if ((!entity.isLivingEntity || entity.getType() == EntityTypes.SHULKER)
                && entity.getType() != EntityTypes.END_CRYSTAL) {
            return false;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) return false;
        if (player.inVehicle()) return false;

        // Match Reach's ViaVersion hitbox exemptions where client geometry is not reliable.
        if (entity.getType() == EntityTypes.ARMOR_STAND
                && player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
            return false;
        }
        return entity.getType() != EntityTypes.HAPPY_GHAST
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6);
    }

    private SimpleCollisionBox getTargetBox(PacketEntity entity) {
        // PacketEntity already combines every possible interpolation/history position into this box.
        SimpleCollisionBox targetBox = entity.getPossibleCollisionBoxes().copy();
        double expansion = hitboxExpansion;
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            expansion += 0.1;
        }
        if (!player.packetStateData.didLastLastMovementIncludePosition || player.canSkipTicks()) {
            expansion += player.getMovementThreshold();
        }
        return targetBox.expand(expansion);
    }

    private LineOfSightResult checkLineOfSight(SimpleCollisionBox targetBox, AttackPosition attack) {
        // Cap only exempts analysis. It cannot turn distance into a flag.
        if (minimumDistanceSquared(targetBox, attack) > MAX_TRACE_DISTANCE * MAX_TRACE_DISTANCE) {
            return new LineOfSightResult(true, null, 0, false, false);
        }

        boolean hitTarget = false;
        boolean sawUnconfirmed = false;
        HitData closestBlock = null;
        double closestBlockDistance = Double.MAX_VALUE;
        int lookCount = player.getClientVersion().isOlderThan(ClientVersion.V_1_8) ? 1
                : player.getClientVersion().isOlderThan(ClientVersion.V_1_9) ? 2 : 3;

        // Current and previous positions, rotations, eye heights, and Grim's combined interpolation
        // hitbox make packet-order ambiguity permissive: any unobstructed candidate accepts the hit.
        for (int positionIndex = 0; positionIndex < 2; positionIndex++) {
            double x = positionIndex == 0 ? attack.x() : attack.lastX();
            double y = positionIndex == 0 ? attack.y() : attack.lastY();
            double z = positionIndex == 0 ? attack.z() : attack.lastZ();

            for (int lookIndex = 0; lookIndex < lookCount; lookIndex++) {
                float yaw = lookIndex == 0 ? player.yaw : player.lastYaw;
                float pitch = lookIndex == 2 ? player.lastPitch : player.pitch;
                Vector3dm direction = ReachUtils.getLook(player, yaw, pitch);

                for (double eyeHeight : player.getPossibleEyeHeights()) {
                    Vector3d eye = new Vector3d(x, y + eyeHeight, z);
                    double intersection = rayIntersectionDistance(targetBox, eye, direction);
                    if (Double.isNaN(intersection)) continue;
                    hitTarget = true;

                    // Analysis cap only exempts distant targets; it never flags or enforces reach.
                    if (intersection > MAX_TRACE_DISTANCE) {
                        return new LineOfSightResult(true, null, 0, false, false);
                    }

                    // No reach threshold: ray ends only where it first enters target hitbox.
                    Vector3d targetHit = eye.add(
                            direction.getX() * intersection,
                            direction.getY() * intersection,
                            direction.getZ() * intersection);
                    HitData blockingHit = findBlockingHit(eye, targetHit);
                    if (blockingHit == UNLOADED) {
                        return new LineOfSightResult(true, null, 0, true, false);
                    } else if (blockingHit == null) {
                        if (unconfirmedHit == null) {
                            return new LineOfSightResult(true, null, 0, false, false);
                        }
                        // Blocked only by an unconfirmed openable toggle: this candidate neither
                        // exonerates the hit nor proves it impossible.
                        sawUnconfirmed = true;
                        continue;
                    }

                    double blockDistance = Vector3dm.from(eye).distance(blockingHit.blockHitLocation());
                    if (blockDistance < closestBlockDistance) {
                        closestBlock = blockingHit;
                        closestBlockDistance = blockDistance;
                    }
                }
            }
        }

        return new LineOfSightResult(hitTarget, closestBlock, closestBlockDistance, false, sawUnconfirmed);
    }

    private double minimumDistanceSquared(SimpleCollisionBox box, AttackPosition attack) {
        double minimum = Double.MAX_VALUE;
        for (int positionIndex = 0; positionIndex < 2; positionIndex++) {
            double x = positionIndex == 0 ? attack.x() : attack.lastX();
            double y = positionIndex == 0 ? attack.y() : attack.lastY();
            double z = positionIndex == 0 ? attack.z() : attack.lastZ();
            double deltaX = Math.max(Math.max(box.minX - x, 0), x - box.maxX);
            double deltaZ = Math.max(Math.max(box.minZ - z, 0), z - box.maxZ);

            for (double eyeHeight : player.getPossibleEyeHeights()) {
                double eyeY = y + eyeHeight;
                double deltaY = Math.max(Math.max(box.minY - eyeY, 0), eyeY - box.maxY);
                minimum = Math.min(minimum,
                        deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            }
        }
        return minimum;
    }

    private double rayIntersectionDistance(
            SimpleCollisionBox box,
            Vector3d origin,
            Vector3dm direction) {
        double min = 0;
        double max = Double.POSITIVE_INFINITY;

        double directionX = direction.getX();
        if (Math.abs(directionX) < DIRECTION_EPSILON) {
            if (origin.getX() < box.minX || origin.getX() > box.maxX) return Double.NaN;
        } else {
            double first = (box.minX - origin.getX()) / directionX;
            double second = (box.maxX - origin.getX()) / directionX;
            min = Math.max(min, Math.min(first, second));
            max = Math.min(max, Math.max(first, second));
            if (max < min) return Double.NaN;
        }

        double directionY = direction.getY();
        if (Math.abs(directionY) < DIRECTION_EPSILON) {
            if (origin.getY() < box.minY || origin.getY() > box.maxY) return Double.NaN;
        } else {
            double first = (box.minY - origin.getY()) / directionY;
            double second = (box.maxY - origin.getY()) / directionY;
            min = Math.max(min, Math.min(first, second));
            max = Math.min(max, Math.max(first, second));
            if (max < min) return Double.NaN;
        }

        double directionZ = direction.getZ();
        if (Math.abs(directionZ) < DIRECTION_EPSILON) {
            if (origin.getZ() < box.minZ || origin.getZ() > box.maxZ) return Double.NaN;
        } else {
            double first = (box.minZ - origin.getZ()) / directionZ;
            double second = (box.maxZ - origin.getZ()) / directionZ;
            min = Math.max(min, Math.min(first, second));
            max = Math.min(max, Math.max(first, second));
            if (max < min) return Double.NaN;
        }

        return max < 0 ? Double.NaN : min;
    }

    private HitData findBlockingHit(Vector3d start, Vector3d targetHit) {
        double targetDistance = start.distance(targetHit);
        int eyeX = GrimMath.floor(start.getX());
        int eyeY = GrimMath.floor(start.getY());
        int eyeZ = GrimMath.floor(start.getZ());
        unconfirmedHit = null;

        return WorldRayTrace.traverseBlocks(player, start, targetHit, (state, pos) -> {
            if (!player.compensatedWorld.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return UNLOADED;
            }

            HitData hit = occlusionHit(state, pos, start, targetHit, targetDistance, eyeX, eyeY, eyeZ);
            if (hit != null) return hit;

            // The client toggled this openable and the server has not confirmed it; a hit that is
            // only clear through the predicted state must also survive the server's state.
            if (blockUnconfirmedOpenables && unconfirmedHit == null) {
                WrappedBlockState original = player.compensatedWorld
                        .getUnconfirmedOpenableOriginal(pos.getX(), pos.getY(), pos.getZ());
                if (original != null && original.getGlobalId() != state.getGlobalId()) {
                    unconfirmedHit = occlusionHit(original, pos, start, targetHit, targetDistance, eyeX, eyeY, eyeZ);
                }
            }
            return null;
        });
    }

    private HitData occlusionHit(
            WrappedBlockState state,
            Vector3i pos,
            Vector3d start,
            Vector3d targetHit,
            double targetDistance,
            int eyeX,
            int eyeY,
            int eyeZ) {
        CollisionBox movementCollision = CollisionData.getData(state.getType())
                .getMovementCollisionBox(
                        player,
                        player.getClientVersion(),
                        state,
                        pos.getX(),
                        pos.getY(),
                        pos.getZ());
        if (movementCollision.isNull()) {
            // Lenient mode only trusts blocks that also stop movement, so mismodelled
            // outline-only shapes (plants, carpets, ViaVersion replacements) can't false.
            if (!strict) return null;
            // An outline-only shape sharing the eye's block cannot occlude a vanilla pick ray.
            if (pos.getX() == eyeX && pos.getY() == eyeY && pos.getZ() == eyeZ) return null;
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
        int size = collision.downCast(blockCollisionBoxes);
        double closestDistance = Double.MAX_VALUE;
        Vector3d closestHit = null;
        BlockFace closestFace = null;

        for (int i = 0; i < size; i++) {
            SimpleCollisionBox box = blockCollisionBoxes[i];
            Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, start, targetHit);
            if (intercept.first() == null) continue;
            double distance = start.distance(intercept.first());
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
    }

    /**
     * True when every sampled point of the target box is behind a confirmed occluder from every
     * possible eye candidate. Sampling can miss thin gaps, so callers may only cancel on this,
     * never flag.
     */
    private boolean isFullyOccluded(SimpleCollisionBox targetBox, AttackPosition attack) {
        for (int positionIndex = 0; positionIndex < 2; positionIndex++) {
            double x = positionIndex == 0 ? attack.x() : attack.lastX();
            double y = positionIndex == 0 ? attack.y() : attack.lastY();
            double z = positionIndex == 0 ? attack.z() : attack.lastZ();

            for (double eyeHeight : player.getPossibleEyeHeights()) {
                Vector3d eye = new Vector3d(x, y + eyeHeight, z);

                for (double factorX : OCCLUSION_SAMPLE_FACTORS) {
                    for (double factorY : OCCLUSION_SAMPLE_FACTORS) {
                        for (double factorZ : OCCLUSION_SAMPLE_FACTORS) {
                            // Corners + center: the corner samples sit slightly inset so a ray
                            // grazing a coplanar occluder face cannot mask real visibility.
                            boolean corner = factorX != 0.5 && factorY != 0.5 && factorZ != 0.5;
                            boolean center = factorX == 0.5 && factorY == 0.5 && factorZ == 0.5;
                            if (!corner && !center) continue;

                            Vector3d point = new Vector3d(
                                    targetBox.minX + factorX * (targetBox.maxX - targetBox.minX),
                                    targetBox.minY + factorY * (targetBox.maxY - targetBox.minY),
                                    targetBox.minZ + factorZ * (targetBox.maxZ - targetBox.minZ));
                            // Beyond the analysis cap no conclusion is possible; never treat the
                            // cap as occlusion evidence.
                            if (eye.distance(point) > MAX_TRACE_DISTANCE) return false;

                            HitData hit = findBlockingHit(eye, point);
                            // Visible, unloaded, or blocked only by an unconfirmed openable
                            // toggle: not proof the whole box is hidden.
                            if (hit == null || hit == UNLOADED) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // Cancellation is instant: the offending ATTACK packet is dropped in the same receive call.
    private boolean shouldCancelHit() {
        return blockInvalidHits && cancelVL >= 0 && violations >= cancelVL && shouldModifyPackets();
    }

    // For attacks that cannot be analyzed at all: dropped without a flag, since inability to
    // analyze is not evidence of cheating.
    private void cancelUnanalyzed(PacketReceiveEvent event) {
        if (blockInvalidHits && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        blockInvalidHits = config.getBooleanElse("AttackLineOfSight.block-invalid-hits", true);
        blockUnconfirmedOpenables = config.getBooleanElse("AttackLineOfSight.block-unconfirmed-openables", true);
        strict = config.getBooleanElse("AttackLineOfSight.strict", true);
        cancelVL = config.getIntElse("AttackLineOfSight.cancelvl", 0);
        hitboxExpansion = Math.max(0, config.getDoubleElse("AttackLineOfSight.hitbox-expansion", 0.03));
    }

    private record AttackPosition(
            double x,
            double y,
            double z,
            double lastX,
            double lastY,
            double lastZ) {}

    private record LineOfSightResult(
            boolean hitTarget,
            HitData blockingHit,
            double blockDistance,
            boolean unloaded,
            boolean unconfirmed) {}
}
