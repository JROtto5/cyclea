package com.otto.cyclea.mixin;

import com.otto.cyclea.feature.CycleaXray;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The X-ray hook. When {@link CycleaXray} is on, any block not on the ore whitelist
 * reports itself as invisible and non-occluding, so the world mesher (vanilla AND
 * Sodium, which read these same block methods) simply doesn't draw it and doesn't
 * cull the ore faces behind it. Every branch early-outs the instant X-ray is off.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class BlockStateBaseMixin {

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void cyclea$xrayShape(CallbackInfoReturnable<RenderShape> cir) {
        if (CycleaXray.hidden((BlockState) (Object) this)) {
            cir.setReturnValue(RenderShape.INVISIBLE);
        }
    }

    @Inject(method = "canOcclude", at = @At("HEAD"), cancellable = true)
    private void cyclea$xrayOcclude(CallbackInfoReturnable<Boolean> cir) {
        if (CycleaXray.hidden((BlockState) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isSolidRender", at = @At("HEAD"), cancellable = true)
    private void cyclea$xraySolid(CallbackInfoReturnable<Boolean> cir) {
        if (CycleaXray.hidden((BlockState) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
