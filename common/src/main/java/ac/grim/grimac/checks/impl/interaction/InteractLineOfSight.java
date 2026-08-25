package ac.grim.grimac.checks.impl.interaction;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockLineOfSightCheck;
import ac.grim.grimac.checks.type.BlockPlaceListener;
import ac.grim.grimac.checks.type.PostFlyingBlockPlaceListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;

@CheckData(
        name = "InteractLineOfSight",
        stableKey = "grim.interaction.interact_line_of_sight",
        description = "Interacted with a block through another block",
        setback = -1)
public class InteractLineOfSight extends BlockLineOfSightCheck implements BlockPlaceListener, PostFlyingBlockPlaceListener {

    public InteractLineOfSight(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        ignorePost = false;
        if (!canCheck(place)) return;

        Sight sight = trace(place.position, player.compensatedWorld.getBlock(place.position), null);
        if (sight == Sight.CLEAR) {
            ignorePost = true;
            reward();
        } else if (sight == Sight.BLOCKED) {
            ignorePost = true;
            if (flagBlocked(place.position, true) && shouldCancelAction()) {
                place.resync(); // Deny the interaction
            }
        }
        // NO_AIM/EXEMPT here can still be a tick-behind look; the post-flying pass re-judges it.
    }

    // Post-flying has the reconciled position and look, but it is too late to cancel.
    @Override
    public void onPostFlyingBlockPlace(BlockPlace place) {
        if (ignorePost) {
            ignorePost = false;
            return;
        }
        if (!canCheck(place)) return;

        if (trace(place.position, player.compensatedWorld.getBlock(place.position), null) == Sight.BLOCKED) {
            flagBlocked(place.position, false);
        }
    }

    private boolean canCheck(BlockPlace place) {
        // Items with a placed type go through PlaceLineOfSight; everything else is an interaction
        // or an item use against a block face, and needs the same line of sight.
        return !place.isBlock && canCheck();
    }
}
