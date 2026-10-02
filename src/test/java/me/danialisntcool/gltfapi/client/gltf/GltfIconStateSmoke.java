package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class GltfIconStateSmoke {
    public static void verify() throws Exception {
        int original = GL30.glGenFramebuffers();
        int temporary = GL30.glGenFramebuffers();
        Matrix4f projection = new Matrix4f().ortho(-10, 10, -20, 20, -30, 30);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, original);
            GlStateManager._viewport(11, 13, 17, 19);
            GlStateManager._enableScissorTest();
            RenderSystem.depthFunc(GL11.GL_GREATER);
            GlStateManager._clearColor(0.2F, 0.3F, 0.4F, 0.5F);
            GlStateManager._clearDepth(0.7);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            var constructor = Class.forName("me.danialisntcool.gltfapi.client.gltf.GltfGuiIconCache$CaptureState")
                    .getDeclaredConstructor(boolean.class);
            constructor.setAccessible(true);
            try (AutoCloseable ignored = (AutoCloseable) constructor.newInstance(true)) {
                if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) throw new IllegalStateException("Capture did not disable scissor");
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, temporary);
                GlStateManager._viewport(0, 0, 8, 8);
                RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN);
                RenderSystem.getModelViewStack().translate(100, 200, 300);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                GlStateManager._clearColor(0, 0, 0, 0);
                GlStateManager._clearDepth(1);
            }
            var viewport = stack.mallocInt(4);
            var color = stack.mallocFloat(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, color);
            if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != original
                    || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != original
                    || viewport.get(0) != 11 || viewport.get(1) != 13 || viewport.get(2) != 17 || viewport.get(3) != 19
                    || !GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) || GL11.glGetInteger(GL11.GL_DEPTH_FUNC) != GL11.GL_GREATER
                    || Math.abs(color.get(0) - 0.2F) > 0.00001F || Math.abs(color.get(3) - 0.5F) > 0.00001F
                    || Math.abs(GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE) - 0.7) > 0.00001
                    || !RenderSystem.getProjectionMatrix().equals(projection, 0.00001F)
                    || RenderSystem.getModelViewMatrix().m30() != 0 || GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("Inventory capture leaked render state");
            }
            System.out.println("Inventory capture GPU regression passed: framebuffer, viewport, scissor, matrices and depth/clear state restored");
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GlStateManager._disableScissorTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL30.glDeleteFramebuffers(original);
            GL30.glDeleteFramebuffers(temporary);
        }
    }
}
