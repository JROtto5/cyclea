package com.otto.cyclea.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.otto.cyclea.CycleaState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws a bright guide-line from the camera to every located target, plus a
 * short vertical "beacon" spike at each one so it reads at a glance — the same
 * idea as a minimap ping, but rendered straight into the world.
 */
public final class GuideRenderer {

    private GuideRenderer() {
    }

    public static void render(WorldRenderContext ctx) {
        CycleaState state = CycleaState.get();
        if (!state.isActive()) {
            return;
        }
        List<BlockPos> targets = state.getFound();
        if (targets.isEmpty()) {
            return;
        }

        Vec3 cam = ctx.camera().getPosition();
        PoseStack poseStack = ctx.matrixStack();
        if (poseStack == null) {
            return;
        }
        VertexConsumer lines = ctx.consumers().getBuffer(RenderType.lines());

        int rgb = state.getTarget().color;
        float rr = ((rgb >> 16) & 0xFF) / 255f;
        float gg = ((rgb >> 8) & 0xFF) / 255f;
        float bb = (rgb & 0xFF) / 255f;

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        var matrix = poseStack.last().pose();
        var normal = poseStack.last();

        for (BlockPos p : targets) {
            float x = p.getX() + 0.5f;
            float y = p.getY() + 0.5f;
            float z = p.getZ() + 0.5f;

            // guide line: camera -> target
            lines.addVertex(matrix, (float) cam.x, (float) cam.y, (float) cam.z)
                .setColor(rr, gg, bb, 0.9f).setNormal(normal, 0f, 1f, 0f);
            lines.addVertex(matrix, x, y, z)
                .setColor(rr, gg, bb, 0.9f).setNormal(normal, 0f, 1f, 0f);

            // beacon spike upward
            lines.addVertex(matrix, x, y, z)
                .setColor(rr, gg, bb, 1f).setNormal(normal, 0f, 1f, 0f);
            lines.addVertex(matrix, x, y + 3f, z)
                .setColor(rr, gg, bb, 1f).setNormal(normal, 0f, 1f, 0f);
        }

        poseStack.popPose();
    }
}
