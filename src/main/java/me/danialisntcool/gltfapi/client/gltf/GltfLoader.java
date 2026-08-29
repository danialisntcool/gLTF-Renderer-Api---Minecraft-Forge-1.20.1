package me.danialisntcool.gltfapi.client.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fileformat.drako.Draco;
import dev.fileformat.drako.DracoMesh;
import dev.fileformat.drako.DracoPointCloud;
import dev.fileformat.drako.PointAttribute;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GltfLoader {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;

    private final ResourceManager resourceManager;
    private final ResourceLocation location;
    private final JsonObject root;
    private final List<byte[]> buffers;
    private final List<BufferView> bufferViews;
    private final List<Accessor> accessors;
    private final List<GltfImageData> images;
    private final List<GltfMaterial> materials;
    private final Map<ResourceLocation, GltfEmbeddedTexture> embeddedTextures = new HashMap<>();

    private GltfLoader(ResourceManager resourceManager, ResourceLocation location, byte[] fileBytes) {
        this.resourceManager = resourceManager;
        this.location = location;
        ParsedContainer container = parseContainer(fileBytes);
        this.root = JsonParser.parseString(container.json()).getAsJsonObject();
        verifyAsset();
        this.buffers = loadBuffers(container.binaryChunk());
        this.bufferViews = loadBufferViews();
        this.accessors = loadAccessors();
        this.images = loadImages();
        this.materials = loadMaterials();
        retainUsedEmbeddedTextures();
    }

    public static GltfModel load(ResourceManager resourceManager, ResourceLocation location) {
        try {
            Resource resource = resourceManager.getResource(location)
                    .orElseThrow(() -> new GltfLoadException("Missing glTF resource " + location));
            byte[] bytes;
            try (var input = resource.open()) {
                bytes = readLimited(input, location.toString());
            }
            return new GltfLoader(resourceManager, location, bytes).buildModel();
        } catch (GltfLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GltfLoadException("Failed to load glTF model " + location, exception);
        }
    }

    private GltfModel buildModel() {
        JsonArray nodeDefinitions = array(root, "nodes");
        List<GltfNode> nodes = loadNodes(nodeDefinitions);
        List<GltfScene> scenes = loadScenes(nodes);
        int defaultScene = integer(root, "scene", 0);
        if (defaultScene < 0 || defaultScene >= scenes.size()) {
            throw error("Default scene index is out of range");
        }
        List<GltfPrimitive> primitives = new ArrayList<>();
        Matrix4f[] transforms = new Matrix4f[nodes.size()];
        boolean[] active = new boolean[nodes.size()];
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            GltfNode node = nodes.get(nodeIndex);
            Matrix4f transform = defaultWorldTransform(nodeIndex, nodes, transforms, active);
            if (node.mesh() >= 0) {
                addMesh(node.mesh(), transform, primitives, nodeIndex,
                        instanceTransforms(nodeDefinitions.get(nodeIndex).getAsJsonObject()));
            }
        }
        return new GltfModel(location, primitives, embeddedTextures, nodes, scenes, defaultScene,
                loadAnimations(nodes), loadSkins(nodes));
    }

    private Matrix4f defaultWorldTransform(int nodeIndex, List<GltfNode> nodes, Matrix4f[] transforms,
                                           boolean[] active) {
        if (transforms[nodeIndex] != null) {
            return transforms[nodeIndex];
        }
        if (active[nodeIndex]) {
            throw error("Node hierarchy contains a cycle at node " + nodeIndex);
        }
        active[nodeIndex] = true;
        GltfNode node = nodes.get(nodeIndex);
        Matrix4f local = node.localTransform(node.pose(), null);
        transforms[nodeIndex] = node.parent() < 0
                ? local
                : new Matrix4f(defaultWorldTransform(node.parent(), nodes, transforms, active)).mul(local);
        active[nodeIndex] = false;
        return transforms[nodeIndex];
    }

    private void addMesh(int meshIndex, Matrix4f transform, List<GltfPrimitive> output, int nodeIndex,
                         List<Matrix4f> instances) {
        JsonArray meshes = requiredArray(root, "meshes");
        if (meshIndex < 0 || meshIndex >= meshes.size()) {
            throw error("Mesh index " + meshIndex + " is out of range");
        }
        JsonObject mesh = meshes.get(meshIndex).getAsJsonObject();
        for (JsonElement element : requiredArray(mesh, "primitives")) {
            JsonObject primitive = element.getAsJsonObject();
            DracoPrimitiveData draco = decodeDracoPrimitive(primitive);
            int mode = integer(primitive, "mode", 4);
            if (mode < 4 || mode > 6) {
                throw error("Primitive mode " + mode + " is not supported; use triangles, triangle strips, or triangle fans");
            }
            JsonObject attributes = requiredObject(primitive, "attributes");
            if (!attributes.has("POSITION")) {
                throw error("Mesh primitive has no POSITION attribute");
            }
            float[] positions = readPrimitiveAttribute(attributes, "POSITION", 3, draco);
            int vertexCount = positions.length / 3;
            int[] indices;
            if (draco != null) {
                indices = draco.indices();
                if (primitive.has("indices")) {
                    Accessor indexAccessor = indexed(accessors, primitive.get("indices").getAsInt(), "index accessor");
                    if (indexAccessor.count() != indices.length) {
                        throw error("Draco index count does not match its accessor");
                    }
                }
            } else {
                int[] sourceIndices = primitive.has("indices")
                        ? readIndexAccessor(primitive.get("indices").getAsInt())
                        : sequentialIndices(vertexCount);
                indices = triangulate(sourceIndices, mode);
            }
            validateIndices(indices, vertexCount);
            float[] normals = attributes.has("NORMAL")
                    ? readPrimitiveAttribute(attributes, "NORMAL", 3, draco)
                    : generateNormals(positions, indices);
            float[] textureCoordinates = attributes.has("TEXCOORD_0")
                    ? readPrimitiveAttribute(attributes, "TEXCOORD_0", 2, draco)
                    : new float[vertexCount * 2];
            float[] secondaryTextureCoordinates = attributes.has("TEXCOORD_1")
                    ? readPrimitiveAttribute(attributes, "TEXCOORD_1", 2, draco)
                    : textureCoordinates.clone();
            float[] colors = attributes.has("COLOR_0")
                    ? readPrimitiveColors(attributes, vertexCount, draco)
                    : filledColors(vertexCount);
            float[] joints = null;
            float[] weights = null;
            if (indexedNodeSkin(nodeIndex) >= 0) {
                if (!attributes.has("JOINTS_0") || !attributes.has("WEIGHTS_0")) {
                    throw error("Skinned primitive is missing JOINTS_0 or WEIGHTS_0");
                }
                joints = readPrimitiveAttribute(attributes, "JOINTS_0", 4, draco);
                weights = readPrimitiveAttribute(attributes, "WEIGHTS_0", 4, draco);
                requireVertexAttributeSize("JOINTS_0", joints.length, vertexCount * 4);
                requireVertexAttributeSize("WEIGHTS_0", weights.length, vertexCount * 4);
                normalizeWeights(weights);
            }
            requireVertexAttributeSize("NORMAL", normals.length, vertexCount * 3);
            requireVertexAttributeSize("TEXCOORD_0", textureCoordinates.length, vertexCount * 2);
            requireVertexAttributeSize("TEXCOORD_1", secondaryTextureCoordinates.length, vertexCount * 2);
            requireVertexAttributeSize("COLOR_0", colors.length, vertexCount * 4);
            int materialIndex = integer(primitive, "material", -1);
            GltfMaterial material = materialIndex >= 0 ? indexed(materials, materialIndex, "material") : defaultMaterial();
            JsonArray targets = array(primitive, "targets");
            if (targets.size() > 4) {
                throw error("Primitive exceeds the four-target GPU morph limit");
            }
            float[][] morphPositions = new float[targets.size()][positions.length];
            float[][] morphNormals = new float[targets.size()][normals.length];
            for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
                JsonObject target = targets.get(targetIndex).getAsJsonObject();
                if (target.has("POSITION")) {
                    morphPositions[targetIndex] = readFloatAccessor(target.get("POSITION").getAsInt(), 3);
                    requireVertexAttributeSize("morph POSITION", morphPositions[targetIndex].length, positions.length);
                }
                if (target.has("NORMAL")) {
                    morphNormals[targetIndex] = readFloatAccessor(target.get("NORMAL").getAsInt(), 3);
                    requireVertexAttributeSize("morph NORMAL", morphNormals[targetIndex].length, normals.length);
                }
            }
            output.add(new GltfPrimitive(positions, normals, textureCoordinates, secondaryTextureCoordinates,
                    colors, joints, weights, morphPositions, morphNormals, indices, material, transform, nodeIndex,
                    indexedNodeSkin(nodeIndex), instances));
        }
    }

    private DracoPrimitiveData decodeDracoPrimitive(JsonObject primitive) {
        if (!primitive.has("extensions")
                || !primitive.getAsJsonObject("extensions").has("KHR_draco_mesh_compression")) {
            return null;
        }
        JsonObject extension = primitive.getAsJsonObject("extensions")
                .getAsJsonObject("KHR_draco_mesh_compression");
        BufferView view = indexed(bufferViews, requiredInt(extension, "bufferView"), "Draco buffer view");
        byte[] buffer = indexed(buffers, view.buffer(), "Draco buffer");
        if (view.byteOffset() < 0 || view.byteLength() < 0
                || (long) view.byteOffset() + view.byteLength() > buffer.length) {
            throw error("Draco buffer view is out of range");
        }
        byte[] compressed = new byte[view.byteLength()];
        System.arraycopy(buffer, view.byteOffset(), compressed, 0, compressed.length);
        DracoPointCloud pointCloud;
        try {
            pointCloud = Draco.decode(compressed);
        } catch (Exception exception) {
            throw new GltfLoadException("Could not decode Draco primitive in " + location, exception);
        }
        if (!(pointCloud instanceof DracoMesh decodedMesh)) {
            throw error("Draco primitive is not a triangle mesh");
        }
        JsonObject attributeDefinitions = requiredObject(extension, "attributes");
        Map<String, Integer> attributeIds = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : attributeDefinitions.entrySet()) {
            attributeIds.put(entry.getKey(), entry.getValue().getAsInt());
        }
        int indexCount = checkedArrayLength(decodedMesh.getNumFaces(), 3, Integer.BYTES, "Draco indices");
        int[] indices = new int[indexCount];
        int[] face = new int[3];
        for (int faceIndex = 0; faceIndex < decodedMesh.getNumFaces(); faceIndex++) {
            decodedMesh.readFace(faceIndex, face);
            System.arraycopy(face, 0, indices, faceIndex * 3, 3);
        }
        return new DracoPrimitiveData(decodedMesh, Map.copyOf(attributeIds), indices);
    }

    private float[] readPrimitiveAttribute(JsonObject attributes, String semantic, int components,
                                           DracoPrimitiveData draco) {
        int accessorIndex = requiredInt(attributes, semantic);
        if (draco == null || !draco.attributeIds().containsKey(semantic)) {
            return readFloatAccessor(accessorIndex, components);
        }
        Accessor accessor = indexed(accessors, accessorIndex, semantic + " accessor");
        if (componentCount(accessor.type()) != components || accessor.count() != draco.mesh().getNumPoints()) {
            throw error("Draco " + semantic + " does not match its accessor");
        }
        int uniqueId = draco.attributeIds().get(semantic);
        PointAttribute attribute = null;
        for (int attributeIndex = 0; attributeIndex < draco.mesh().getNumAttributes(); attributeIndex++) {
            PointAttribute candidate = draco.mesh().attribute(attributeIndex);
            if (Short.toUnsignedInt(candidate.getUniqueId()) == uniqueId) {
                attribute = candidate;
                break;
            }
        }
        if (attribute == null) {
            throw error("Draco attribute " + uniqueId + " for " + semantic + " is missing");
        }
        if (attribute.getComponentsCount() != components) {
            throw error("Draco " + semantic + " component count does not match its accessor");
        }
        float[] result = new float[checkedArrayLength(accessor.count(), components, Float.BYTES,
                "Draco " + semantic + " accessor")];
        float[] value = new float[components];
        for (int point = 0; point < accessor.count(); point++) {
            attribute.getValue(attribute.mappedIndex(point), value);
            for (int component = 0; component < components; component++) {
                result[point * components + component] = normalizeDracoComponent(
                        value[component], accessor.componentType(), accessor.normalized());
            }
        }
        return result;
    }

    private float normalizeDracoComponent(float value, int componentType, boolean normalized) {
        if (!normalized) {
            return value;
        }
        return switch (componentType) {
            case 5120 -> Math.max(value / 127.0F, -1.0F);
            case 5121 -> value / 255.0F;
            case 5122 -> Math.max(value / 32767.0F, -1.0F);
            case 5123 -> value / 65535.0F;
            case 5125 -> (float) (value / 4294967295.0);
            case 5126 -> value;
            default -> throw error("Unsupported Draco accessor component type " + componentType);
        };
    }

    private float[] readPrimitiveColors(JsonObject attributes, int vertexCount, DracoPrimitiveData draco) {
        int accessorIndex = requiredInt(attributes, "COLOR_0");
        Accessor accessor = indexed(accessors, accessorIndex, "COLOR_0 accessor");
        int components = componentCount(accessor.type());
        if (components != 3 && components != 4) {
            throw error("COLOR_0 must use VEC3 or VEC4");
        }
        float[] source = readPrimitiveAttribute(attributes, "COLOR_0", components, draco);
        return expandColors(source, components, vertexCount);
    }

    private int indexedNodeSkin(int nodeIndex) {
        JsonArray nodes = array(root, "nodes");
        return integer(nodes.get(nodeIndex).getAsJsonObject(), "skin", -1);
    }

    private void normalizeWeights(float[] weights) {
        for (int offset = 0; offset < weights.length; offset += 4) {
            float sum = weights[offset] + weights[offset + 1] + weights[offset + 2] + weights[offset + 3];
            if (sum <= 0.0F) {
                weights[offset] = 1.0F;
                weights[offset + 1] = 0.0F;
                weights[offset + 2] = 0.0F;
                weights[offset + 3] = 0.0F;
            } else {
                weights[offset] /= sum;
                weights[offset + 1] /= sum;
                weights[offset + 2] /= sum;
                weights[offset + 3] /= sum;
            }
        }
    }

    private List<Matrix4f> instanceTransforms(JsonObject node) {
        if (!node.has("extensions")
                || !node.getAsJsonObject("extensions").has("EXT_mesh_gpu_instancing")) {
            return List.of(new Matrix4f());
        }
        JsonObject extension = node.getAsJsonObject("extensions").getAsJsonObject("EXT_mesh_gpu_instancing");
        JsonObject attributes = requiredObject(extension, "attributes");
        float[] translations = attributes.has("TRANSLATION")
                ? readFloatAccessor(attributes.get("TRANSLATION").getAsInt(), 3) : null;
        float[] rotations = attributes.has("ROTATION")
                ? readFloatAccessor(attributes.get("ROTATION").getAsInt(), 4) : null;
        float[] scales = attributes.has("SCALE")
                ? readFloatAccessor(attributes.get("SCALE").getAsInt(), 3) : null;
        int count = translations != null ? translations.length / 3
                : rotations != null ? rotations.length / 4
                : scales != null ? scales.length / 3 : 0;
        if (count == 0) {
            throw error("EXT_mesh_gpu_instancing has no instance attributes");
        }
        if ((translations != null && translations.length / 3 != count)
                || (rotations != null && rotations.length / 4 != count)
                || (scales != null && scales.length / 3 != count)) {
            throw error("EXT_mesh_gpu_instancing attribute counts do not match");
        }
        List<Matrix4f> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Vector3f translation = translations == null ? new Vector3f()
                    : new Vector3f(translations[index * 3], translations[index * 3 + 1], translations[index * 3 + 2]);
            Quaternionf rotation = rotations == null ? new Quaternionf()
                    : new Quaternionf(rotations[index * 4], rotations[index * 4 + 1],
                    rotations[index * 4 + 2], rotations[index * 4 + 3]).normalize();
            Vector3f scale = scales == null ? new Vector3f(1.0F)
                    : new Vector3f(scales[index * 3], scales[index * 3 + 1], scales[index * 3 + 2]);
            result.add(new Matrix4f().translation(translation).rotate(rotation).scale(scale));
        }
        return result;
    }

    private float[] readColors(int accessorIndex, int vertexCount) {
        Accessor accessor = indexed(accessors, accessorIndex, "accessor");
        int components = componentCount(accessor.type());
        if (components != 3 && components != 4) {
            throw error("COLOR_0 must use VEC3 or VEC4");
        }
        float[] source = readFloatAccessor(accessorIndex, components);
        return expandColors(source, components, vertexCount);
    }

    private float[] expandColors(float[] source, int components, int vertexCount) {
        if (source.length != vertexCount * components) {
            throw error("COLOR_0 count does not match POSITION");
        }
        float[] result = new float[vertexCount * 4];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int sourceOffset = vertex * components;
            int targetOffset = vertex * 4;
            result[targetOffset] = source[sourceOffset];
            result[targetOffset + 1] = source[sourceOffset + 1];
            result[targetOffset + 2] = source[sourceOffset + 2];
            result[targetOffset + 3] = components == 4 ? source[sourceOffset + 3] : 1.0F;
        }
        return result;
    }

    private float[] readFloatAccessor(int accessorIndex, int expectedComponents) {
        Accessor accessor = indexed(accessors, accessorIndex, "accessor");
        int components = componentCount(accessor.type());
        if (components != expectedComponents) {
            throw error("Accessor " + accessorIndex + " uses " + accessor.type() + " but expected " + expectedComponents + " components");
        }
        float[] result = new float[checkedArrayLength(accessor.count(), components, Float.BYTES,
                "Accessor " + accessorIndex)];
        int componentSize = componentSize(accessor.componentType());
        int elementSize = componentSize * components;
        if (accessor.bufferView() >= 0) {
            BufferView view = indexed(bufferViews, accessor.bufferView(), "buffer view");
            byte[] buffer = indexed(buffers, view.buffer(), "buffer");
            int stride = view.byteStride() == 0 ? elementSize : view.byteStride();
            validateReadRange(buffer, view, accessor.byteOffset(), accessor.count(), stride, elementSize);
            ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
            int start = view.byteOffset() + accessor.byteOffset();
            for (int element = 0; element < accessor.count(); element++) {
                int offset = start + element * stride;
                for (int component = 0; component < components; component++) {
                    result[element * components + component] = readComponentAsFloat(
                            data, offset + component * componentSize, accessor.componentType(), accessor.normalized());
                }
            }
        }
        applySparseFloats(accessorIndex, accessor, result, components);
        return result;
    }

    private int[] readIndexAccessor(int accessorIndex) {
        Accessor accessor = indexed(accessors, accessorIndex, "accessor");
        if (!"SCALAR".equals(accessor.type())) {
            throw error("Index accessor " + accessorIndex + " must use SCALAR");
        }
        if (accessor.componentType() != 5121 && accessor.componentType() != 5123 && accessor.componentType() != 5125) {
            throw error("Index accessor " + accessorIndex + " must use unsigned byte, unsigned short, or unsigned int");
        }
        int[] result = new int[checkedArrayLength(accessor.count(), 1, Integer.BYTES,
                "Index accessor " + accessorIndex)];
        int componentSize = componentSize(accessor.componentType());
        if (accessor.bufferView() >= 0) {
            BufferView view = indexed(bufferViews, accessor.bufferView(), "buffer view");
            byte[] buffer = indexed(buffers, view.buffer(), "buffer");
            int stride = view.byteStride() == 0 ? componentSize : view.byteStride();
            validateReadRange(buffer, view, accessor.byteOffset(), accessor.count(), stride, componentSize);
            ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
            int start = view.byteOffset() + accessor.byteOffset();
            for (int index = 0; index < result.length; index++) {
                result[index] = readUnsignedInt(data, start + index * stride, accessor.componentType(), "index");
            }
        }
        applySparseIndices(accessorIndex, accessor, result);
        return result;
    }

    private void applySparseFloats(int accessorIndex, Accessor accessor, float[] result, int components) {
        SparseAccessor sparse = accessor.sparse();
        if (sparse == null) {
            return;
        }
        int[] targetIndices = readSparseTargetIndices(accessorIndex, accessor.count(), sparse);
        BufferView valuesView = indexed(bufferViews, sparse.valuesBufferView(), "sparse values buffer view");
        byte[] valuesBuffer = indexed(buffers, valuesView.buffer(), "buffer");
        int componentSize = componentSize(accessor.componentType());
        int elementSize = componentSize * components;
        validateReadRange(valuesBuffer, valuesView, sparse.valuesByteOffset(), sparse.count(), elementSize, elementSize);
        ByteBuffer data = ByteBuffer.wrap(valuesBuffer).order(ByteOrder.LITTLE_ENDIAN);
        int start = valuesView.byteOffset() + sparse.valuesByteOffset();
        for (int sparseIndex = 0; sparseIndex < sparse.count(); sparseIndex++) {
            int targetOffset = targetIndices[sparseIndex] * components;
            int sourceOffset = start + sparseIndex * elementSize;
            for (int component = 0; component < components; component++) {
                result[targetOffset + component] = readComponentAsFloat(
                        data, sourceOffset + component * componentSize, accessor.componentType(), accessor.normalized());
            }
        }
    }

    private void applySparseIndices(int accessorIndex, Accessor accessor, int[] result) {
        SparseAccessor sparse = accessor.sparse();
        if (sparse == null) {
            return;
        }
        int[] targetIndices = readSparseTargetIndices(accessorIndex, accessor.count(), sparse);
        BufferView valuesView = indexed(bufferViews, sparse.valuesBufferView(), "sparse values buffer view");
        byte[] valuesBuffer = indexed(buffers, valuesView.buffer(), "buffer");
        int componentSize = componentSize(accessor.componentType());
        validateReadRange(valuesBuffer, valuesView, sparse.valuesByteOffset(), sparse.count(), componentSize, componentSize);
        ByteBuffer data = ByteBuffer.wrap(valuesBuffer).order(ByteOrder.LITTLE_ENDIAN);
        int start = valuesView.byteOffset() + sparse.valuesByteOffset();
        for (int sparseIndex = 0; sparseIndex < sparse.count(); sparseIndex++) {
            result[targetIndices[sparseIndex]] = readUnsignedInt(
                    data, start + sparseIndex * componentSize, accessor.componentType(), "sparse index value");
        }
    }

    private int[] readSparseTargetIndices(int accessorIndex, int accessorCount, SparseAccessor sparse) {
        if (sparse.count() < 0 || sparse.count() > accessorCount) {
            throw error("Sparse accessor " + accessorIndex + " has an invalid count");
        }
        if (sparse.indicesComponentType() != 5121 && sparse.indicesComponentType() != 5123
                && sparse.indicesComponentType() != 5125) {
            throw error("Sparse accessor " + accessorIndex + " uses an invalid index component type");
        }
        BufferView view = indexed(bufferViews, sparse.indicesBufferView(), "sparse indices buffer view");
        byte[] buffer = indexed(buffers, view.buffer(), "buffer");
        int componentSize = componentSize(sparse.indicesComponentType());
        validateReadRange(buffer, view, sparse.indicesByteOffset(), sparse.count(), componentSize, componentSize);
        ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[sparse.count()];
        int start = view.byteOffset() + sparse.indicesByteOffset();
        int previous = -1;
        for (int index = 0; index < result.length; index++) {
            int value = readUnsignedInt(data, start + index * componentSize, sparse.indicesComponentType(), "sparse target index");
            if (value >= accessorCount || value <= previous) {
                throw error("Sparse accessor " + accessorIndex + " has out-of-range or unsorted indices");
            }
            result[index] = value;
            previous = value;
        }
        return result;
    }

    private int readUnsignedInt(ByteBuffer data, int offset, int componentType, String label) {
        long value = switch (componentType) {
            case 5121 -> Byte.toUnsignedInt(data.get(offset));
            case 5123 -> Short.toUnsignedInt(data.getShort(offset));
            case 5125 -> Integer.toUnsignedLong(data.getInt(offset));
            default -> throw error("Invalid unsigned component type for " + label);
        };
        if (value > Integer.MAX_VALUE) {
            throw error(label + " exceeds Minecraft's supported vertex range");
        }
        return (int) value;
    }

    private List<byte[]> loadBuffers(byte[] binaryChunk) {
        JsonArray definitions = array(root, "buffers");
        List<byte[]> result = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            JsonObject definition = definitions.get(index).getAsJsonObject();
            byte[] data;
            if (definition.has("uri")) {
                data = loadUriBytes(definition.get("uri").getAsString());
            } else if (index == 0 && binaryChunk != null) {
                data = binaryChunk;
            } else {
                throw error("Buffer " + index + " has no URI or GLB binary chunk");
            }
            int declaredLength = requiredInt(definition, "byteLength");
            if (data.length < declaredLength) {
                throw error("Buffer " + index + " is shorter than its declared byteLength");
            }
            result.add(data);
        }
        return result;
    }

    private List<BufferView> loadBufferViews() {
        List<BufferView> result = new ArrayList<>();
        for (JsonElement element : array(root, "bufferViews")) {
            JsonObject view = element.getAsJsonObject();
            if (view.has("extensions")
                    && view.getAsJsonObject("extensions").has("EXT_meshopt_compression")) {
                result.add(decodeMeshoptBufferView(view,
                        view.getAsJsonObject("extensions").getAsJsonObject("EXT_meshopt_compression")));
            } else {
                result.add(new BufferView(
                        requiredInt(view, "buffer"),
                        integer(view, "byteOffset", 0),
                        requiredInt(view, "byteLength"),
                        integer(view, "byteStride", 0)));
            }
        }
        return result;
    }

    private BufferView decodeMeshoptBufferView(JsonObject parent, JsonObject extension) {
        int sourceBufferIndex = requiredInt(extension, "buffer");
        byte[] sourceBuffer = indexed(buffers, sourceBufferIndex, "meshopt source buffer");
        int sourceOffset = integer(extension, "byteOffset", 0);
        int sourceLength = requiredInt(extension, "byteLength");
        int stride = requiredInt(extension, "byteStride");
        int count = requiredInt(extension, "count");
        if (sourceOffset < 0 || sourceLength < 0 || stride <= 0 || count < 0
                || (long) sourceOffset + sourceLength > sourceBuffer.length
                || (long) stride * count > ModMetadata.MAX_DECODED_BUFFER_BYTES) {
            throw error("EXT_meshopt_compression contains an invalid byte range");
        }
        int outputLength = stride * count;
        int declaredLength = requiredInt(parent, "byteLength");
        if (declaredLength != outputLength) {
            throw error("EXT_meshopt_compression output size does not match its buffer view");
        }
        ByteBuffer source = MemoryUtil.memAlloc(sourceLength);
        ByteBuffer output = MemoryUtil.memAlloc(outputLength);
        try {
            source.put(sourceBuffer, sourceOffset, sourceLength).flip();
            String mode = requiredString(extension, "mode");
            int status = switch (mode) {
                case "ATTRIBUTES" -> MeshOptimizer.meshopt_decodeVertexBuffer(output, count, stride, source);
                case "TRIANGLES" -> MeshOptimizer.meshopt_decodeIndexBuffer(output, count, stride, source);
                case "INDICES" -> MeshOptimizer.meshopt_decodeIndexSequence(output, count, stride, source);
                default -> throw error("Unknown EXT_meshopt_compression mode " + mode);
            };
            if (status != 0) {
                throw error("Meshoptimizer rejected compressed buffer data with status " + status);
            }
            String filter = string(extension, "filter", "NONE");
            switch (filter) {
                case "NONE" -> {
                }
                case "OCTAHEDRAL" -> MeshOptimizer.meshopt_decodeFilterOct(output, count, stride);
                case "QUATERNION" -> MeshOptimizer.meshopt_decodeFilterQuat(output, count, stride);
                case "EXPONENTIAL" -> MeshOptimizer.meshopt_decodeFilterExp(output, count, stride);
                default -> throw error("Unknown EXT_meshopt_compression filter " + filter);
            }
            byte[] decoded = new byte[outputLength];
            output.position(0);
            output.get(decoded);
            int decodedBufferIndex = buffers.size();
            buffers.add(decoded);
            return new BufferView(decodedBufferIndex, 0, outputLength,
                    integer(parent, "byteStride", stride));
        } finally {
            MemoryUtil.memFree(output);
            MemoryUtil.memFree(source);
        }
    }

    private List<Accessor> loadAccessors() {
        List<Accessor> result = new ArrayList<>();
        for (JsonElement element : array(root, "accessors")) {
            JsonObject accessor = element.getAsJsonObject();
            SparseAccessor sparse = null;
            if (accessor.has("sparse")) {
                JsonObject sparseObject = accessor.getAsJsonObject("sparse");
                JsonObject indices = requiredObject(sparseObject, "indices");
                JsonObject values = requiredObject(sparseObject, "values");
                sparse = new SparseAccessor(
                        requiredInt(sparseObject, "count"),
                        requiredInt(indices, "bufferView"),
                        integer(indices, "byteOffset", 0),
                        requiredInt(indices, "componentType"),
                        requiredInt(values, "bufferView"),
                        integer(values, "byteOffset", 0));
            }
            result.add(new Accessor(
                    integer(accessor, "bufferView", -1),
                    integer(accessor, "byteOffset", 0),
                    requiredInt(accessor, "componentType"),
                    booleanValue(accessor, "normalized", false),
                    requiredInt(accessor, "count"),
                    requiredString(accessor, "type"),
                    sparse));
        }
        return result;
    }

    private List<GltfImageData> loadImages() {
        List<GltfImageData> result = new ArrayList<>();
        JsonArray images = array(root, "images");
        for (int index = 0; index < images.size(); index++) {
            JsonObject image = images.get(index).getAsJsonObject();
            if (image.has("uri")) {
                String uri = image.get("uri").getAsString();
                if (uri.startsWith("data:")) {
                    byte[] bytes = decodeDataUri(uri);
                    result.add(new GltfImageData(bytes, isKtx2(bytes, string(image, "mimeType", ""))));
                } else {
                    byte[] bytes = loadUriBytes(uri);
                    result.add(new GltfImageData(bytes, isKtx2(bytes, string(image, "mimeType", "")) || uri.toLowerCase(Locale.ROOT).endsWith(".ktx2")));
                }
            } else if (image.has("bufferView")) {
                BufferView view = indexed(bufferViews, image.get("bufferView").getAsInt(), "buffer view");
                byte[] buffer = indexed(buffers, view.buffer(), "buffer");
                if (view.byteOffset() < 0 || view.byteLength() < 0 || view.byteOffset() + view.byteLength() > buffer.length) {
                    throw error("Embedded image buffer view is out of range");
                }
                byte[] bytes = new byte[view.byteLength()];
                System.arraycopy(buffer, view.byteOffset(), bytes, 0, bytes.length);
                result.add(new GltfImageData(bytes, isKtx2(bytes, string(image, "mimeType", ""))));
            } else {
                throw error("Image " + index + " has neither a URI nor a buffer view");
            }
        }
        return result;
    }

    private List<GltfMaterial> loadMaterials() {
        List<GltfMaterial> result = new ArrayList<>();
        List<GltfSampler> samplers = new ArrayList<>();
        for (JsonElement element : array(root, "samplers")) {
            JsonObject sampler = element.getAsJsonObject();
            samplers.add(new GltfSampler(
                    integer(sampler, "magFilter", 9729),
                    integer(sampler, "minFilter", 9729),
                    integer(sampler, "wrapS", 10497),
                    integer(sampler, "wrapT", 10497)));
        }
        List<TextureDefinition> textures = new ArrayList<>();
        int textureNumber = 0;
        for (JsonElement element : array(root, "textures")) {
            JsonObject texture = element.getAsJsonObject();
            int fallbackSource = integer(texture, "source", -1);
            int source = fallbackSource;
            if (texture.has("extensions") && texture.getAsJsonObject("extensions").has("KHR_texture_basisu")) {
                source = requiredInt(texture.getAsJsonObject("extensions").getAsJsonObject("KHR_texture_basisu"), "source");
            }
            if (source < 0) {
                throw error("Texture has neither source nor KHR_texture_basisu source");
            }
            GltfImageData image = indexed(images, source, "image");
            GltfImageData fallback = fallbackSource >= 0 && fallbackSource != source
                    ? indexed(images, fallbackSource, "fallback image") : null;
            if (texture.has("extensions") && texture.getAsJsonObject("extensions").has("KHR_texture_basisu") && !image.ktx2()) {
                throw error("KHR_texture_basisu source is not a KTX2 image");
            }
            ResourceLocation textureLocation = registerEmbeddedTexture(textureNumber++, image, fallback);
            int samplerIndex = integer(texture, "sampler", -1);
            GltfSampler sampler = samplerIndex < 0
                    ? GltfSampler.DEFAULT
                    : indexed(samplers, samplerIndex, "sampler");
            textures.add(new TextureDefinition(textureLocation, sampler));
        }
        for (JsonElement element : array(root, "materials")) {
            JsonObject material = element.getAsJsonObject();
            JsonObject pbr = object(material, "pbrMetallicRoughness");
            GltfTextureInfo baseColorTexture = textureInfo(pbr, "baseColorTexture", textures);
            GltfTextureInfo metallicRoughnessTexture = textureInfo(pbr, "metallicRoughnessTexture", textures);
            GltfTextureInfo normalTexture = textureInfo(material, "normalTexture", textures);
            GltfTextureInfo occlusionTexture = textureInfo(material, "occlusionTexture", textures);
            GltfTextureInfo emissiveTexture = textureInfo(material, "emissiveTexture", textures);
            float[] factor = floatArray(pbr, "baseColorFactor", new float[]{1.0F, 1.0F, 1.0F, 1.0F});
            if (factor.length != 4) {
                throw error("baseColorFactor must contain four values");
            }
            float[] emissiveFactor = floatArray(material, "emissiveFactor", new float[]{0.0F, 0.0F, 0.0F});
            if (emissiveFactor.length != 3) {
                throw error("emissiveFactor must contain three values");
            }
            GltfMaterial.AlphaMode alphaMode;
            try {
                alphaMode = GltfMaterial.AlphaMode.valueOf(string(material, "alphaMode", "OPAQUE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw error("Unknown material alphaMode " + material.get("alphaMode"));
            }
            boolean unlit = material.has("extensions")
                    && material.getAsJsonObject("extensions").has("KHR_materials_unlit");
            result.add(new GltfMaterial(
                    baseColorTexture,
                    metallicRoughnessTexture,
                    normalTexture,
                    occlusionTexture,
                    emissiveTexture,
                    factor[0], factor[1], factor[2], factor[3],
                    floatValue(pbr, "metallicFactor", 1.0F),
                    floatValue(pbr, "roughnessFactor", 1.0F),
                    normalTexture == null ? 1.0F : floatValue(material.getAsJsonObject("normalTexture"), "scale", 1.0F),
                    occlusionTexture == null ? 1.0F : floatValue(material.getAsJsonObject("occlusionTexture"), "strength", 1.0F),
                    emissiveFactor[0], emissiveFactor[1], emissiveFactor[2],
                    alphaMode,
                    material.has("alphaCutoff") ? material.get("alphaCutoff").getAsFloat() : 0.5F,
                    booleanValue(material, "doubleSided", false),
                    unlit));
        }
        return result;
    }

    private GltfTextureInfo textureInfo(JsonObject parent, String name, List<TextureDefinition> textures) {
        if (!parent.has(name)) {
            return null;
        }
        JsonObject info = parent.getAsJsonObject(name);
        TextureDefinition texture = indexed(textures, requiredInt(info, "index"), "texture");
        int textureCoordinate = integer(info, "texCoord", 0);
        float[] offset = new float[]{0.0F, 0.0F};
        float[] scale = new float[]{1.0F, 1.0F};
        float rotation = 0.0F;
        if (info.has("extensions") && info.getAsJsonObject("extensions").has("KHR_texture_transform")) {
            JsonObject transform = info.getAsJsonObject("extensions").getAsJsonObject("KHR_texture_transform");
            offset = floatArray(transform, "offset", offset);
            scale = floatArray(transform, "scale", scale);
            rotation = floatValue(transform, "rotation", 0.0F);
            textureCoordinate = integer(transform, "texCoord", textureCoordinate);
        }
        if (textureCoordinate < 0 || textureCoordinate > 1) {
            throw error(name + " uses unsupported TEXCOORD_" + textureCoordinate);
        }
        if (offset.length != 2 || scale.length != 2) {
            throw error(name + " has an invalid KHR_texture_transform");
        }
        return new GltfTextureInfo(texture.location(), textureCoordinate, offset[0], offset[1],
                scale[0], scale[1], rotation, texture.sampler());
    }

    private void retainUsedEmbeddedTextures() {
        Set<ResourceLocation> usedTextures = new HashSet<>();
        for (GltfMaterial material : materials) {
            retainTexture(usedTextures, material.baseColorTexture());
            retainTexture(usedTextures, material.metallicRoughnessTexture());
            retainTexture(usedTextures, material.normalTexture());
            retainTexture(usedTextures, material.occlusionTexture());
            retainTexture(usedTextures, material.emissiveTexture());
        }
        embeddedTextures.keySet().retainAll(usedTextures);
    }

    private void retainTexture(Set<ResourceLocation> textures, GltfTextureInfo texture) {
        if (texture != null) {
            textures.add(texture.texture());
        }
    }

    private List<GltfNode> loadNodes(JsonArray definitions) {
        JsonArray meshDefinitions = array(root, "meshes");
        int[] parents = new int[definitions.size()];
        java.util.Arrays.fill(parents, -1);
        List<int[]> children = new ArrayList<>(definitions.size());
        for (int nodeIndex = 0; nodeIndex < definitions.size(); nodeIndex++) {
            JsonArray childDefinitions = array(definitions.get(nodeIndex).getAsJsonObject(), "children");
            int[] nodeChildren = new int[childDefinitions.size()];
            for (int childIndex = 0; childIndex < childDefinitions.size(); childIndex++) {
                int child = childDefinitions.get(childIndex).getAsInt();
                if (child < 0 || child >= definitions.size()) {
                    throw error("Node " + nodeIndex + " has an out-of-range child");
                }
                if (parents[child] >= 0) {
                    throw error("Node " + child + " has more than one parent");
                }
                parents[child] = nodeIndex;
                nodeChildren[childIndex] = child;
            }
            children.add(nodeChildren);
        }
        List<GltfNode> result = new ArrayList<>(definitions.size());
        for (int nodeIndex = 0; nodeIndex < definitions.size(); nodeIndex++) {
            JsonObject node = definitions.get(nodeIndex).getAsJsonObject();
            boolean hasMatrix = node.has("matrix");
            if (hasMatrix && (node.has("translation") || node.has("rotation") || node.has("scale"))) {
                throw error("Node " + nodeIndex + " defines both matrix and TRS transforms");
            }
            Matrix4f matrix = hasMatrix ? nodeTransform(node) : null;
            float[] translation = floatArray(node, "translation", new float[]{0.0F, 0.0F, 0.0F});
            float[] rotation = floatArray(node, "rotation", new float[]{0.0F, 0.0F, 0.0F, 1.0F});
            float[] scale = floatArray(node, "scale", new float[]{1.0F, 1.0F, 1.0F});
            if (translation.length != 3 || rotation.length != 4 || scale.length != 3) {
                throw error("Node " + nodeIndex + " has an invalid TRS component count");
            }
            int meshIndex = integer(node, "mesh", -1);
            if (meshIndex >= meshDefinitions.size()) {
                throw error("Node " + nodeIndex + " has an out-of-range mesh");
            }
            int morphTargetCount = meshIndex >= 0
                    ? meshMorphTargetCount(meshDefinitions.get(meshIndex).getAsJsonObject()) : 0;
            float[] weights = node.has("weights")
                    ? floatArray(node, "weights", null)
                    : meshIndex >= 0
                    ? floatArray(meshDefinitions.get(meshIndex).getAsJsonObject(), "weights", new float[morphTargetCount])
                    : new float[0];
            if (weights.length != morphTargetCount) {
                throw error("Node " + nodeIndex + " morph weight count does not match its mesh targets");
            }
            if (weights.length > 4) {
                throw error("Node " + nodeIndex + " exceeds the four-target GPU morph limit");
            }
            result.add(new GltfNode(
                    string(node, "name", "Node " + nodeIndex),
                    parents[nodeIndex],
                    children.get(nodeIndex),
                    meshIndex,
                    integer(node, "skin", -1),
                    matrix,
                    new Vector3f(translation[0], translation[1], translation[2]),
                    new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]).normalize(),
                    new Vector3f(scale[0], scale[1], scale[2]),
                    weights));
        }
        return result;
    }

    private int meshMorphTargetCount(JsonObject mesh) {
        int count = -1;
        for (JsonElement element : requiredArray(mesh, "primitives")) {
            int primitiveCount = array(element.getAsJsonObject(), "targets").size();
            if (count < 0) {
                count = primitiveCount;
            } else if (primitiveCount != count) {
                throw error("Mesh primitives have different morph target counts");
            }
        }
        return Math.max(count, 0);
    }

    private List<GltfScene> loadScenes(List<GltfNode> nodes) {
        JsonArray definitions = array(root, "scenes");
        List<GltfScene> result = new ArrayList<>();
        for (int sceneIndex = 0; sceneIndex < definitions.size(); sceneIndex++) {
            JsonObject scene = definitions.get(sceneIndex).getAsJsonObject();
            JsonArray roots = array(scene, "nodes");
            int[] rootIndices = new int[roots.size()];
            for (int index = 0; index < roots.size(); index++) {
                int node = roots.get(index).getAsInt();
                if (node < 0 || node >= nodes.size()) {
                    throw error("Scene " + sceneIndex + " has an out-of-range root node");
                }
                rootIndices[index] = node;
            }
            result.add(new GltfScene(string(scene, "name", "Scene " + sceneIndex), rootIndices));
        }
        if (result.isEmpty()) {
            int[] roots = new int[(int) nodes.stream().filter(node -> node.parent() < 0).count()];
            int output = 0;
            for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                if (nodes.get(nodeIndex).parent() < 0) {
                    roots[output++] = nodeIndex;
                }
            }
            result.add(new GltfScene("Default", roots));
        }
        return result;
    }

    private List<GltfAnimation> loadAnimations(List<GltfNode> nodes) {
        JsonArray definitions = array(root, "animations");
        List<GltfAnimation> result = new ArrayList<>();
        for (int animationIndex = 0; animationIndex < definitions.size(); animationIndex++) {
            JsonObject animation = definitions.get(animationIndex).getAsJsonObject();
            List<AnimationSampler> samplers = new ArrayList<>();
            for (JsonElement element : requiredArray(animation, "samplers")) {
                JsonObject sampler = element.getAsJsonObject();
                float[] times = readFloatAccessor(requiredInt(sampler, "input"), 1);
                if (times.length == 0) {
                    throw error("Animation " + animationIndex + " has an empty input accessor");
                }
                for (int key = 0; key < times.length; key++) {
                    if (times[key] < 0.0F || (key > 0 && times[key] <= times[key - 1])) {
                        throw error("Animation " + animationIndex + " input times are invalid");
                    }
                }
                GltfAnimation.Interpolation interpolation;
                try {
                    interpolation = GltfAnimation.Interpolation.valueOf(
                            string(sampler, "interpolation", "LINEAR").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw error("Animation " + animationIndex + " uses an unknown interpolation");
                }
                samplers.add(new AnimationSampler(times, requiredInt(sampler, "output"), interpolation));
            }
            List<GltfAnimation.Channel> channels = new ArrayList<>();
            float duration = 0.0F;
            for (JsonElement element : requiredArray(animation, "channels")) {
                JsonObject channel = element.getAsJsonObject();
                AnimationSampler sampler = indexed(samplers, requiredInt(channel, "sampler"), "animation sampler");
                JsonObject target = requiredObject(channel, "target");
                int nodeIndex = requiredInt(target, "node");
                GltfNode node = indexed(nodes, nodeIndex, "animation target node");
                String pathName = requiredString(target, "path").toUpperCase(Locale.ROOT);
                GltfAnimation.Path path;
                try {
                    path = GltfAnimation.Path.valueOf(pathName);
                } catch (IllegalArgumentException exception) {
                    throw error("Animation " + animationIndex + " uses an unknown target path");
                }
                if (node.matrix() != null) {
                    throw error("Animation " + animationIndex + " targets matrix-authored node " + nodeIndex);
                }
                int components = path == GltfAnimation.Path.ROTATION ? 4
                        : path == GltfAnimation.Path.WEIGHTS ? node.weights().length : 3;
                if (components == 0) {
                    throw error("Animation " + animationIndex + " targets weights on a node without morph targets");
                }
                float[] values = path == GltfAnimation.Path.WEIGHTS
                        ? readFloatAccessor(sampler.outputAccessor(), 1)
                        : readFloatAccessor(sampler.outputAccessor(), components);
                int multiplier = sampler.interpolation() == GltfAnimation.Interpolation.CUBICSPLINE ? 3 : 1;
                if (values.length != sampler.times().length * components * multiplier) {
                    throw error("Animation " + animationIndex + " output count does not match its input");
                }
                channels.add(new GltfAnimation.Channel(
                        nodeIndex, path, components, sampler.interpolation(), sampler.times(), values));
                duration = Math.max(duration, sampler.times()[sampler.times().length - 1]);
            }
            result.add(new GltfAnimation(string(animation, "name", "Animation " + animationIndex), duration, channels));
        }
        return result;
    }

    private List<GltfSkin> loadSkins(List<GltfNode> nodes) {
        JsonArray definitions = array(root, "skins");
        List<GltfSkin> result = new ArrayList<>();
        for (int skinIndex = 0; skinIndex < definitions.size(); skinIndex++) {
            JsonObject skin = definitions.get(skinIndex).getAsJsonObject();
            JsonArray jointDefinitions = requiredArray(skin, "joints");
            if (jointDefinitions.size() > 64) {
                throw error("Skin " + skinIndex + " exceeds the 64-joint GPU limit");
            }
            int[] joints = new int[jointDefinitions.size()];
            for (int jointIndex = 0; jointIndex < joints.length; jointIndex++) {
                int nodeIndex = jointDefinitions.get(jointIndex).getAsInt();
                indexed(nodes, nodeIndex, "skin joint node");
                joints[jointIndex] = nodeIndex;
            }
            List<Matrix4f> inverseBindMatrices = new ArrayList<>(joints.length);
            if (skin.has("inverseBindMatrices")) {
                float[] values = readFloatAccessor(skin.get("inverseBindMatrices").getAsInt(), 16);
                if (values.length != joints.length * 16) {
                    throw error("Skin " + skinIndex + " inverse bind matrix count does not match its joints");
                }
                for (int jointIndex = 0; jointIndex < joints.length; jointIndex++) {
                    float[] matrix = new float[16];
                    System.arraycopy(values, jointIndex * 16, matrix, 0, 16);
                    inverseBindMatrices.add(new Matrix4f().set(matrix));
                }
            } else {
                for (int jointIndex = 0; jointIndex < joints.length; jointIndex++) {
                    inverseBindMatrices.add(new Matrix4f());
                }
            }
            result.add(new GltfSkin(string(skin, "name", "Skin " + skinIndex), joints, inverseBindMatrices));
        }
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            int skinIndex = nodes.get(nodeIndex).skin();
            if (skinIndex >= 0) {
                indexed(result, skinIndex, "node skin");
            }
        }
        return result;
    }

    private Matrix4f nodeTransform(JsonObject node) {
        if (node.has("matrix")) {
            float[] values = floatArray(node, "matrix", null);
            if (values.length != 16) {
                throw error("Node matrix must contain sixteen values");
            }
            return new Matrix4f().set(values);
        }
        float[] translation = floatArray(node, "translation", new float[]{0.0F, 0.0F, 0.0F});
        float[] rotation = floatArray(node, "rotation", new float[]{0.0F, 0.0F, 0.0F, 1.0F});
        float[] scale = floatArray(node, "scale", new float[]{1.0F, 1.0F, 1.0F});
        if (translation.length != 3 || rotation.length != 4 || scale.length != 3) {
            throw error("Node translation, rotation, or scale has an invalid component count");
        }
        return new Matrix4f()
                .translation(translation[0], translation[1], translation[2])
                .rotate(new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]))
                .scale(scale[0], scale[1], scale[2]);
    }

    private ResourceLocation registerEmbeddedTexture(int index, GltfImageData image, GltfImageData fallback) {
        String path = "gltf_embedded/" + location.getNamespace() + "/" + location.getPath() + "/" + index;
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(ModMetadata.MOD_ID, path);
        embeddedTextures.put(texture, new GltfEmbeddedTexture(image, fallback));
        return texture;
    }

    private boolean isKtx2(byte[] bytes, String mimeType) {
        byte[] identifier = new byte[]{(byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};
        if ("image/ktx2".equalsIgnoreCase(mimeType)) {
            return true;
        }
        if (bytes.length < identifier.length) {
            return false;
        }
        for (int index = 0; index < identifier.length; index++) {
            if (bytes[index] != identifier[index]) {
                return false;
            }
        }
        return true;
    }

    private byte[] loadUriBytes(String uri) {
        if (uri.startsWith("data:")) {
            return decodeDataUri(uri);
        }
        ResourceLocation target = resolveRelative(uri);
        try {
            Resource resource = resourceManager.getResource(target)
                    .orElseThrow(() -> error("Missing glTF dependency " + target));
            try (var input = resource.open()) {
                return readLimited(input, target.toString());
            }
        } catch (IOException exception) {
            throw new GltfLoadException("Failed to read glTF dependency " + target, exception);
        }
    }

    private ResourceLocation resolveRelative(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (parsed.isAbsolute() || parsed.getAuthority() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
                throw error("External URI must be a relative resource-pack path: " + uri);
            }
            String decoded = parsed.getPath();
            int separator = location.getPath().lastIndexOf('/');
            String base = separator < 0 ? "" : location.getPath().substring(0, separator + 1);
            Deque<String> parts = new ArrayDeque<>();
            for (String part : (base + decoded).replace('\\', '/').split("/")) {
                if (part.isEmpty() || ".".equals(part)) {
                    continue;
                }
                if ("..".equals(part)) {
                    if (parts.isEmpty()) {
                        throw error("Relative URI escapes its resource namespace: " + uri);
                    }
                    parts.removeLast();
                } else {
                    parts.addLast(part);
                }
            }
            return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), String.join("/", parts));
        } catch (IllegalArgumentException exception) {
            throw new GltfLoadException("Invalid relative URI " + uri + " in " + location, exception);
        }
    }

    private byte[] decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            throw error("Malformed data URI");
        }
        String metadata = uri.substring(5, comma);
        String data = uri.substring(comma + 1);
        try {
            if (metadata.endsWith(";base64")) {
                return Base64.getDecoder().decode(data);
            }
            return java.net.URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException exception) {
            throw new GltfLoadException("Malformed data URI in " + location, exception);
        }
    }

    private static byte[] readLimited(java.io.InputStream input, String resourceName) throws IOException {
        byte[] bytes = input.readNBytes(ModMetadata.MAX_RESOURCE_BYTES + 1);
        if (bytes.length > ModMetadata.MAX_RESOURCE_BYTES) {
            throw new GltfLoadException("glTF resource exceeds the "
                    + (ModMetadata.MAX_RESOURCE_BYTES / 1024 / 1024) + " MiB limit: " + resourceName);
        }
        return bytes;
    }

    private static int checkedArrayLength(int count, int components, int componentBytes, String subject) {
        long length = (long) count * components;
        long bytes = length * componentBytes;
        if (count < 0 || components <= 0 || length > Integer.MAX_VALUE
                || bytes > ModMetadata.MAX_DECODED_BUFFER_BYTES) {
            throw new GltfLoadException(subject + " exceeds the decoded buffer limit");
        }
        return (int) length;
    }

    private ParsedContainer parseContainer(byte[] bytes) {
        if (bytes.length >= 4 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == GLB_MAGIC) {
            if (bytes.length < 12) {
                throw error("GLB header is truncated");
            }
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = data.getInt();
            int version = data.getInt();
            long declaredLength = Integer.toUnsignedLong(data.getInt());
            if (magic != GLB_MAGIC || version != 2 || declaredLength != bytes.length) {
                throw error("Invalid GLB 2.0 header");
            }
            String json = null;
            byte[] binary = null;
            while (data.remaining() >= 8) {
                long chunkLengthLong = Integer.toUnsignedLong(data.getInt());
                int chunkType = data.getInt();
                if (chunkLengthLong > data.remaining() || chunkLengthLong > Integer.MAX_VALUE) {
                    throw error("GLB chunk is out of range");
                }
                int chunkLength = (int) chunkLengthLong;
                byte[] chunk = new byte[chunkLength];
                data.get(chunk);
                if (chunkType == JSON_CHUNK && json == null) {
                    json = new String(chunk, StandardCharsets.UTF_8).trim();
                } else if (chunkType == BIN_CHUNK && binary == null) {
                    binary = chunk;
                }
            }
            if (json == null || data.hasRemaining()) {
                throw error("GLB has no valid JSON chunk");
            }
            return new ParsedContainer(json, binary);
        }
        return new ParsedContainer(new String(bytes, StandardCharsets.UTF_8), null);
    }

    private void verifyAsset() {
        JsonObject asset = requiredObject(root, "asset");
        String version = requiredString(asset, "version");
        if (!version.startsWith("2.")) {
            throw error("Only glTF 2.x assets are supported, found " + version);
        }
        for (JsonElement extension : array(root, "extensionsRequired")) {
            String name = extension.getAsString();
            if (!Set.of("KHR_materials_unlit", "KHR_texture_transform", "KHR_mesh_quantization",
                    "EXT_mesh_gpu_instancing", "KHR_draco_mesh_compression", "EXT_meshopt_compression",
                    "KHR_texture_basisu").contains(name)) {
                throw error("Required glTF extension is not supported: " + name);
            }
        }
    }

    private void validateReadRange(byte[] buffer, BufferView view, int accessorOffset, int count,
                                   int stride, int elementSize) {
        if (count < 0 || view.byteOffset() < 0 || view.byteLength() < 0 || accessorOffset < 0 || stride < elementSize) {
            throw error("Accessor or buffer view contains an invalid range");
        }
        long endInView = count == 0 ? accessorOffset : (long) accessorOffset + (long) (count - 1) * stride + elementSize;
        long absoluteEnd = (long) view.byteOffset() + endInView;
        if (endInView > view.byteLength() || absoluteEnd > buffer.length) {
            throw error("Accessor reads outside its buffer view");
        }
    }

    private float readComponentAsFloat(ByteBuffer data, int offset, int componentType, boolean normalized) {
        return switch (componentType) {
            case 5120 -> normalized ? Math.max(data.get(offset) / 127.0F, -1.0F) : data.get(offset);
            case 5121 -> normalized ? Byte.toUnsignedInt(data.get(offset)) / 255.0F : Byte.toUnsignedInt(data.get(offset));
            case 5122 -> normalized ? Math.max(data.getShort(offset) / 32767.0F, -1.0F) : data.getShort(offset);
            case 5123 -> normalized ? Short.toUnsignedInt(data.getShort(offset)) / 65535.0F : Short.toUnsignedInt(data.getShort(offset));
            case 5125 -> {
                long value = Integer.toUnsignedLong(data.getInt(offset));
                yield normalized ? (float) (value / 4294967295.0) : value;
            }
            case 5126 -> data.getFloat(offset);
            default -> throw error("Unsupported accessor component type " + componentType);
        };
    }

    private int componentSize(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw error("Unsupported accessor component type " + componentType);
        };
    }

    private int componentCount(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw error("Unsupported accessor type " + type);
        };
    }

    private int[] triangulate(int[] source, int mode) {
        if (mode == 4) {
            if (source.length % 3 != 0) {
                throw error("Triangle primitive index count is not divisible by three");
            }
            return source;
        }
        if (source.length < 3) {
            return new int[0];
        }
        int[] result = new int[(source.length - 2) * 3];
        int output = 0;
        if (mode == 5) {
            for (int index = 0; index < source.length - 2; index++) {
                if ((index & 1) == 0) {
                    result[output++] = source[index];
                    result[output++] = source[index + 1];
                } else {
                    result[output++] = source[index + 1];
                    result[output++] = source[index];
                }
                result[output++] = source[index + 2];
            }
        } else {
            for (int index = 1; index < source.length - 1; index++) {
                result[output++] = source[0];
                result[output++] = source[index];
                result[output++] = source[index + 1];
            }
        }
        return result;
    }

    private float[] generateNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int index = 0; index < indices.length; index += 3) {
            int a = indices[index] * 3;
            int b = indices[index + 1] * 3;
            int c = indices[index + 2] * 3;
            edgeA.set(positions[b] - positions[a], positions[b + 1] - positions[a + 1], positions[b + 2] - positions[a + 2]);
            edgeB.set(positions[c] - positions[a], positions[c + 1] - positions[a + 1], positions[c + 2] - positions[a + 2]);
            edgeA.cross(edgeB, normal);
            addNormal(normals, a, normal);
            addNormal(normals, b, normal);
            addNormal(normals, c, normal);
        }
        for (int offset = 0; offset < normals.length; offset += 3) {
            normal.set(normals[offset], normals[offset + 1], normals[offset + 2]);
            if (normal.lengthSquared() > 0.0F) {
                normal.normalize();
                normals[offset] = normal.x;
                normals[offset + 1] = normal.y;
                normals[offset + 2] = normal.z;
            } else {
                normals[offset + 1] = 1.0F;
            }
        }
        return normals;
    }

    private void addNormal(float[] normals, int offset, Vector3f normal) {
        normals[offset] += normal.x;
        normals[offset + 1] += normal.y;
        normals[offset + 2] += normal.z;
    }

    private int[] sequentialIndices(int count) {
        int[] indices = new int[count];
        for (int index = 0; index < count; index++) {
            indices[index] = index;
        }
        return indices;
    }

    private void validateIndices(int[] indices, int vertexCount) {
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw error("Primitive index " + index + " is outside its POSITION accessor");
            }
        }
    }

    private float[] filledColors(int vertexCount) {
        float[] colors = new float[vertexCount * 4];
        java.util.Arrays.fill(colors, 1.0F);
        return colors;
    }

    private void requireVertexAttributeSize(String name, int actual, int expected) {
        if (actual != expected) {
            throw error(name + " accessor count does not match POSITION");
        }
    }

    private GltfMaterial defaultMaterial() {
        return new GltfMaterial(null, null, null, null, null,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                0.0F, 0.0F, 0.0F, GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
    }

    private JsonArray array(JsonObject object, String name) {
        return object.has(name) ? object.getAsJsonArray(name) : new JsonArray();
    }

    private JsonArray requiredArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) {
            throw error("Missing required array " + name);
        }
        return object.getAsJsonArray(name);
    }

    private JsonObject object(JsonObject object, String name) {
        return object.has(name) ? object.getAsJsonObject(name) : new JsonObject();
    }

    private JsonObject requiredObject(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonObject()) {
            throw error("Missing required object " + name);
        }
        return object.getAsJsonObject(name);
    }

    private int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    private int requiredInt(JsonObject object, String name) {
        if (!object.has(name)) {
            throw error("Missing required integer " + name);
        }
        return object.get(name).getAsInt();
    }

    private String string(JsonObject object, String name, String fallback) {
        return object.has(name) ? object.get(name).getAsString() : fallback;
    }

    private String requiredString(JsonObject object, String name) {
        if (!object.has(name)) {
            throw error("Missing required string " + name);
        }
        return object.get(name).getAsString();
    }

    private boolean booleanValue(JsonObject object, String name, boolean fallback) {
        return object.has(name) ? object.get(name).getAsBoolean() : fallback;
    }

    private float[] floatArray(JsonObject object, String name, float[] fallback) {
        if (!object.has(name)) {
            return fallback;
        }
        JsonArray values = object.getAsJsonArray(name);
        float[] result = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index).getAsFloat();
        }
        return result;
    }

    private float floatValue(JsonObject object, String name, float fallback) {
        return object.has(name) ? object.get(name).getAsFloat() : fallback;
    }

    private <T> T indexed(List<T> values, int index, String type) {
        if (index < 0 || index >= values.size()) {
            throw error("Invalid " + type + " index " + index);
        }
        return values.get(index);
    }

    private GltfLoadException error(String message) {
        return new GltfLoadException(message + " in " + location);
    }

    private record ParsedContainer(String json, byte[] binaryChunk) {
    }

    private record BufferView(int buffer, int byteOffset, int byteLength, int byteStride) {
    }

    private record Accessor(int bufferView, int byteOffset, int componentType, boolean normalized, int count,
                            String type, SparseAccessor sparse) {
    }

    private record SparseAccessor(int count, int indicesBufferView, int indicesByteOffset,
                                  int indicesComponentType, int valuesBufferView, int valuesByteOffset) {
    }

    private record TextureDefinition(ResourceLocation location, GltfSampler sampler) {
    }

    private record AnimationSampler(float[] times, int outputAccessor, GltfAnimation.Interpolation interpolation) {
    }

    private record DracoPrimitiveData(DracoMesh mesh, Map<String, Integer> attributeIds, int[] indices) {
    }
}
