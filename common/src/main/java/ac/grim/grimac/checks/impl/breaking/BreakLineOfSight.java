package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.BlockLineOfSightCheck;
import ac.grim.grimac.checks.type.PostFlyingBlockBreakListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;

@CheckData(
        name = "BreakLineOfSight",
        stableKey = "grim.breaking.break_line_of_sight",
        description = "Tried to break a block through another block",
        setback = -1)
public class BreakLineOfSight extends BlockLineOfSightCheck implements BlockBreakListener, PostFlyingBlockBreakListener {

    public BreakLineOfSight(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        ignorePost = false;
        if (!canCheck(blockBreak)) return;

        Sight sight = trace(blockBreak.position, blockBreak.block, heldPlacedType());
        if (sight == Sight.CLEAR) {
            ignorePost = true;
            reward();
        } else if (sight == Sight.BLOCKED) {
            ignorePost = true;
            if (flagBlocked(blockBreak.position, true) && shouldCancelAction()) {
                blockBreak.cancel();
            }
        }
        // NO_AIM/EXEMPT here can still be a tick-behind look; the post-flying pass re-judges it.
    }

    // Post-flying has the reconciled position and look, but it is too late to cancel.
    @Override
    public void onPostFlyingBlockBreak(BlockBreak blockBreak) {
        if (ignorePost) {
            ignorePost = false;
            return;
        }
        if (!canCheck(blockBreak)) return;

        if (trace(blockBreak.position, blockBreak.block, heldPlacedType()) == Sight.BLOCKED) {
            flagBlocked(blockBreak.position, false);
        }
    }

    private StateType heldPlacedType() {
        return player.inventory.getHeldItem().getType().getPlacedType();
    }

    private boolean canCheck(BlockBreak blockBreak) {
        // Cancelling a dig carries no reliable look; RotationBreak exempts it for the same reason.
        return blockBreak.action != DiggingAction.CANCELLED_DIGGING && canCheck();
    }
}
