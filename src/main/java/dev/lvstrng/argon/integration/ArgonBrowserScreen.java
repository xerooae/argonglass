package dev.lvstrng.argon.integration;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.ccbluex.liquidbounce.mcef.MCEF;
import dev.lvstrng.argon.utils.BlurProcessor;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings;
import org.lwjgl.opengl.GL11;

/**
 * ArgonBrowserScreen — Phase 3 of the LiquidBounce GUI port.
 *
 * Replaces Argon's native Java ClickGui with an MCEF Chromium browser window
 * pointed at the LiquidBounce Svelte app hosted by ArgonInteropServer.
 *
 * MCEF API (net.ccbluex:mcef) mirrors JCEF. The key classes are:
 *   - MCEFBrowser : the embedded Chromium browser instance
 *   - MCEFClient  : browser event callbacks
 *
 * This screen creates a browser on open, loads the GUI URL, forwards
 * all mouse/keyboard events into the browser, and renders the browser
 * texture each frame using raw GL.
 */
public class ArgonBrowserScreen extends Screen {

    /** The URL the Svelte app is served at. Points to our interop server. */
    private final String url;

    // MCEF browser instance
    private static MCEFBrowser browser;
    private int winWidth;
    private int winHeight;
    private boolean textureFound = false;

    // Core-Profile VAO/VBO for blitting the MCEF texture
    private int quadVao = -1;
    private int quadVbo = -1;
    private int blitProgram = -1;
    
    private static boolean handlersAdded = false;
    public static boolean browserReady = false;

    public ArgonBrowserScreen(String url) {
        super(Text.literal("Argon GUI"));
        this.url = url;
    }

    @Override
    protected void init() {
        super.init();
        this.winWidth  = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
        this.winHeight = MinecraftClient.getInstance().getWindow().getFramebufferHeight();
        initQuad();
        initBrowser();
    }

    /**
     * Creates a fullscreen quad VAO/VBO and a minimal GLSL blit program.
     * This is necessary because Minecraft 1.21.x uses an OpenGL Core Profile
     * which does NOT support legacy glBegin/glEnd immediate-mode rendering.
     */
    private void initQuad() {
        // Vertex layout: (x, y, u, v) — NDC coords + UV
        // Triangle-strip covering the full screen: bottom-left, bottom-right, top-left, top-right
        // MCEF texture origin is top-left → UV (0,0) = top-left, matching screen space.
        float[] verts = {
            -1f, -1f,  0f, 1f,   // bottom-left  screen, UV bottom-left  of MCEF
             1f, -1f,  1f, 1f,   // bottom-right screen, UV bottom-right of MCEF
            -1f,  1f,  0f, 0f,   // top-left     screen, UV top-left     of MCEF
             1f,  1f,  1f, 0f,   // top-right    screen, UV top-right    of MCEF
        };

        quadVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
        quadVbo = org.lwjgl.opengl.GL15.glGenBuffers();

        org.lwjgl.opengl.GL30.glBindVertexArray(quadVao);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVbo);

        org.lwjgl.BufferUtils.createFloatBuffer(verts.length).put(verts).flip();
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(verts.length);
        buf.put(verts).flip();
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buf, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);

        // attrib 0: vec2 position
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        // attrib 1: vec2 uv
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        // Minimal blit shader — no matrix needed since we're already in NDC
        String vert = "#version 150 core\n"
            + "in vec2 pos;\n"
            + "in vec2 uv;\n"
            + "out vec2 fUv;\n"
            + "void main(){gl_Position=vec4(pos,0,1);fUv=uv;}\n";
        String frag = "#version 150 core\n"
            + "uniform sampler2D tex;\n"
            + "in vec2 fUv;\n"
            + "out vec4 colour;\n"
            + "void main(){colour=texture(tex,fUv);}\n";

        int vs = org.lwjgl.opengl.GL20.glCreateShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER);
        org.lwjgl.opengl.GL20.glShaderSource(vs, vert);
        org.lwjgl.opengl.GL20.glCompileShader(vs);

        int fs = org.lwjgl.opengl.GL20.glCreateShader(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER);
        org.lwjgl.opengl.GL20.glShaderSource(fs, frag);
        org.lwjgl.opengl.GL20.glCompileShader(fs);

        blitProgram = org.lwjgl.opengl.GL20.glCreateProgram();
        org.lwjgl.opengl.GL20.glAttachShader(blitProgram, vs);
        org.lwjgl.opengl.GL20.glAttachShader(blitProgram, fs);
        org.lwjgl.opengl.GL20.glBindAttribLocation(blitProgram, 0, "pos");
        org.lwjgl.opengl.GL20.glBindAttribLocation(blitProgram, 1, "uv");
        org.lwjgl.opengl.GL20.glLinkProgram(blitProgram);
        org.lwjgl.opengl.GL20.glDeleteShader(vs);
        org.lwjgl.opengl.GL20.glDeleteShader(fs);

        // Bind sampler to texture unit 0
        org.lwjgl.opengl.GL20.glUseProgram(blitProgram);
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(blitProgram, "tex"), 0);
        org.lwjgl.opengl.GL20.glUseProgram(0);

        System.out.println("[ArgonBrowser] Quad VAO/VBO + blit shader initialized.");
    }

    /**
     * Creates the MCEF browser instance pointing at the interop server URL.
     */
    private void initBrowser() {
        System.out.println("[ArgonBrowser] initBrowser() called");
        try {
            // Force-initialize MCEF internal state via reflection to bypass mod loader failures
            if (MCEF.INSTANCE.getResourceManager() == null) {
                System.out.println("[ArgonBrowser] MCEF internal state is incomplete. Force-initializing...");
                try {
                    // 1. Settings
                    java.lang.reflect.Field sField = MCEF.class.getDeclaredField("settings");
                    sField.setAccessible(true);
                    if (sField.get(MCEF.INSTANCE) == null) {
                        net.ccbluex.liquidbounce.mcef.MCEFSettings settings = new net.ccbluex.liquidbounce.mcef.MCEFSettings();
                        settings.appendCefSwitches("--no-proxy-server", "--disable-web-security");
                        sField.set(MCEF.INSTANCE, settings);
                    }

                    // 2. ResourceManager (Manually via constructor)
                    java.lang.reflect.Field rmField = MCEF.class.getDeclaredField("resourceManager");
                    rmField.setAccessible(true);
                    if (rmField.get(MCEF.INSTANCE) == null) {
                        Class<?> dmClass = net.ccbluex.liquidbounce.mcef.MCEFDownloadManager.class;
                        java.lang.reflect.Constructor<?> dmCtor = dmClass.getDeclaredConstructor(String[].class, String.class, net.ccbluex.liquidbounce.mcef.MCEFPlatform.class, java.io.File.class);
                        dmCtor.setAccessible(true);
                        
                        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                        java.io.File mcefDir = new java.io.File(mc.runDirectory, "LiquidBounce/mcef");
                        mcefDir.mkdirs();
                        
                        Object rm = dmCtor.newInstance(
                            new String[]{"https://cloud.liquidbounce.net/mcef"},
                            "master",
                            net.ccbluex.liquidbounce.mcef.MCEFPlatform.getPlatform(),
                            mcefDir
                        );
                        
                        // Check if download is required
                        java.lang.reflect.Method reqMethod = dmClass.getDeclaredMethod("requiresDownload");
                        reqMethod.setAccessible(true);
                        if ((boolean) reqMethod.invoke(rm)) {
                            System.out.println("[ArgonBrowser] CEF binaries missing. Downloading... (This may take a minute)");
                            java.lang.reflect.Method dlMethod = dmClass.getDeclaredMethod("downloadJcef");
                            dlMethod.setAccessible(true);
                            dlMethod.invoke(rm);
                        }
                        
                        rmField.set(MCEF.INSTANCE, rm);
                    }
                } catch (Exception e) {
                    System.err.println("[ArgonBrowser] Reflection force-init failed!");
                    e.printStackTrace();
                }
            }

            if (!MCEF.INSTANCE.isInitialized()) {
                System.out.println("[ArgonBrowser] Invoking MCEF.INSTANCE.initialize()...");
                MCEF.INSTANCE.initialize();
            }

            if (!MCEF.INSTANCE.isInitialized()) {
                System.err.println("[ArgonBrowser] MCEF failed to initialize! Binaries missing in 'LiquidBounce/mcef'?");
                return;
            }

            String targetUrl = this.url; // Use real URL

            if (browser != null) {
                if (!browser.getURL().equals(targetUrl)) {
                    browser.loadURL(targetUrl);
                }
                browser.resize(winWidth, winHeight);
                System.out.println("[ArgonBrowser] Reusing existing browser. URL: " + targetUrl);
                return;
            }

            if (!handlersAdded) {
                MCEF.INSTANCE.getClient().getHandle().addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
                    @Override
                    public void onAfterCreated(org.cef.browser.CefBrowser cefBrowser) {
                        browserReady = true;
                        cefBrowser.loadURL(targetUrl);
                        System.out.println("[ArgonBrowser] onAfterCreated fired! Loaded URL: " + targetUrl);
                    }
                });
                handlersAdded = true;
            }

            browser = MCEF.INSTANCE.createBrowser(targetUrl, true, winWidth, winHeight, new MCEFBrowserSettings(60, false));
            if (browser != null) {
                browser.resize(winWidth, winHeight);
                System.out.println("[ArgonBrowser] Browser created! URL: " + targetUrl);
            }
        } catch (Exception e) {
            System.err.println("[ArgonBrowser] Failed to create browser: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private long lastPrintTime = 0;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. Always draw background blur
        dev.lvstrng.argon.utils.BlurProcessor.renderBlur(15.0f);
        
        if (browser == null) {
            // 2. Handle missing browser (Initialization/Download phase)
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            if (MCEFInitializer.downloading) {
                // Dark grey background, glowing white bar
                int barWidth = 200;
                int barHeight = 8;
                int x = centerX - (barWidth / 2);
                int y = centerY - (barHeight / 2);

                context.fill(x - 2, y - 2, x + barWidth + 2, y + barHeight + 2, 0xCC222222);
                context.fill(x, y, x + barWidth, y + barHeight, 0xFF111111);

                int fillWidth = (int) (barWidth * MCEFInitializer.progress);
                context.fill(x, y, x + fillWidth, y + barHeight, 0xFFFFFFFF);
                
                // Glow effect
                context.fill(x, y - 1, x + fillWidth, y, 0xAAFFFFFF);
                context.fill(x, y + barHeight, x + fillWidth, y + barHeight + 1, 0xAAFFFFFF);

                String status = String.format("%s [%d%%]", MCEFInitializer.status, (int)(MCEFInitializer.progress * 100));
                context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, y - 15, 0xFFFFFFFF);
            } else {
                context.drawCenteredTextWithShadow(this.textRenderer, "MCEF not loaded", centerX, centerY, 0xFFFF0000);
            }
        } else {
            // 3. Pump the CEF message loop so Chromium can handle network I/O,
            //    JS execution, and paint callbacks.
            try {
                if (browserReady && MCEF.INSTANCE.getApp() != null && MCEF.INSTANCE.getApp().getHandle() != null) {
                    MCEF.INSTANCE.getApp().getHandle().N_DoMessageLoopWork();
                }
            } catch (Exception e) {
                System.err.println("[ArgonBrowser] doMessageLoopWork failed!");
                e.printStackTrace();
            }

            // 4. Render the browser texture using native Minecraft drawing
            try {
                long now = System.currentTimeMillis();
                if (now - lastPrintTime > 1000) {
                    lastPrintTime = now;
                    String curUrl = browser.getURL();
                    System.out.println("[ArgonBrowser] URL: " + curUrl + ", TextureReady: " + browser.getRenderer().isTextureReady());
                    if (curUrl == null || curUrl.isEmpty() || curUrl.equals("about:blank")) {
                        browser.loadURL(url); // Force load
                        System.out.println("[ArgonBrowser] Force loading URL...");
                    }
                }

                if (browser.getRenderer().isTextureReady()) {
                    net.minecraft.util.Identifier texId = browser.getRenderer().getIdentifier();
                    if (texId != null) {
                        if (!textureFound) {
                            System.out.println("[ArgonBrowser] First texture Identifier received: " + texId.toString());
                            textureFound = true;
                        }
                        
                        // Native GL blit using the Core-Profile quad
                        int glId = 0;
                        net.minecraft.client.texture.AbstractTexture tex = net.minecraft.client.MinecraftClient.getInstance().getTextureManager().getTexture(texId);
                        if (tex != null) {
                            try {
                                Class<?> clazz = tex.getClass();
                                while (clazz != Object.class) {
                                    for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                                        if (f.getType() == int.class && (f.getName().equalsIgnoreCase("id") || f.getName().equalsIgnoreCase("glId"))) {
                                            f.setAccessible(true);
                                            glId = f.getInt(tex);
                                            break;
                                        }
                                    }
                                    if (glId != 0) break;
                                    clazz = clazz.getSuperclass();
                                }
                            } catch (Exception ex) {}
                        }
                        
                        if (glId != 0 && blitProgram != -1 && quadVao != -1) {
                            // Save state
                            int prevProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
                            int prevVao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                            boolean depthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                            boolean blendEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
                            
                            // Setup state
                            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                            if (!blendEnabled) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
                            
                            // Draw
                            org.lwjgl.opengl.GL20.glUseProgram(blitProgram);
                            org.lwjgl.opengl.GL30.glBindVertexArray(quadVao);
                            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
                            org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP, 0, 4);
                            
                            // Restore state
                            org.lwjgl.opengl.GL30.glBindVertexArray(prevVao);
                            org.lwjgl.opengl.GL20.glUseProgram(prevProgram);
                            if (depthTest) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                            if (!blendEnabled) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
                        }
                    }
                }
            } catch (Exception e) {
                if (!textureFound) {
                    System.err.println("[ArgonBrowser] Exception during render frame: " + e.getMessage());
                    e.printStackTrace();
                    textureFound = true; // only print once to avoid log spam
                }
            }
        }
        
        super.render(context, mouseX, mouseY, delta);
    }


    // ─── Input forwarding ───────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        forwardMousePress(click.x(), click.y(), click.button(), true);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        forwardMousePress(click.x(), click.y(), click.button(), false);
        return true;
    }

    // Note: mouseClicked/mouseReleased keep standard (double,double,int) signatures in 1.21.11

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // mouseMoved returns void in Minecraft 1.21.x
        if (browser != null) {
            browser.sendMouseMove((int) mouseX, (int) mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (browser != null) {
            browser.sendMouseWheel((int) mouseX, (int) mouseY, verticalAmount);
        }
        return true;
    }

    private void forwardMousePress(double x, double y, int button, boolean pressed) {
        if (browser == null) return;
        if (pressed) {
            browser.sendMousePress((int) x, (int) y, button);
        } else {
            browser.sendMouseRelease((int) x, (int) y, button);
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        int scanCode = keyInput.scancode();
        int modifiers = keyInput.modifiers();

        if (browser != null) {
            browser.sendKeyPress(keyCode, (long) scanCode, modifiers);
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharInput charInput) {
        if (browser != null) {
            browser.sendKeyTyped(charInput.asString().charAt(0), 0);
        }
        return true;
    }

    @Override
    public void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        // Clean up GL resources
        if (quadVao != -1) {
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(quadVao);
            quadVao = -1;
        }
        if (quadVbo != -1) {
            org.lwjgl.opengl.GL15.glDeleteBuffers(quadVbo);
            quadVbo = -1;
        }
        if (blitProgram != -1) {
            org.lwjgl.opengl.GL20.glDeleteProgram(blitProgram);
            blitProgram = -1;
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
