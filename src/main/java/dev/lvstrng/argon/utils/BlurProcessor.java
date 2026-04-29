package dev.lvstrng.argon.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import java.lang.reflect.Field;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class BlurProcessor {
    private static Framebuffer blurBuffer;
    private static Framebuffer secondBuffer;
    private static int blurShader = -1;
    private static int panelBlitShader = -1;
    private static int vao = -1;
    private static int vbo = -1;

    private static final Map<String, Integer> uniformCache = new HashMap<>();

    private static void init() {
        if (blurShader != -1)
            return;

        blurShader = loadShader("assets/argon/shaders/post.vert", "assets/argon/shaders/blur.frag");
        panelBlitShader = loadShader("assets/argon/shaders/post.vert", "assets/argon/shaders/panelblit.frag");

        validateProgram(blurShader, "Blur Shader");
        validateProgram(panelBlitShader, "Panel Blit Shader");

        GL20.glUseProgram(blurShader);
        GL20.glUniform1i(getUniformLoc(blurShader, "screenTexture"), 0);

        GL20.glUseProgram(panelBlitShader);
        GL20.glUniform1i(getUniformLoc(panelBlitShader, "blurTexture"), 0);
        GL20.glUseProgram(0);

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        float[] vertices = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                1f, 1f, 1f, 1f,
                -1f, 1f, 0f, 1f
        };

        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL30.glBindVertexArray(0);
    }

    private static int getUniformLoc(int program, String name) {
        String key = program + ":" + name;
        return uniformCache.computeIfAbsent(key, k -> GL20.glGetUniformLocation(program, name));
    }

    private static int getFboId(Framebuffer fb) {
        try {
            Field f = Framebuffer.class.getDeclaredField("fbo");
            f.setAccessible(true);
            return f.getInt(fb);
        } catch (Exception e) {
            try {
                Field f = Framebuffer.class.getDeclaredField("fboId");
                f.setAccessible(true);
                return f.getInt(fb);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static int getTextureId(Object texture) {
        if (texture == null) return 0;
        try {
            // In 1.21.11, GpuTexture implementations (like GlTexture) store the ID in a field
            Class<?> clazz = texture.getClass();
            while (clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == int.class && (f.getName().equalsIgnoreCase("id") || f.getName().equalsIgnoreCase("glId"))) {
                        f.setAccessible(true);
                        return f.getInt(texture);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static void renderBlur(float radius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc.getWindow().getFramebufferWidth();
        int height = mc.getWindow().getFramebufferHeight();

        if (blurBuffer == null || blurBuffer.textureWidth != width || blurBuffer.textureHeight != height) {
            if (blurBuffer != null)
                blurBuffer.delete();
            blurBuffer = new SimpleFramebuffer("blur", width, height, false);
            if (secondBuffer != null)
                secondBuffer.delete();
            secondBuffer = new SimpleFramebuffer("second", width, height, false);
        }

        init();

        RenderSystem.assertOnRenderThread();

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GL11.glDisable(GL11.GL_DEPTH_TEST);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, getFboId(mc.getFramebuffer()));
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, getFboId(blurBuffer));
        GL11.glViewport(0, 0, width, height);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GL30.glBindVertexArray(vao);
        GL20.glUseProgram(blurShader);
        GL20.glUniform1f(getUniformLoc(blurShader, "radius"), radius);

        // Horizontal pass
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, getFboId(secondBuffer));
        GL11.glViewport(0, 0, width, height);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, getTextureId(blurBuffer.getColorAttachment()));
        GL20.glUniform2f(getUniformLoc(blurShader, "direction"), 1f / width, 0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        // Vertical pass
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, getFboId(blurBuffer));
        GL11.glViewport(0, 0, width, height);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, getTextureId(secondBuffer.getColorAttachment()));
        GL20.glUniform2f(getUniformLoc(blurShader, "direction"), 0f, 1f / height);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
    }

    public static void drawPanelBlur(int x, int y, int w, int h, float radius) {
        if (blurBuffer == null) return;
        init();

        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.getWindow().getScaleFactor();
        int fbW = mc.getWindow().getFramebufferWidth();
        int fbH = mc.getWindow().getFramebufferHeight();

        float nx1 = (float) (x * scale) / fbW * 2 - 1;
        float ny1 = (float) (fbH - (y + h) * scale) / fbH * 2 - 1;
        float nx2 = (float) ((x + w) * scale) / fbW * 2 - 1;
        float ny2 = (float) (fbH - y * scale) / fbH * 2 - 1;

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL30.glBindVertexArray(vao);
        GL20.glUseProgram(panelBlitShader);

        float[] vertices = {
                nx1, ny1, (float) (x * scale) / fbW, (float) (fbH - (y + h) * scale) / fbH,
                nx2, ny1, (float) ((x + w) * scale) / fbW, (float) (fbH - (y + h) * scale) / fbH,
                nx2, ny2, (float) ((x + w) * scale) / fbW, (float) (fbH - y * scale) / fbH,
                nx1, ny2, (float) (x * scale) / fbW, (float) (fbH - y * scale) / fbH
        };
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buffer);

        GL20.glUniform2f(getUniformLoc(panelBlitShader, "rectSize"), (float) (w * scale), (float) (h * scale));
        GL20.glUniform1f(getUniformLoc(panelBlitShader, "cornerRadius"), (float) (radius * scale));

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, getTextureId(blurBuffer.getColorAttachment()));

        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
    }

    private static int loadShader(String vertPath, String fragPath) {
        int vert = compileShader(vertPath, GL20.GL_VERTEX_SHADER);
        int frag = compileShader(fragPath, GL20.GL_FRAGMENT_SHADER);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glLinkProgram(program);
        return program;
    }

    private static int compileShader(String path, int type) {
        try {
            InputStream is = BlurProcessor.class.getClassLoader().getResourceAsStream(path);
            String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, src);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                System.err.println("Shader Compile Error (" + path + "): " + GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private static void validateProgram(int program, String name) {
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.err.println(name + " Link Error: " + GL20.glGetProgramInfoLog(program));
        }
    }
}
