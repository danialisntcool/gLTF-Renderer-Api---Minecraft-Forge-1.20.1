package me.danialisntcool.gltfapi;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import me.danialisntcool.gltfapi.client.gltf.GltfInstancingSmoke;
import me.danialisntcool.gltfapi.client.gltf.GltfBufferPoolSmoke;
import com.mojang.blaze3d.systems.RenderSystem;

public final class GltfShaderCompileSmoke {
    public static void main(String[] arguments) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("Could not initialize the shader compiler context");
        long window = 0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(16, 16, "glTF shader validation", 0, 0);
            if (window == 0) throw new IllegalStateException("Could not create an OpenGL 3.2 context");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            var config = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
            me.danialisntcool.gltfapi.client.GltfClientConfig.SPEC.correct(config);
            me.danialisntcool.gltfapi.client.GltfClientConfig.SPEC.setConfig(config);
            String fog = resource("/assets/minecraft/shaders/include/fog.glsl")
                    .replaceAll("(?m)^#version[^\\r\\n]*", "");
            if (arguments.length > 0) {
                try (ZipFile reference = new ZipFile(arguments[0]);
                     InputStream input = reference.getInputStream(reference.getEntry(
                             "assets/gltf_renderer_api/shaders/core/gltf_model.fsh"))) {
                    String oldFragment = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("#moj_import <fog.glsl>", fog);
                    boolean reproduced = false;
                    try {
                        GltfMaterialShaderSmoke.verify(oldFragment);
                    } catch (IllegalStateException exception) {
                        if (!exception.getMessage().startsWith("Normal-map detail changes")) throw exception;
                        System.out.println("Previous JAR reproduced the bug: " + exception.getMessage());
                        reproduced = true;
                    }
                    if (!reproduced) throw new IllegalStateException("Reference JAR did not reproduce the normal-map bug");
                }
            }
            int fragment = compile(GL20.GL_FRAGMENT_SHADER, "gltf_model.fsh", fog);
            try {
                link("gltf_model.vsh", fragment, fog);
                link("gltf_buffered_model.vsh", fragment, fog);
                GltfMaterialShaderSmoke.verify(resource("/assets/gltf_renderer_api/shaders/core/gltf_model.fsh")
                        .replace("#moj_import <fog.glsl>", fog));
                GltfInstancingSmoke.verify(resource("/assets/gltf_renderer_api/shaders/core/gltf_model.vsh")
                        .replace("#moj_import <fog.glsl>", fog));
                GltfBufferPoolSmoke.verify();
                me.danialisntcool.gltfapi.client.gltf.GltfPersistentStreamSmoke.verify();
                int iconFragment = compile(GL20.GL_FRAGMENT_SHADER, "gltf_icon.fsh", fog);
                try {
                    link("gltf_icon.vsh", iconFragment, fog);
                } finally {
                    GL20.glDeleteShader(iconFragment);
                }
                me.danialisntcool.gltfapi.client.gltf.GltfGpuTimingSmoke.verify();
                GltfIconShaderSmoke.verify(resource("/assets/gltf_renderer_api/shaders/core/gltf_icon.vsh"),
                        resource("/assets/gltf_renderer_api/shaders/core/gltf_icon.fsh"));
                me.danialisntcool.gltfapi.client.gltf.GltfIconStateSmoke.verify();
                me.danialisntcool.gltfapi.client.gltf.GltfIconQueueSmoke.verify();
                System.out.println("glTF native and buffered GLSL 150 shaders compiled and linked successfully");
            } finally {
                GL20.glDeleteShader(fragment);
            }
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void link(String vertexName, int fragment, String fog) throws Exception {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexName, fog);
        int program = GL20.glCreateProgram();
        try {
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(vertexName + ": " + GL20.glGetProgramInfoLog(program));
            }
        } finally {
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertex);
        }
    }

    private static int compile(int type, String name, String fog) throws Exception {
        String source = resource("/assets/gltf_renderer_api/shaders/core/" + name)
                .replace("#moj_import <fog.glsl>", fog);
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(name + ": " + error);
        }
        return shader;
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = GltfShaderCompileSmoke.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing shader resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
