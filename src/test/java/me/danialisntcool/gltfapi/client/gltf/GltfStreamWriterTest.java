package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GltfStreamWriterTest {
    private static GltfPrimitive primitive() {
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                0.5F, 0.25F, 1, 1, 1, 0.5F, 1, 1, 0, 0, 0,
                GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        return new GltfPrimitive(new float[]{1, 2, 3, -1, 0, 2},
                new float[]{1, 1, 0, 0, 0, 1}, new float[]{0.2F, 0.3F, 0.4F, 0.5F},
                new float[]{0.7F, 0.8F, 0.9F, 1}, new float[]{1, 1, 1, 1, 1, 1, 1, 1},
                new float[8], new float[8], new float[0][], new float[0][],
                new int[]{0, 1, 0}, material, new Matrix4f(), 0, -1, List.of(new Matrix4f()));
    }

    @AfterEach
    void release() {
        GltfStreamWriter.clear();
    }

    @Test
    void preservesBothUvsLightOverlayAndLinearVertexColorForPbr() {
        GltfPrimitive primitive = primitive();
        Matrix4f pose = new Matrix4f().translate(4, 5, 6).rotateY(0.4F).scale(-2, 3, 4);
        Matrix3f normal = new Matrix3f(pose).invert().transpose();
        ByteBuffer bytes = GltfStreamWriter.indexed(primitive,
                new GltfDeformedGeometry(primitive.positions(), primitive.normals()), pose, normal,
                0x00f000b0, 0x000a0004, true);
        assertEquals(88, bytes.remaining());
        Vector3f expected = pose.transformPosition(new Vector3f(1, 2, 3));
        assertEquals(expected.x, bytes.getFloat(0), 0.00001F);
        assertEquals(expected.y, bytes.getFloat(4), 0.00001F);
        assertEquals(expected.z, bytes.getFloat(8), 0.00001F);
        assertEquals(255, bytes.get(12) & 255);
        assertEquals(0.2F, bytes.getFloat(16));
        assertEquals(0.7F, bytes.getFloat(36));
        assertEquals(0.8F, bytes.getFloat(40));
        assertEquals(0x000a0004, bytes.getInt(24));
        assertEquals(0x00f000b0, bytes.getInt(28));
        expected = normal.transform(new Vector3f(1, 1, 0)).normalize();
        assertEquals(expected.x, bytes.get(32) / 127.0F, 0.008F);
        assertEquals(expected.y, bytes.get(33) / 127.0F, 0.008F);
        assertEquals(expected.z, bytes.get(34) / 127.0F, 0.008F);
    }

    @Test
    void appliesMaterialFactorsForStandardEntityLayout() {
        GltfPrimitive primitive = primitive();
        ByteBuffer bytes = GltfStreamWriter.indexed(primitive,
                new GltfDeformedGeometry(primitive.positions(), primitive.normals()),
                new Matrix4f(), new Matrix3f(), 240, 0, false);
        assertEquals(72, bytes.remaining());
        assertEquals(127, bytes.get(12) & 255);
        assertEquals(63, bytes.get(13) & 255);
        assertEquals(255, bytes.get(14) & 255);
        assertEquals(0.4F, bytes.getFloat(36 + 16));
    }

    @Test
    void instanceMatricesUseColumnsAndKeepLightingPerInstance() {
        Matrix4f pose = new Matrix4f().translate(4, 5, 6).rotateX(0.6F).scale(2, 3, 4);
        float[] data = new float[32];
        Matrix3f normal = new Matrix3f();
        GltfNativeBatch.pack(pose, 0x00f000b0, 0x000a0004, data, normal);
        assertEquals(4, data[12]);
        assertEquals(5, data[13]);
        assertEquals(6, data[14]);
        Matrix3f expected = new Matrix3f(pose).invert().transpose();
        assertEquals(expected.m00(), data[16]);
        assertEquals(expected.m12(), data[22]);
        assertEquals(176, data[28]);
        assertEquals(240, data[29]);
        assertEquals(4, data[30]);
        assertEquals(10, data[31]);
    }

    @Test
    void staticAttributeReuseDoesNotReuseDynamicPositionsOrLighting() {
        GltfPrimitive primitive = primitive();
        GltfDeformedGeometry geometry = new GltfDeformedGeometry(primitive.positions(), primitive.normals());
        GltfStreamWriter.indexed(primitive, geometry, new Matrix4f(), new Matrix3f(), 240, 0, true);
        ByteBuffer reused = GltfStreamWriter.indexed(primitive, geometry, new Matrix4f().translate(3, 0, 0),
                new Matrix3f(), 0x00f000f0, 0x000b0005, true);
        assertEquals(4, reused.getFloat(0));
        assertEquals(0x00f000f0, reused.getInt(28));
        assertEquals(0x000b0005, reused.getInt(24));
        assertEquals(0.2F, reused.getFloat(16));
        assertEquals(0.7F, reused.getFloat(36));
        assertEquals(255, reused.get(12) & 255);
    }
}
