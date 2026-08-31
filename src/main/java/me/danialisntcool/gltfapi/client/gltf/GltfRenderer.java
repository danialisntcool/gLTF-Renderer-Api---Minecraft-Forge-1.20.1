package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import me.danialisntcool.gltfapi.client.render.GltfRenderTypes;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class GltfRenderer {
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.fromNamespaceAndPath("forge", "textures/white.png");
    private static final Map<Integer, GltfSampler> APPLIED_SAMPLERS = new HashMap<>();

    private GltfRenderer() {
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay, GltfRenderOptions options) {
        RenderSystem.assertOnRenderThread();
        GltfRenderState state = model.renderState(
                options.animation(), options.animationTimeSeconds(), options.scene(), options.nodeRotationOffsets());
        boolean shaderPack = GltfShaderPackCompat.isShaderPackInUse();
        boolean shadowPass = GltfShaderPackCompat.isRenderingShadowPass();
        if (!model.hasBlendedPrimitives()) {
            for (GltfPrimitive primitive : model.primitives()) {
                if (!state.visibleNodes().get(primitive.nodeIndex())) {
                    continue;
                }
                Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
                for (Matrix4f instance : primitive.instances()) {
                    Matrix4f transform = new Matrix4f(nodeTransform).mul(instance);
                    if (shaderPack || shadowPass) {
                        renderCompatible(model, state, primitive, transform, poseStack, buffers,
                                packedLight, packedOverlay, shadowPass);
                    } else {
                        renderPrimitive(model, state, primitive, transform, poseStack, packedLight);
                    }
                }
            }
            return;
        }
        List<RenderCall> calls = new ArrayList<>();
        for (GltfPrimitive primitive : model.primitives()) {
            if (state.visibleNodes().get(primitive.nodeIndex())) {
                Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
                for (Matrix4f instance : primitive.instances()) {
                    Matrix4f transform = new Matrix4f(nodeTransform).mul(instance);
                    Vector3f center = primitiveCenter(primitive, transform, poseStack.last().pose());
                    calls.add(new RenderCall(primitive, transform, center.lengthSquared()));
                }
            }
        }
        calls.sort(Comparator.comparing((RenderCall call) -> call.primitive().material().alphaMode()
                        == GltfMaterial.AlphaMode.BLEND)
                .thenComparing(call -> call.primitive().material().alphaMode() == GltfMaterial.AlphaMode.BLEND
                        ? -call.distanceSquared() : 0.0F));
        for (RenderCall call : calls) {
            if (shaderPack || shadowPass) {
                renderCompatible(model, state, call.primitive(), call.transform(), poseStack, buffers,
                        packedLight, packedOverlay, shadowPass);
            } else {
                renderPrimitive(model, state, call.primitive(), call.transform(), poseStack, packedLight);
            }
        }
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay) {
        render(model, poseStack, buffers, packedLight, packedOverlay, GltfRenderOptions.DEFAULT);
    }

    public static void renderBuffered(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                                      int packedLight, int packedOverlay, GltfRenderOptions options) {
        RenderSystem.assertOnRenderThread();
        GltfRenderState state = model.renderState(
                options.animation(), options.animationTimeSeconds(), options.scene(), options.nodeRotationOffsets());
        boolean shadowPass = GltfShaderPackCompat.isRenderingShadowPass();
        if (!model.hasBlendedPrimitives()) {
            for (GltfPrimitive primitive : model.primitives()) {
                if (!state.visibleNodes().get(primitive.nodeIndex())) {
                    continue;
                }
                Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
                for (Matrix4f instance : primitive.instances()) {
                    renderCompatible(model, state, primitive, new Matrix4f(nodeTransform).mul(instance),
                            poseStack, buffers, packedLight, packedOverlay, shadowPass);
                }
            }
            return;
        }
        List<RenderCall> calls = new ArrayList<>();
        for (GltfPrimitive primitive : model.primitives()) {
            if (!state.visibleNodes().get(primitive.nodeIndex())) {
                continue;
            }
            Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
            for (Matrix4f instance : primitive.instances()) {
                Matrix4f transform = new Matrix4f(nodeTransform).mul(instance);
                Vector3f center = primitiveCenter(primitive, transform, poseStack.last().pose());
                calls.add(new RenderCall(primitive, transform, center.lengthSquared()));
            }
        }
        calls.sort(Comparator.comparing((RenderCall call) -> call.primitive().material().alphaMode()
                        == GltfMaterial.AlphaMode.BLEND)
                .thenComparing(call -> call.primitive().material().alphaMode() == GltfMaterial.AlphaMode.BLEND
                        ? -call.distanceSquared() : 0.0F));
        for (RenderCall call : calls) {
            renderCompatible(model, state, call.primitive(), call.transform(), poseStack, buffers,
                    packedLight, packedOverlay, shadowPass);
        }
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        render(model, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
    }

    public static boolean usesCompatibilityPath() {
        return GltfShaderPackCompat.isShaderPackInUse() || GltfShaderPackCompat.isRenderingShadowPass();
    }

    public static boolean isRenderingShadowPass() {
        return GltfShaderPackCompat.isRenderingShadowPass();
    }

    private static void renderPrimitive(GltfModel model, GltfRenderState state,
                                        GltfPrimitive primitive, Matrix4f transform,
                                        PoseStack poseStack, int packedLight) {
        GltfMaterial material = primitive.material();
        ShaderInstance shader = GltfShaders.modelShader();

        poseStack.pushPose();
        poseStack.mulPoseMatrix(transform);
        Matrix3f normalMatrix = new Matrix3f(poseStack.last().pose()).invert().transpose();

        RenderSystem.setShader(() -> shader);
        bindTexture(0, material.baseColorTexture());
        bindTexture(1, material.metallicRoughnessTexture());
        bindTexture(3, material.normalTexture());
        bindTexture(4, material.occlusionTexture());
        bindTexture(5, material.emissiveTexture());
        RenderSystem.enableDepthTest();
        if (material.doubleSided()) {
            RenderSystem.disableCull();
        } else {
            RenderSystem.enableCull();
        }
        if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }

        shader.getUniform("NormalMat").set(normalMatrix);
        shader.getUniform("LightUV").set(packedLight & 65535, packedLight >>> 16 & 65535);
        shader.getUniform("AlphaCutoff").set(
                material.alphaMode() == GltfMaterial.AlphaMode.MASK ? material.alphaCutoff() : 0.0F);
        shader.getUniform("HasBaseColorTexture").set(material.baseColorTexture() == null ? 0 : 1);
        shader.getUniform("HasMetallicRoughnessTexture").set(material.metallicRoughnessTexture() == null ? 0 : 1);
        shader.getUniform("HasNormalTexture").set(material.normalTexture() == null ? 0 : 1);
        shader.getUniform("HasOcclusionTexture").set(material.occlusionTexture() == null ? 0 : 1);
        shader.getUniform("HasEmissiveTexture").set(material.emissiveTexture() == null ? 0 : 1);
        shader.getUniform("IsUnlit").set(material.unlit() ? 1 : 0);
        shader.getUniform("HasSkin").set(primitive.skinIndex() >= 0 ? 1 : 0);
        if (primitive.skinIndex() >= 0) {
            shader.getUniform("JointMatrices").set(model.skinMatrices(primitive, state));
        }
        shader.getUniform("HasMorphTargets").set(primitive.morphTargetCount() > 0 ? 1 : 0);
        float[] morphWeights = state.morphWeights()[primitive.nodeIndex()];
        shader.getUniform("MorphWeights").set(
                morphWeights.length > 0 ? morphWeights[0] : 0.0F,
                morphWeights.length > 1 ? morphWeights[1] : 0.0F,
                morphWeights.length > 2 ? morphWeights[2] : 0.0F,
                morphWeights.length > 3 ? morphWeights[3] : 0.0F);
        shader.getUniform("MetallicFactor").set(material.metallicFactor());
        shader.getUniform("RoughnessFactor").set(material.roughnessFactor());
        shader.getUniform("NormalScale").set(material.normalScale());
        shader.getUniform("OcclusionStrength").set(material.occlusionStrength());
        shader.getUniform("EmissiveFactor").set(
                material.emissiveRed(), material.emissiveGreen(), material.emissiveBlue());
        shader.getUniform("TextureCoordinateSets").set(
                textureCoordinate(material.baseColorTexture()),
                textureCoordinate(material.metallicRoughnessTexture()),
                textureCoordinate(material.normalTexture()),
                textureCoordinate(material.occlusionTexture()));
        shader.getUniform("EmissiveTextureCoordinateSet").set(textureCoordinate(material.emissiveTexture()));
        setTextureTransform(shader, "BaseColor", material.baseColorTexture());
        setTextureTransform(shader, "MetallicRoughness", material.metallicRoughnessTexture());
        setTextureTransform(shader, "Normal", material.normalTexture());
        setTextureTransform(shader, "Occlusion", material.occlusionTexture());
        setTextureTransform(shader, "Emissive", material.emissiveTexture());

        GltfGpuBuffer vertexBuffer;
        if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND) {
            GltfDeformedGeometry geometry = deformedGeometry(model, state, primitive);
            vertexBuffer = primitive.sortedVertexBuffer(sortedIndices(
                    primitive.indices(), poseStack.last().pose(), geometry.positions()));
        } else {
            vertexBuffer = primitive.vertexBuffer();
        }
        vertexBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        poseStack.popPose();
    }

    static void renderCompatible(GltfModel model, GltfRenderState state,
                                 GltfPrimitive primitive, Matrix4f transform,
                                 PoseStack poseStack, MultiBufferSource buffers,
                                 int packedLight, int packedOverlay, boolean shadowPass) {
        GltfMaterial material = primitive.material();
        RenderType renderType = compatibleRenderType(material, shadowPass);
        VertexConsumer consumer = buffers.getBuffer(renderType);
        poseStack.pushPose();
        poseStack.mulPoseMatrix(transform);
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();
        GltfDeformedGeometry geometry = deformedGeometry(model, state, primitive);
        int[] orderedIndices = material.alphaMode() == GltfMaterial.AlphaMode.BLEND
                ? sortedIndices(primitive.indices(), pose, geometry.positions()) : primitive.indices();
        for (int index : orderedIndices) {
            int vectorOffset = index * 3;
            int textureOffset = index * 2;
            float u = primitive.textureCoordinates()[textureOffset];
            float v = primitive.textureCoordinates()[textureOffset + 1];
            GltfTextureInfo textureInfo = material.baseColorTexture();
            if (textureInfo != null && textureInfo.textureCoordinate() == 1) {
                u = primitive.secondaryTextureCoordinates()[textureOffset];
                v = primitive.secondaryTextureCoordinates()[textureOffset + 1];
            }
            if (textureInfo != null) {
                float rotatedU = (float) (u * Math.cos(textureInfo.rotation()) - v * Math.sin(textureInfo.rotation()));
                float rotatedV = (float) (u * Math.sin(textureInfo.rotation()) + v * Math.cos(textureInfo.rotation()));
                u = textureInfo.offsetX() + rotatedU * textureInfo.scaleX();
                v = textureInfo.offsetY() + rotatedV * textureInfo.scaleY();
            }
            int colorOffset = index * 4;
            consumer.vertex(pose, geometry.positions()[vectorOffset], geometry.positions()[vectorOffset + 1],
                            geometry.positions()[vectorOffset + 2])
                    .color(primitive.colors()[colorOffset] * material.red(),
                            primitive.colors()[colorOffset + 1] * material.green(),
                            primitive.colors()[colorOffset + 2] * material.blue(),
                            primitive.colors()[colorOffset + 3] * material.alpha())
                    .uv(u, v)
                    .overlayCoords(packedOverlay)
                    .uv2(material.unlit() ? 15728880 : packedLight)
                    .normal(normalMatrix, geometry.normals()[vectorOffset], geometry.normals()[vectorOffset + 1],
                            geometry.normals()[vectorOffset + 2])
                    .endVertex();
        }
        poseStack.popPose();
    }

    private static int[] sortedIndices(int[] source, Matrix4f pose, float[] positions) {
        Integer[] triangles = new Integer[source.length / 3];
        float[] distances = new float[triangles.length];
        Vector3f center = new Vector3f();
        for (int triangle = 0; triangle < triangles.length; triangle++) {
            triangles[triangle] = triangle;
            center.zero();
            for (int corner = 0; corner < 3; corner++) {
                int offset = source[triangle * 3 + corner] * 3;
                center.add(positions[offset], positions[offset + 1], positions[offset + 2]);
            }
            center.mul(1.0F / 3.0F);
            pose.transformPosition(center);
            distances[triangle] = center.lengthSquared();
        }
        java.util.Arrays.sort(triangles, Comparator.comparingDouble((Integer triangle) -> distances[triangle]).reversed());
        int[] sorted = new int[source.length];
        for (int target = 0; target < triangles.length; target++) {
            int sourceOffset = triangles[target] * 3;
            System.arraycopy(source, sourceOffset, sorted, target * 3, 3);
        }
        return sorted;
    }

    static GltfDeformedGeometry deformedGeometry(GltfModel model, GltfRenderState state,
                                                  GltfPrimitive primitive) {
        GltfDeformedGeometry cached = state.cachedDeformedGeometry(primitive);
        if (cached != null) {
            return cached;
        }
        float[] skinMatrices = primitive.skinIndex() >= 0 ? model.skinMatrices(primitive, state) : null;
        GltfDeformedGeometry geometry = deformGeometry(
                primitive, skinMatrices, state.morphWeights()[primitive.nodeIndex()]);
        state.cacheDeformedGeometry(primitive, geometry);
        return geometry;
    }

    private static GltfDeformedGeometry deformGeometry(GltfPrimitive primitive, float[] skinMatrixValues,
                                                        float[] morphWeights) {
        float[] positions = primitive.positions().clone();
        float[] normals = primitive.normals().clone();
        int targetCount = Math.min(primitive.morphTargetCount(), morphWeights.length);
        for (int target = 0; target < targetCount; target++) {
            float weight = morphWeights[target];
            if (weight == 0.0F) {
                continue;
            }
            float[] morphPositions = primitive.morphPositions()[target];
            float[] morphNormals = target < primitive.morphNormals().length ? primitive.morphNormals()[target] : null;
            for (int offset = 0; offset < positions.length; offset++) {
                positions[offset] += morphPositions[offset] * weight;
                if (morphNormals != null) {
                    normals[offset] += morphNormals[offset] * weight;
                }
            }
        }
        if (skinMatrixValues != null) {
            Matrix4f[] skinMatrices = new Matrix4f[64];
            for (int index = 0; index < skinMatrices.length; index++) {
                skinMatrices[index] = new Matrix4f().set(skinMatrixValues, index * 16);
            }
            Vector3f position = new Vector3f();
            Vector3f normal = new Vector3f();
            Vector3f transformed = new Vector3f();
            Vector3f skinnedPosition = new Vector3f();
            Vector3f skinnedNormal = new Vector3f();
            for (int index = 0; index < positions.length / 3; index++) {
                int vectorOffset = index * 3;
                int jointOffset = index * 4;
                position.set(positions[vectorOffset], positions[vectorOffset + 1], positions[vectorOffset + 2]);
                normal.set(normals[vectorOffset], normals[vectorOffset + 1], normals[vectorOffset + 2]);
                skinnedPosition.zero();
                skinnedNormal.zero();
                for (int influence = 0; influence < 4; influence++) {
                    float weight = primitive.weights()[jointOffset + influence];
                    if (weight == 0.0F) {
                        continue;
                    }
                    Matrix4f matrix = skinMatrices[(int) primitive.joints()[jointOffset + influence]];
                    matrix.transformPosition(position, transformed);
                    skinnedPosition.fma(weight, transformed);
                    matrix.transformDirection(normal, transformed);
                    skinnedNormal.fma(weight, transformed);
                }
                positions[vectorOffset] = skinnedPosition.x;
                positions[vectorOffset + 1] = skinnedPosition.y;
                positions[vectorOffset + 2] = skinnedPosition.z;
                if (skinnedNormal.lengthSquared() > 0.0F) {
                    skinnedNormal.normalize();
                }
                normals[vectorOffset] = skinnedNormal.x;
                normals[vectorOffset + 1] = skinnedNormal.y;
                normals[vectorOffset + 2] = skinnedNormal.z;
            }
        } else {
            Vector3f normal = new Vector3f();
            for (int offset = 0; offset < normals.length; offset += 3) {
                normal.set(normals[offset], normals[offset + 1], normals[offset + 2]);
                if (normal.lengthSquared() > 0.0F) {
                    normal.normalize();
                    normals[offset] = normal.x;
                    normals[offset + 1] = normal.y;
                    normals[offset + 2] = normal.z;
                }
            }
        }
        return new GltfDeformedGeometry(positions, normals);
    }

    static RenderType compatibleRenderType(GltfMaterial material, boolean shadowPass) {
        ResourceLocation texture = material.baseColorTexture() == null
                ? WHITE_TEXTURE : material.baseColorTexture().texture();
        if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND && !shadowPass) {
            return GltfRenderTypes.translucent(texture, material.doubleSided());
        }
        if (material.alphaMode() == GltfMaterial.AlphaMode.MASK || shadowPass) {
            return GltfRenderTypes.cutout(texture, material.doubleSided());
        }
        return GltfRenderTypes.opaque(texture, material.doubleSided());
    }

    static Vector3f primitiveCenter(GltfPrimitive primitive, Matrix4f transform, Matrix4f pose) {
        Vector3f center = primitive.center();
        transform.transformPosition(center);
        pose.transformPosition(center);
        return center;
    }

    private record RenderCall(GltfPrimitive primitive, Matrix4f transform, float distanceSquared) {
    }

    private static void bindTexture(int unit, GltfTextureInfo texture) {
        ResourceLocation location = texture == null ? WHITE_TEXTURE : texture.texture();
        RenderSystem.setShaderTexture(unit, location);
        if (texture == null) {
            return;
        }
        int textureId = Minecraft.getInstance().getTextureManager().getTexture(location).getId();
        GltfSampler sampler = texture.sampler();
        if (sampler.equals(APPLIED_SAMPLERS.get(textureId))) {
            return;
        }
        int minFilter = switch (sampler.minFilter()) {
            case 9984, 9986 -> 9728;
            case 9985, 9987 -> 9729;
            default -> sampler.minFilter();
        };
        GlStateManager._activeTexture(33984 + unit);
        GlStateManager._bindTexture(textureId);
        GlStateManager._texParameter(3553, 10240, sampler.magFilter());
        GlStateManager._texParameter(3553, 10241, minFilter);
        GlStateManager._texParameter(3553, 10242, sampler.wrapS());
        GlStateManager._texParameter(3553, 10243, sampler.wrapT());
        GlStateManager._activeTexture(33984);
        APPLIED_SAMPLERS.put(textureId, sampler);
    }

    static void clearStateCaches() {
        APPLIED_SAMPLERS.clear();
    }

    private static int textureCoordinate(GltfTextureInfo texture) {
        return texture == null ? 0 : texture.textureCoordinate();
    }

    private static void setTextureTransform(ShaderInstance shader, String prefix, GltfTextureInfo texture) {
        if (texture == null) {
            shader.getUniform(prefix + "UvTransform").set(0.0F, 0.0F, 1.0F, 1.0F);
            shader.getUniform(prefix + "UvRotation").set(0.0F);
        } else {
            shader.getUniform(prefix + "UvTransform").set(
                    texture.offsetX(), texture.offsetY(), texture.scaleX(), texture.scaleY());
            shader.getUniform(prefix + "UvRotation").set(texture.rotation());
        }
    }
}
