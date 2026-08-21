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
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "AttackLineOfSight",
        stableKey = "grim.combat.attack_line_of_sight",
        description = "Attacked an entity outside the player's line of sight",
        setback = -1,
        experimental = true)
public class AttackLineOfSight extends Check implements PacketReceiveListener {
    private static final int AIM_MISS = 0;
    private static final int BLOCKED = 1;
    private static final int MAX_ANALYZED_TARGETS = 10;
    private static final double MAX_TRACE_DISTANCE = 16;
    private static final double DIRECTION_EPSILON = 1.0E-12;
    private static final double BLOCK_HIT_EPSILON = 1.0E-7;
    private static final HitData UNLOADED = new HitData(null, null, null, null);
    private static final Verbose V = Verbose.of("reason=miss, type={entity}")
            .or("reason=block, type={entity}, block={block}, pos={mcpos}, distance={f64:%.3f}");

    private final Int2ObjectMap<QueuedAttack> attackQueue = new Int2ObjectOpenHashMap<>();
    private final IntSet analyzedTargets = new IntOpenHashSet();
    private final SimpleCollisionBox[] blockCollisionBoxes =
            new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];
    private boolean blockInvalidHits;
    private double hitboxExpansion;

    public AttackLineOfSight(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isEnabled() || !player.isExperimentalChecks() || player.disableGrim) {
            attackQueue.clear();
            analyzedTargets.clear();
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
        if (event.isCancelled() || player.getSetbackTeleportUtil().shouldBlockMovement()) return;
        if (player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1)) return;
        // One analysis per target and bounded distinct targets per tick prevents attack-spam CPU abuse.
        if (analyzedTargets.contains(entityId) || analyzedTargets.size() >= MAX_ANALYZED_TARGETS) return;

        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        if (!canCheck(entity) || entity.hasRecentlyTeleported()) return;
        analyzedTargets.add(entityId);

        AttackPosition attack = new AttackPosition(
                player.x, player.y, player.z,
                player.lastX, player.lastY, player.lastZ);

        // Snapshot attack-time world/entity state. Delayed retry only adds lenience for packet-order ambiguity.
        LineOfSightResult initialResult = checkLineOfSight(getTargetBox(entity), attack);
        if (initialResult.unloaded()
                || initialResult.hitTarget() && initialResult.blockingHit() == null) {
            attackQueue.remove(entityId);
            reward();
            return;
        }

        if (initialResult.hitTarget()) {
            if (flagBlocked(entity, initialResult) && blockInvalidHits && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        attackQueue.put(entityId, new QueuedAttack(attack, initialResult));
    }

    private void checkQueuedAttacks() {
        for (Int2ObjectMap.Entry<QueuedAttack> attack : attackQueue.int2ObjectEntrySet()) {
            PacketEntity entity = player.compensatedEntities.entityMap.get(attack.getIntKey());
            if (!canCheck(entity) || entity.hasRecentlyTeleported()
                    || player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(1)) {
                continue;
            }

            QueuedAttack queued = attack.getValue();
            LineOfSightResult result = checkLineOfSight(getTargetBox(entity), queued.position());
            if (result.unloaded()) {
                continue;
            } else if (result.hitTarget() && result.blockingHit() == null) {
                reward();
            } else if (queued.initialResult().hitTarget()) {
                flagBlocked(entity, queued.initialResult());
            } else if (result.hitTarget()) {
                flagBlocked(entity, result);
            } else {
                flag(V.write(verbose(), AIM_MISS)
                        .uint(VerboseCodecs.entity(entity.getType(), player.getClientVersion())));
            }
        }

        attackQueue.clear();
        analyzedTargets.clear();
    }

    private boolean flagBlocked(PacketEntity entity, LineOfSightResult result) {
        HitData hit = result.blockingHit();
        Vector3i pos = hit.position();
        return flag(V.write(verbose(), BLOCKED)
                .uint(VerboseCodecs.entity(entity.getType(), player.getClientVersion()))
                .sint(VerboseCodecs.block(hit.state().getType(), player.getClientVersion()))
                .mcPos(pos.getX(), pos.getY(), pos.getZ())
                .f64(result.blockDistance()));
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
            return new LineOfSightResult(true, null, 0, false);
        }

        boolean hitTarget = false;
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
                        return new LineOfSightResult(true, null, 0, false);
                    }

                    // No reach threshold: ray ends only where it first enters target hitbox.
                    Vector3d targetHit = eye.add(
                            direction.getX() * intersection,
                            direction.getY() * intersection,
                            direction.getZ() * intersection);
                    HitData blockingHit = findBlockingHit(eye, targetHit);
                    if (blockingHit == UNLOADED) {
                        return new LineOfSightResult(true, null, 0, true);
                    } else if (blockingHit == null) {
                        return new LineOfSightResult(true, null, 0, false);
                    }

                    double blockDistance = Vector3dm.from(eye).distance(blockingHit.blockHitLocation());
                    if (blockDistance < closestBlockDistance) {
                        closestBlock = blockingHit;
                        closestBlockDistance = blockDistance;
                    }
                }
            }
        }

        return new LineOfSightResult(hitTarget, closestBlock, closestBlockDistance, false);
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

        return WorldRayTrace.traverseBlocks(player, start, targetHit, (state, pos) -> {
            if (!player.compensatedWorld.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return UNLOADED;
            }

            CollisionBox movementCollision = CollisionData.getData(state.getType())
                    .getMovementCollisionBox(
                            player,
                            player.getClientVersion(),
                            state,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ());
            if (movementCollision.isNull()) return null;

            // Use ray-pick geometry only after confirming this is a collidable block.
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
        });
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        blockInvalidHits = config.getBooleanElse("AttackLineOfSight.block-invalid-hits", true);
        hitboxExpansion = Math.max(0, config.getDoubleElse("AttackLineOfSight.hitbox-expansion", 0.03));
    }

    private record AttackPosition(
            double x,
            double y,
            double z,
            double lastX,
            double lastY,
            double lastZ) {}

    private record QueuedAttack(AttackPosition position, LineOfSightResult initialResult) {}

    private record LineOfSightResult(
            boolean hitTarget,
            HitData blockingHit,
            double blockDistance,
            boolean unloaded) {}
}
