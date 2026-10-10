package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import me.danialisntcool.gltfapi.client.render.GltfRenderTypes;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import me.danialisntcool.gltfapi.api.client.GltfRenderMode;
import me.danialisntcool.gltfapi.api.client.GltfMaterialRenderers;
import me.danialisntcool.gltfapi.api.client.GltfMaterialRenderer;
import me.danialisntcool.gltfapi.api.client.GltfMaterialRenderContext;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import me.danialisntcool.gltfapi.client.GltfClientConfig;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraftforge.fml.ModList;
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
    private static final Map<ShaderInstance, MaterialState> MATERIAL_STATES = new java.util.IdentityHashMap<>();

    private GltfRenderer() {
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay, GltfRenderOptions options) {
        render(model, poseStack, buffers, packedLight, packedOverlay, options, GltfRenderMode.AUTO);
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay, GltfRenderOptions options, GltfRenderMode mode) {
        RenderSystem.assertOnRenderThread();
        if (usesCompatibilityPath(mode)) {
            renderBuffered(model, poseStack, buffers, packedLight, packedOverlay, options, mode);
            return;
        }
        try (GltfNativeState ignored = new GltfNativeState()) {
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor();
            RenderSystem.setShaderTexture(6, RenderSystem.getShaderTexture(1));
            renderDirect(model, poseStack, buffers, packedLight, packedOverlay, options);
        }
    }

    private static void renderDirect(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                                     int packedLight, int packedOverlay, GltfRenderOptions options) {
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
                        if (!renderCustom(model, state, primitive, transform, poseStack, buffers, packedLight, packedOverlay)) {
                            renderPrimitive(model, state, primitive, transform, poseStack, packedLight, packedOverlay);
                        }
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
                if (!renderCustom(model, state, call.primitive(), call.transform(), poseStack, buffers,
                        packedLight, packedOverlay)) {
                    renderPrimitive(model, state, call.primitive(), call.transform(), poseStack, packedLight, packedOverlay);
                }
            }
        }
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay) {
        render(model, poseStack, buffers, packedLight, packedOverlay, GltfRenderOptions.DEFAULT);
    }

    public static void renderBuffered(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                                      int packedLight, int packedOverlay, GltfRenderOptions options) {
        renderBuffered(model, poseStack, buffers, packedLight, packedOverlay, options, GltfRenderMode.AUTO);
    }

    public static void renderBuffered(GltfModel model, PoseStack poseStack, MultiBufferSource buffers,
                                      int packedLight, int packedOverlay, GltfRenderOptions options,
                                      GltfRenderMode mode) {
        RenderSystem.assertOnRenderThread();
        GltfRenderState state = model.renderState(
                options.animation(), options.animationTimeSeconds(), options.scene(), options.nodeRotationOffsets());
        boolean shadowPass = GltfShaderPackCompat.isRenderingShadowPass();
        boolean pbr = usesBufferedPbr(mode);
        if (!model.hasBlendedPrimitives()) {
            for (GltfPrimitive primitive : model.primitives()) {
                if (!state.visibleNodes().get(primitive.nodeIndex())) {
                    continue;
                }
                Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
                for (Matrix4f instance : primitive.instances()) {
                    renderBufferedPrimitive(model, state, primitive, new Matrix4f(nodeTransform).mul(instance),
                            poseStack, buffers, packedLight, packedOverlay, shadowPass, pbr);
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
            renderBufferedPrimitive(model, state, call.primitive(), call.transform(), poseStack, buffers,
                    packedLight, packedOverlay, shadowPass, pbr);
        }
    }

    public static void render(GltfModel model, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        render(model, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
    }

    public static boolean usesCompatibilityPath() {
        return usesCompatibilityPath(GltfRenderMode.AUTO);
    }

    public static boolean usesCompatibilityPath(GltfRenderMode requested) {
        if (GltfShaderPackCompat.isShaderPackInUse() || GltfShaderPackCompat.isRenderingShadowPass()) {
            return true;
        }
        GltfRenderMode mode = requested == GltfRenderMode.AUTO ? GltfClientConfig.RENDER_MODE.get() : requested;
        ModList mods = ModList.get();
        return mode.usesBuffered(false,
                mods.isLoaded("embeddium") || mods.isLoaded("rubidium") || mods.isLoaded("sodium"));
    }

    public static boolean usesBufferedPbr(GltfRenderMode requested) {
        if (GltfShaderPackCompat.isRenderingShadowPass()
                || GltfShaderPackCompat.isShaderPackInUse() && !isOrthographic(RenderSystem.getProjectionMatrix())) {
            return false;
        }
        GltfRenderMode mode = requested == GltfRenderMode.AUTO ? GltfClientConfig.RENDER_MODE.get() : requested;
        return mode != GltfRenderMode.BUFFERED;
    }

    public static boolean isRenderingShadowPass() {
        return GltfShaderPackCompat.isRenderingShadowPass();
    }

    public static boolean isShaderPackInUse() {
        return GltfShaderPackCompat.isShaderPackInUse();
    }

    private static void renderPrimitive(GltfModel model, GltfRenderState state,
                                        GltfPrimitive primitive, Matrix4f transform,
                                        PoseStack poseStack, int packedLight, int packedOverlay) {
        GltfMaterial material = primitive.material();
        ShaderInstance shader = GltfShaders.modelShader();

        poseStack.pushPose();
        try {
            poseStack.mulPoseMatrix(transform);
            Matrix3f normalMatrix = new Matrix3f(poseStack.last().pose()).invert().transpose();

            RenderSystem.setShader(() -> shader);
            configureNativeShader(model, state, primitive, packedLight, packedOverlay);
            shader.getUniform("NormalMat").set(normalMatrix);

            GltfGpuBuffer vertexBuffer;
            if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND) {
                GltfDeformedGeometry geometry = deformedGeometry(model, state, primitive);
                vertexBuffer = primitive.sortedVertexBuffer(primitive.sortedIndices(
                        poseStack.last().pose(), geometry.positions()));
            } else {
                vertexBuffer = primitive.vertexBuffer();
            }
            vertexBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);

            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
        } finally {
            poseStack.popPose();
        }
    }

    public static boolean canInstance(GltfModel model) {
        return !model.hasBlendedPrimitives() && GltfMaterialRenderers.find(model.location()) == null;
    }

    static void configureNativeShader(GltfModel model, GltfRenderState state, GltfPrimitive primitive,
                                      int packedLight, int packedOverlay) {
        ShaderInstance shader = GltfShaders.modelShader();
        GltfMaterial material = primitive.material();
        bindMaterialTextures(material);
        RenderSystem.enableDepthTest();
        if (material.doubleSided()) RenderSystem.disableCull(); else RenderSystem.enableCull();
        if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }
        shader.getUniform("HasInstances").set(0);
        shader.getUniform("LightUV").set(packedLight & 65535, packedLight >>> 16 & 65535);
        shader.getUniform("OverlayUV").set(packedOverlay & 65535, packedOverlay >>> 16 & 65535);
        shader.getUniform("HasSkin").set(primitive.skinIndex() >= 0 ? 1 : 0);
        if (primitive.skinIndex() >= 0) shader.getUniform("JointMatrices").set(model.skinMatrices(primitive, state));
        shader.getUniform("HasMorphTargets").set(primitive.morphTargetCount() > 0 ? 1 : 0);
        float[] weights = state.morphWeights()[primitive.nodeIndex()];
        shader.getUniform("MorphWeights").set(weights.length > 0 ? weights[0] : 0.0F,
                weights.length > 1 ? weights[1] : 0.0F, weights.length > 2 ? weights[2] : 0.0F,
                weights.length > 3 ? weights[3] : 0.0F);
        applyMaterialUniforms(shader, material);
    }

    static void bindMaterialTextures(GltfMaterial material) {
        bindTexture(0, material.baseColorTexture());
        bindTexture(1, material.metallicRoughnessTexture());
        bindTexture(3, material.normalTexture());
        bindTexture(4, material.occlusionTexture());
        bindTexture(5, material.emissiveTexture());
        RenderSystem.setShaderTexture(7, GltfEnvironmentTexture.LOCATION);
    }

    static void applyMaterialUniforms(ShaderInstance shader, GltfMaterial material) {
        boolean gui = isOrthographic(RenderSystem.getProjectionMatrix());
        MaterialState previous = MATERIAL_STATES.get(shader);
        if (previous != null && previous.material == material && previous.gui == gui) return;
        shader.getUniform("GuiMode").set(gui ? 1 : 0);
        shader.getUniform("AlphaCutoff").set(
                material.alphaMode() == GltfMaterial.AlphaMode.MASK ? material.alphaCutoff() : 0.0F);
            shader.getUniform("HasBaseColorTexture").set(material.baseColorTexture() == null ? 0 : 1);
            shader.getUniform("HasMetallicRoughnessTexture").set(material.metallicRoughnessTexture() == null ? 0 : 1);
            shader.getUniform("HasNormalTexture").set(material.normalTexture() == null ? 0 : 1);
            shader.getUniform("HasOcclusionTexture").set(material.occlusionTexture() == null ? 0 : 1);
            shader.getUniform("HasEmissiveTexture").set(material.emissiveTexture() == null ? 0 : 1);
            shader.getUniform("BaseColorDecodedSrgb").set(decodesSrgb(material.baseColorTexture()) ? 1 : 0);
            shader.getUniform("EmissiveDecodedSrgb").set(decodesSrgb(material.emissiveTexture()) ? 1 : 0);
            shader.getUniform("IsUnlit").set(material.unlit() ? 1 : 0);
            shader.getUniform("MetallicFactor").set(material.metallicFactor());
            shader.getUniform("BaseColorFactor").set(material.red(), material.green(), material.blue(), material.alpha());
            shader.getUniform("AlphaMode").set(material.alphaMode().ordinal());
            shader.getUniform("EnvironmentStrength").set(gui
                    ? ModMetadata.GUI_ENVIRONMENT_STRENGTH : ModMetadata.ENVIRONMENT_STRENGTH);
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
        MATERIAL_STATES.put(shader, new MaterialState(material, gui));
    }

    public static boolean isOrthographic(org.joml.Matrix4fc projection) {
        return Math.abs(projection.m33() - 1.0F) < 0.00001F && Math.abs(projection.m23()) < 0.00001F;
    }

    static void renderCompatible(GltfModel model, GltfRenderState state,
                                 GltfPrimitive primitive, Matrix4f transform,
                                 PoseStack poseStack, MultiBufferSource buffers,
                                 int packedLight, int packedOverlay, boolean shadowPass) {
        renderBufferedPrimitive(model, state, primitive, transform, poseStack,
                buffers, packedLight, packedOverlay, shadowPass, false);
    }

    static void renderBufferedPrimitive(GltfModel model, GltfRenderState state,
                                        GltfPrimitive primitive, Matrix4f transform,
                                        PoseStack poseStack, MultiBufferSource buffers,
                                        int packedLight, int packedOverlay, boolean shadowPass, boolean pbr) {
        if (renderCustom(model, state, primitive, transform, poseStack, buffers, packedLight, packedOverlay)) {
            return;
        }
        GltfMaterial material = primitive.material();
        RenderType renderType = pbr ? GltfBufferedPbrTypes.get(material) : compatibleRenderType(material, shadowPass);
        if (!pbr && (shadowPass || GltfShaderPackCompat.isShaderPackInUse())
                && GltfClientConfig.SHADER_GEOMETRY_CACHE.get() && material.alphaMode() != GltfMaterial.AlphaMode.BLEND
                && GltfShaderGeometryCache.canCache(primitive)
                && buffers.getClass() == MultiBufferSource.BufferSource.class
                && buffers instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
            try (GltfNativeState ignored = new GltfNativeState()) {
                renderType.setupRenderState();
                try {
                    GltfShaderGeometryCache.draw(primitive, deformedGeometry(model, state, primitive),
                            new Matrix4f(poseStack.last().pose()).mul(transform), packedLight, packedOverlay,
                            RenderSystem.getShader());
                } finally {
                    renderType.clearRenderState();
                }
            }
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(renderType);
        if (pbr && !(consumer instanceof com.mojang.blaze3d.vertex.BufferVertexConsumer)) {
            pbr = false;
            consumer = buffers.getBuffer(compatibleRenderType(material, shadowPass));
        }
        poseStack.pushPose();
        try {
            poseStack.mulPoseMatrix(transform);
            Matrix4f pose = poseStack.last().pose();
            Matrix3f normalMatrix = new Matrix3f(pose).invert().transpose();
            GltfDeformedGeometry geometry = deformedGeometry(model, state, primitive);
            int[] orderedIndices = material.alphaMode() == GltfMaterial.AlphaMode.BLEND
                    ? primitive.sortedIndices(pose, geometry.positions()) : primitive.indices();
            if (!pbr && (shadowPass || GltfShaderPackCompat.isShaderPackInUse())
                    && GltfClientConfig.SHADER_GEOMETRY_CACHE.get()
                    && consumer instanceof com.mojang.blaze3d.vertex.BufferBuilder shaderBuilder
                    && GltfShaderStreamWriter.append(shaderBuilder, primitive, geometry, pose, normalMatrix,
                    packedLight, packedOverlay, GltfShaderPackCompat.captureEntityState())) return;
            if (!shadowPass && !GltfShaderPackCompat.isShaderPackInUse()
                    && consumer instanceof com.mojang.blaze3d.vertex.BufferBuilder builder
                    && GltfStreamWriter.append(builder, primitive, geometry, pose, normalMatrix,
                    orderedIndices, packedLight, packedOverlay, pbr)) {
                GltfRenderMetrics.buffered(true, (long) orderedIndices.length * (pbr ? 44 : 36));
                return;
            }
            GltfRenderMetrics.buffered(false, 0);
            GltfRenderMetrics.stream(orderedIndices.length, orderedIndices.length);
            float[] uvs = pbr ? primitive.textureCoordinates() : primitive.compatibleUvs();
            float[] secondaryUvs = primitive.secondaryTextureCoordinates();
            Vector3f normal = new Vector3f();
            for (int index : orderedIndices) {
                int vectorOffset = index * 3;
                int textureOffset = index * 2;
                float u = uvs[textureOffset];
                float v = uvs[textureOffset + 1];
                normalMatrix.transform(geometry.normals()[vectorOffset], geometry.normals()[vectorOffset + 1],
                        geometry.normals()[vectorOffset + 2], normal);
                if (normal.lengthSquared() > 0.0F) normal.normalize();
                int colorOffset = index * 4;
                consumer.vertex(pose, geometry.positions()[vectorOffset], geometry.positions()[vectorOffset + 1],
                                geometry.positions()[vectorOffset + 2])
                        .color(primitive.colors()[colorOffset] * (pbr ? 1.0F : material.red()),
                                primitive.colors()[colorOffset + 1] * (pbr ? 1.0F : material.green()),
                                primitive.colors()[colorOffset + 2] * (pbr ? 1.0F : material.blue()),
                                primitive.colors()[colorOffset + 3] * (pbr ? 1.0F : material.alpha()))
                        .uv(u, v)
                        .overlayCoords(packedOverlay)
                        .uv2(material.unlit() && !pbr ? 15728880 : packedLight)
                        .normal(normal.x, normal.y, normal.z);
                if (pbr) {
                    com.mojang.blaze3d.vertex.BufferVertexConsumer raw =
                            (com.mojang.blaze3d.vertex.BufferVertexConsumer) consumer;
                    raw.putFloat(0, secondaryUvs[textureOffset]);
                    raw.putFloat(4, secondaryUvs[textureOffset + 1]);
                    raw.nextElement();
                }
                consumer.endVertex();
            }
        } finally {
            poseStack.popPose();
        }
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

    static GltfDeformedGeometry deformGeometry(GltfPrimitive primitive, float[] skinMatrixValues,
                                                        float[] morphWeights) {
        if (skinMatrixValues == null && primitive.morphTargetCount() == 0) {
            return new GltfDeformedGeometry(primitive.positions(), primitive.normals());
        }
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
            for (int index = 0; index < positions.length / 3; index++) {
                int vectorOffset = index * 3;
                int jointOffset = index * 4;
                float px = positions[vectorOffset], py = positions[vectorOffset + 1], pz = positions[vectorOffset + 2];
                float nx = normals[vectorOffset], ny = normals[vectorOffset + 1], nz = normals[vectorOffset + 2];
                float sx = 0, sy = 0, sz = 0, snx = 0, sny = 0, snz = 0;
                for (int influence = 0; influence < 4; influence++) {
                    float weight = primitive.weights()[jointOffset + influence];
                    if (weight == 0.0F) {
                        continue;
                    }
                    int matrix = (int) primitive.joints()[jointOffset + influence] * 16;
                    float[] m = skinMatrixValues;
                    sx += weight * (m[matrix] * px + m[matrix + 4] * py + m[matrix + 8] * pz + m[matrix + 12]);
                    sy += weight * (m[matrix + 1] * px + m[matrix + 5] * py + m[matrix + 9] * pz + m[matrix + 13]);
                    sz += weight * (m[matrix + 2] * px + m[matrix + 6] * py + m[matrix + 10] * pz + m[matrix + 14]);
                    snx += weight * (m[matrix] * nx + m[matrix + 4] * ny + m[matrix + 8] * nz);
                    sny += weight * (m[matrix + 1] * nx + m[matrix + 5] * ny + m[matrix + 9] * nz);
                    snz += weight * (m[matrix + 2] * nx + m[matrix + 6] * ny + m[matrix + 10] * nz);
                }
                positions[vectorOffset] = sx;
                positions[vectorOffset + 1] = sy;
                positions[vectorOffset + 2] = sz;
                float squaredLength = snx * snx + sny * sny + snz * snz;
                if (squaredLength > 0.0F) {
                    float inverseLength = (float) (1.0 / Math.sqrt(squaredLength));
                    snx *= inverseLength;
                    sny *= inverseLength;
                    snz *= inverseLength;
                }
                normals[vectorOffset] = snx;
                normals[vectorOffset + 1] = sny;
                normals[vectorOffset + 2] = snz;
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
        if (GltfShaderPackCompat.isShaderPackInUse() || shadowPass)
            texture = GltfShaderMaterialTextures.texture(material, texture);
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

    private static boolean renderCustom(GltfModel model, GltfRenderState state, GltfPrimitive primitive,
                                        Matrix4f transform, PoseStack poseStack, MultiBufferSource buffers,
                                        int packedLight, int packedOverlay) {
        GltfMaterialRenderer renderer = GltfMaterialRenderers.find(model.location());
        if (renderer == null) {
            return false;
        }
        GltfDeformedGeometry geometry = deformedGeometry(model, state, primitive);
        PoseStack customPose = new PoseStack();
        customPose.last().pose().set(poseStack.last().pose()).mul(transform);
        customPose.last().normal().set(new Matrix3f(customPose.last().pose()).invert().transpose());
        int[] indices = primitive.material().alphaMode() == GltfMaterial.AlphaMode.BLEND
                ? primitive.sortedIndices(customPose.last().pose(), geometry.positions()) : primitive.indices();
        return renderer.render(new GltfMaterialRenderContext(model.location(), primitive.material(),
                FloatBuffer.wrap(geometry.positions()), FloatBuffer.wrap(geometry.normals()),
                FloatBuffer.wrap(primitive.colors()), FloatBuffer.wrap(primitive.textureCoordinates()),
                FloatBuffer.wrap(primitive.secondaryTextureCoordinates()), IntBuffer.wrap(indices),
                customPose, buffers, packedLight, packedOverlay,
                GltfShaderPackCompat.isShaderPackInUse(), GltfShaderPackCompat.isRenderingShadowPass()));
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
        int minFilter = sampler.minFilter();
        if (Minecraft.getInstance().getTextureManager().getTexture(location) instanceof KtxTexture ktx
                && !ktx.hasMipmaps()) {
            minFilter = switch (minFilter) {
                case 9984, 9986 -> 9728;
                case 9985, 9987 -> 9729;
                default -> minFilter;
            };
        }
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
        GltfGuiIconCache.clear();
        GltfWorldQueue.reset();
        GltfGpuTiming.clear();
        GltfNativeBatch.clearPool();
        MATERIAL_STATES.clear();
        APPLIED_SAMPLERS.clear();
        GltfBufferedPbrTypes.clear();
        GltfStreamWriter.clear();
        GltfShaderStreamWriter.clear();
        GltfGpuBufferPool.clear();
        GltfInstanceBuffer.clear();
        GltfCompatibleBatch.clearCaches();
        GltfShaderGeometryCache.clear();
        GltfShaderMaterialTextures.clear();
    }

    private static int textureCoordinate(GltfTextureInfo texture) {
        return texture == null ? 0 : texture.textureCoordinate();
    }

    private static boolean decodesSrgb(GltfTextureInfo texture) {
        return texture != null && Minecraft.getInstance().getTextureManager().getTexture(texture.texture())
                instanceof KtxTexture ktx && ktx.decodesSrgb();
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

    private record MaterialState(GltfMaterial material, boolean gui) {
    }
}
