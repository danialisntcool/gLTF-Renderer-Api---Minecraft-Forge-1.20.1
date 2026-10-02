package me.danialisntcool.gltfapi;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

final class GltfMaterialShaderSmoke {
    static void verify(String fragmentSource) {
        String vertexSource = """
                #version 150
                in vec2 Position;
                uniform float Footprint;
                uniform vec3 ViewOffset;
                out float vertexDistance;
                out vec4 vertexColor;
                out vec4 lightMapColor;
                out vec4 overlayColor;
                out vec2 primaryTextureCoordinates;
                out vec2 secondaryTextureCoordinates;
                out vec3 viewPosition;
                out vec3 viewNormal;
                void main() {
                    gl_Position = vec4(Position, 0, 1);
                    vertexDistance = 0;
                    vertexColor = vec4(1);
                    lightMapColor = vec4(1);
                    overlayColor = vec4(0, 0, 0, 1);
                    primaryTextureCoordinates = Position * Footprint * 0.5 + 0.5;
                    secondaryTextureCoordinates = primaryTextureCoordinates;
                    viewPosition = vec3(Position, -4) + ViewOffset;
                    viewNormal = vec3(0, 0, 1);
                }
                """;
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL20.glCreateProgram();
        int vao = GL30.glGenVertexArrays();
        int buffer = GL15.glGenBuffers();
        int target = GL30.glGenFramebuffers();
        int color = GL11.glGenTextures();
        int normal = GL11.glGenTextures();
        int environment = GL11.glGenTextures();
        FloatBuffer panorama = MemoryUtil.memAllocFloat(128 * 64 * 4);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    stack.floats(-1, -1, 3, -1, -1, 3), GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 32, 32, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Material test framebuffer is incomplete");
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normal);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, stack.floats(0.8F, 0.5F, 0.8F, 1));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normal);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler3"), 3);
            uniform(program, "Footprint", 1.0F);
            uniform(program, "RoughnessFactor", 0.8F);
            uniform(program, "NormalScale", 1.0F);
            uniform(program, "OcclusionStrength", 1.0F);
            uniform(program, "FogStart", 100.0F);
            uniform(program, "FogEnd", 200.0F);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "BaseColorFactor"), 0.35F, 0.35F, 0.35F, 1);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "Light0_Direction"), 1, 0, 1);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "NormalUvTransform"), 0, 0, 1, 1);
            GL11.glViewport(0, 0, 32, 32);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            float far = normalEffect(program, 1.0F, stack);
            float close = normalEffect(program, 0.001F, stack);
            if (far < 0.01F || close < 0.01F || Math.abs(far - close) > 0.005F) {
                throw new IllegalStateException("Normal-map detail changes with pixel footprint: far=" + far + ", close=" + close);
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("Material test GL error " + error);
            System.out.printf(java.util.Locale.ROOT,
                    "Normal-map GPU regression passed: far effect %.5f, close effect %.5f%n", far, close);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, environment);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 128; x++) {
                    float value = x > 84 && x < 110 ? 0.95F : 0.15F;
                    panorama.put(value).put(value).put(value).put(1);
                }
            }
            panorama.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 128, 64, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, panorama);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler7"), 7);
            GL20.glUniformMatrix3fv(GL20.glGetUniformLocation(program, "IViewRotMat"), false,
                    stack.floats(1, 0, 0, 0, 1, 0, 0, 0, 1));
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "HasNormalTexture"), 0);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "Light0_Direction"), 0, 0, 0);
            uniform(program, "MetallicFactor", 1);
            uniform(program, "RoughnessFactor", 0.04F);
            uniform(program, "EnvironmentStrength", 0);
            float unreflected = sample(stack);
            uniform(program, "EnvironmentStrength", 1);
            float smoothMetal = sample(stack);
            uniform(program, "RoughnessFactor", 1);
            float roughMetal = sample(stack);
            uniform(program, "MetallicFactor", 0);
            float dielectric = sample(stack);
            if (!Float.isFinite(smoothMetal) || !Float.isFinite(roughMetal)
                    || smoothMetal < unreflected + 0.02F || Math.abs(smoothMetal - roughMetal) < 0.02F
                    || Math.abs(roughMetal - dielectric) < 0.02F) {
                throw new IllegalStateException("Metallic environment or roughness response failed: "
                        + unreflected + ", " + smoothMetal + ", " + roughMetal + ", " + dielectric);
            }
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Reflection test GL error");
            System.out.printf(java.util.Locale.ROOT,
                    "Static reflection GPU regression passed: smooth metal %.5f, rough metal %.5f, dielectric %.5f%n",
                    smoothMetal, roughMetal, dielectric);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "GuiMode"), 1);
            uniform(program, "MetallicFactor", 1);
            uniform(program, "RoughnessFactor", 0.2F);
            float gui = sample(stack);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "ViewOffset"), 100, -80, -500);
            GL20.glUniformMatrix3fv(GL20.glGetUniformLocation(program, "IViewRotMat"), false,
                    stack.floats(0, 0, -1, 0, 1, 0, 1, 0, 0));
            float guiMoved = sample(stack);
            if (!Float.isFinite(guiMoved) || Math.abs(gui - guiMoved) > 0.0001F) {
                throw new IllegalStateException("GUI reflection depends on inventory slot/world camera: " + gui + ", " + guiMoved);
            }
            System.out.printf(java.util.Locale.ROOT,
                    "GUI reflection GPU regression passed: original %.5f, translated/world camera rotated %.5f%n", gui, guiMoved);
        } finally {
            MemoryUtil.memFree(panorama);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(target);
            GL11.glDeleteTextures(color);
            GL11.glDeleteTextures(normal);
            GL11.glDeleteTextures(environment);
            GL15.glDeleteBuffers(buffer);
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);
        }
    }

    private static float normalEffect(int program, float footprint, MemoryStack stack) {
        uniform(program, "Footprint", footprint);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "HasNormalTexture"), 0);
        float plain = sample(stack);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "HasNormalTexture"), 1);
        return Math.abs(sample(stack) - plain);
    }

    private static float sample(MemoryStack stack) {
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        FloatBuffer pixel = stack.mallocFloat(4);
        GL11.glReadPixels(16, 16, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        return pixel.get(0);
    }

    private static void uniform(int program, String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
