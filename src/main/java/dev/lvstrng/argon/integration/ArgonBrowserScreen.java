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

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.module.modules.client.ClickGUI;
import org.lwjgl.opengl.GL11;

/**
 * ArgonBrowserScreen — Phase 3 of the LiquidBounce GUI port.
 *
 * Replaces Argon's native Java ClickGui with an MCEF Chromium browser window
 * pointed at the LiquidBounce Svelte app hosted by ArgonInteropServer.
 *
 * MCEF API (net.ccbluex:mcef) mirrors JCEF. The key classes are:
 * - MCEFBrowser : the embedded Chromium browser instance
 * - MCEFClient : browser event callbacks
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
        MCEFInitializer.ensureInitialized();
        this.winWidth = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
        this.winHeight = MinecraftClient.getInstance().getWindow().getFramebufferHeight();
        double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
        System.out.println("[ArgonBrowser] Init: FB=" + winWidth + "x" + winHeight + " Scale=" + scale + " Win=" + MinecraftClient.getInstance().getWindow().getWidth() + "x" + MinecraftClient.getInstance().getWindow().getHeight());
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
        // Two triangles covering the full screen
        float[] verts = {
                -1f,  1f, 0f, 0f, // TL
                -1f, -1f, 0f, 1f, // BL
                 1f,  1f, 1f, 0f, // TR

                -1f, -1f, 0f, 1f, // BL
                 1f, -1f, 1f, 1f, // BR
                 1f,  1f, 1f, 0f  // TR
        };

        quadVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
        quadVbo = org.lwjgl.opengl.GL15.glGenBuffers();

        org.lwjgl.opengl.GL30.glBindVertexArray(quadVao);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVbo);

        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(verts.length);
        buf.put(verts).flip();
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buf,
                org.lwjgl.opengl.GL15.GL_STATIC_DRAW);

        // attrib 0: vec2 position
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, org.lwjgl.opengl.GL11.GL_FLOAT, false, 16, 0);
        // attrib 1: vec2 uv
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, org.lwjgl.opengl.GL11.GL_FLOAT, false, 16, 8);

        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        // Minimal blit shader — no matrix needed since we're already in NDC
        String vert = "#version 150 core\n"
                + "in vec2 pos;\n"
                + "in vec2 uv;\n"
                + "out vec2 fUv;\n"
                + "void main(){gl_Position=vec4(pos,0,1);fUv=uv;}\n";
        String fragSource = "#version 150 core\n" +
                "uniform sampler2D tex;\n" +
                "uniform int useTex;\n" +
                "in vec2 fUv;\n" +
                "out vec4 colour;\n" +
                "void main() {\n" +
                "    if (useTex == 1) {\n" +
                "        vec4 col = texture(tex, fUv);\n" +
                "        // Chromium OSR on Windows is usually BGRA. Swap R and B.\n" +
                "        // Diagnostic: mix with bright magenta to see transparent areas\n" +
                "        vec3 bg = vec3(1.0, 0.0, 1.0);\n" +
                "        vec3 fg = vec3(col.b, col.g, col.r);\n" +
                "        colour = vec4(mix(bg, fg, col.a), 1.0);\n" + 
                "    } else {\n" +
                "        colour = vec4(1.0, 0.0, 0.0, 0.5);\n" + // Red tint for loading
                "    }\n" +
                "}\n";

        int vs = org.lwjgl.opengl.GL20.glCreateShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER);
        org.lwjgl.opengl.GL20.glShaderSource(vs, vert);
        org.lwjgl.opengl.GL20.glCompileShader(vs);
        if (org.lwjgl.opengl.GL20.glGetShaderi(vs, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS) == org.lwjgl.opengl.GL11.GL_FALSE) {
            System.err.println("[ArgonBrowser] Vertex Shader Error: " + org.lwjgl.opengl.GL20.glGetShaderInfoLog(vs));
        }

        int fs = org.lwjgl.opengl.GL20.glCreateShader(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER);
        org.lwjgl.opengl.GL20.glShaderSource(fs, fragSource);
        org.lwjgl.opengl.GL20.glCompileShader(fs);
        if (org.lwjgl.opengl.GL20.glGetShaderi(fs, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS) == org.lwjgl.opengl.GL11.GL_FALSE) {
            System.err.println("[ArgonBrowser] Fragment Shader Error: " + org.lwjgl.opengl.GL20.glGetShaderInfoLog(fs));
        }

        blitProgram = org.lwjgl.opengl.GL20.glCreateProgram();
        org.lwjgl.opengl.GL20.glAttachShader(blitProgram, vs);
        org.lwjgl.opengl.GL20.glAttachShader(blitProgram, fs);
        org.lwjgl.opengl.GL20.glBindAttribLocation(blitProgram, 0, "pos");
        org.lwjgl.opengl.GL20.glBindAttribLocation(blitProgram, 1, "uv");
        org.lwjgl.opengl.GL20.glLinkProgram(blitProgram);
        if (org.lwjgl.opengl.GL20.glGetProgrami(blitProgram, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.err.println(
                    "[ArgonBrowser] Program Link Error: " + org.lwjgl.opengl.GL20.glGetProgramInfoLog(blitProgram));
        }
        org.lwjgl.opengl.GL20.glDeleteShader(vs);
        org.lwjgl.opengl.GL20.glDeleteShader(fs);

        // Bind sampler to texture unit 0
        org.lwjgl.opengl.GL20.glUseProgram(blitProgram);
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(blitProgram, "tex"), 0);
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(blitProgram, "useTex"), 1);
        org.lwjgl.opengl.GL20.glUseProgram(0);

        System.out.println("[ArgonBrowser] Quad VAO/VBO + blit shader initialized.");
    }

    /**
     * Creates the MCEF browser instance pointing at the interop server URL.
     */
    private void initBrowser() {
        System.out.println("[ArgonBrowser] initBrowser() called. MCEF instance: " + MCEF.INSTANCE);
        try {
            // 1. Ensure MCEF is initialized
            if (MCEF.INSTANCE == null || !MCEF.INSTANCE.isInitialized()) {
                System.out.println("[ArgonBrowser] MCEF not initialized yet, skipping browser creation.");
                return;
            }

            String targetUrl = ArgonInteropServer.getInstance().getUrl() + "/resource/liquidbounce/index.html#/clickgui";

            if (browser == null) {
                System.out.println("[ArgonBrowser] Creating new browser instance for: " + targetUrl);
                if (!handlersAdded) {
                    System.out.println("[ArgonBrowser] Registering LifeSpanHandler...");
                    MCEF.INSTANCE.getClient().getHandle()
                            .addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
                                @Override
                                public void onAfterCreated(org.cef.browser.CefBrowser browser) {
                                    System.out.println("[ArgonBrowser] onAfterCreated fired for browser!");
                                    browserReady = true;
                                }

                                @Override
                                public void onBeforeClose(org.cef.browser.CefBrowser browser) {
                                    System.out.println("[ArgonBrowser] onBeforeClose fired for browser!");
                                }
                            });
                    
                    MCEF.INSTANCE.getClient().getHandle()
                            .addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
                                @Override
                                public boolean onConsoleMessage(org.cef.browser.CefBrowser browser, 
                                        org.cef.CefSettings.LogSeverity level, String message, String source, int line) {
                                    System.out.println("[ArgonBrowser Console] [" + level + "] (" + source + ":" + line + ") " + message);
                                    return false;
                                }
                            });

                    MCEF.INSTANCE.getClient().getHandle()
                            .addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                                @Override
                                public void onLoadStart(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest.TransitionType transitionType) {
                                    if (frame.isMain()) {
                                        String js = "window.onerror = function(msg, url, line, col, error) { " +
                                                    "  console.error('Uncaught Error: ' + msg + ' at ' + url + ':' + line + ':' + col); " +
                                                    "}; " +
                                                    "window.addEventListener('unhandledrejection', function(event) { " +
                                                    "  console.error('Unhandled Promise Rejection: ' + event.reason); " +
                                                    "}); " +
                                                    "var diagDiv = document.createElement('div'); " +
                                                    "diagDiv.style.position = 'fixed'; diagDiv.style.top = '0'; diagDiv.style.left = '0'; " +
                                                    "diagDiv.style.width = '100vw'; diagDiv.style.height = '100vh'; diagDiv.style.zIndex = '999999'; " +
                                                    "document.body.appendChild(diagDiv); " +
                                                    "setInterval(function() { " +
                                                    "  diagDiv.style.backgroundColor = diagDiv.style.backgroundColor === 'red' ? 'blue' : 'red'; " +
                                                    "}, 500);";
                                        frame.executeJavaScript(js, frame.getURL(), 0);
                                    }
                                }
                            });
                    
                    handlersAdded = true;
                }

                browser = MCEF.INSTANCE.createBrowser(targetUrl, true, winWidth, winHeight,
                        new MCEFBrowserSettings(60, false));
                if (browser != null) {
                    browser.resize(winWidth, winHeight);
                    System.out.println("[ArgonBrowser] Browser created successfully. Loading URL: " + targetUrl);
                    browser.loadURL(targetUrl);
                } else {
                    System.err.println("[ArgonBrowser] MCEF.INSTANCE.createBrowser returned NULL!");
                }
            } else {
                String currentUrl = browser.getURL();
                System.out
                        .println("[ArgonBrowser] Reusing existing browser instance. Current URL: '" + currentUrl + "'");
                
                // Force a reload to clear any diagnostic red/blue state from previous runs
                browser.reload();
                
                browser.resize(winWidth, winHeight);
            }
        } catch (Exception e) {
            System.err.println("[ArgonBrowser] Failed to create browser!");
            e.printStackTrace();
        }
    }

    private long lastPrintTime = 0;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. Always pump MCEF message loop
        // This is critical for decoding network responses and uploading textures.
        try {
            org.cef.CefApp.getInstance().N_DoMessageLoopWork();
        } catch (Throwable t) {
            // Ignored if fails
        }

        // 2. Always draw background blur
        dev.lvstrng.argon.utils.BlurProcessor.renderBlur(15.0f);

        if (browser == null) {
            // If browser is null, it might be because MCEF is still initializing.
            // Try calling initBrowser again on the render thread to ensure process
            // spawning.
            if (!MCEFInitializer.downloading) {
                initBrowser();
            }

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

                String status = String.format("%s [%d%%]", MCEFInitializer.status,
                        (int) (MCEFInitializer.progress * 100));
                context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, y - 15, 0xFFFFFFFF);
            } else {
                context.drawCenteredTextWithShadow(this.textRenderer, "MCEF not loaded", centerX, centerY, 0xFFFF0000);
            }
        } else {
            // 3. Pump the CEF message loop so Chromium can handle network I/O,
            // JS execution, and paint callbacks.
            try {
                // Pump the CEF message loop.
                if (MCEF.INSTANCE.getApp() != null && MCEF.INSTANCE.getApp().getHandle() != null) {
                    MCEF.INSTANCE.getApp().getHandle().N_DoMessageLoopWork();
                }
            } catch (Exception e) {
                System.err.println("[ArgonBrowser] Message loop work failed: " + e.getMessage());
            }


            // 4. Render the browser texture using native Minecraft drawing
            try {
                long now = System.currentTimeMillis();
                boolean isReady = browser.getRenderer().isTextureReady();
                
                if (now - lastPrintTime > 2000) {
                    lastPrintTime = now;
                    String curUrl = browser.getURL();
                    System.out.println("[ArgonBrowser] Diagnostic - URL: '" + curUrl + "', TextureReady: " + isReady);
                }

                if (isReady) {
                    int glId = browser.getRenderer().getTextureId();
                    net.minecraft.util.Identifier texId = browser.getRenderer().getIdentifier();
                    if (!textureFound) {
                        System.out.println("[ArgonBrowser] Rendering first frame with Identifier: " + texId + " (GL ID: " + glId + ")");
                        textureFound = true;
                    }
                    
                    if (glId != 0) {
                        // Enable blending so the Chromium texture can overlay properly
                        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);

                        // Draw the Chromium texture
                        // We must pass this.width and this.height for both the destination size
                        // AND the texture size, because drawTexture uses these to calculate UVs.
                        // u1 = u / textureWidth = 0.0, u2 = (u + width) / textureWidth = width / width = 1.0.
                        context.drawTexture(
                            net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                            texId,
                            0, 0,
                            0.0f, 0.0f,
                            this.width, this.height,
                            this.width, this.height,
                            0xFFFFFFFF
                        );
                    } else {
                        // Texture not ready - draw red tint
                        context.fill(0, 0, this.width, this.height, 0x80FF0000);
                        context.drawCenteredTextWithShadow(this.textRenderer, "Browser Loading (Texture Not Ready)...", this.width / 2, this.height / 2, 0xFFFFFFFF);
                    }
                } else {
                    // Diagnostic: Draw a red overlay using DrawContext to prove we are rendering
                    context.fill(0, 0, this.width, this.height, 0x22FF0000);
                    context.drawCenteredTextWithShadow(this.textRenderer, "Browser Loading... (Texture Not Ready)",
                            this.width / 2, this.height / 2 + 20, 0xFFFFFFFF);

                    // Also try raw GL red quad just in case DrawContext is being cleared
                    if (blitProgram != -1 && quadVao != -1) {
                        renderQuad(0, false);
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
        System.out.println("[ArgonBrowser] mouseClicked (Click): " + click.x() + ", " + click.y() + " button=" + click.button());
        forwardMousePress(click.x(), click.y(), click.button(), true);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        System.out.println("[ArgonBrowser] mouseReleased (Click): " + click.x() + ", " + click.y() + " button=" + click.button());
        forwardMousePress(click.x(), click.y(), click.button(), false);
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (browser != null) {
            double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
            browser.sendMouseMove((int) (click.x() * scale), (int) (click.y() * scale));
        }
        return true;
    }

    // Note: mouseClicked/mouseReleased keep standard (double,double,int) signatures
    // in 1.21.11 but Argon uses (Click, boolean) and (Click)


    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) {
            double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
            browser.sendMouseMove((int) (mouseX * scale), (int) (mouseY * scale));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (browser != null) {
            double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
            // MCEF scroll delta: +/- 3 units per notch typically (or scale by 30-100 for smoother scrolling)
            browser.sendMouseWheel((int) (mouseX * scale), (int) (mouseY * scale), verticalAmount * 30.0);
        }
        return true;
    }

    private void forwardMousePress(double x, double y, int button, boolean pressed) {
        if (browser == null)
            return;
        
        double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
        int physX = (int) (x * scale);
        int physY = (int) (y * scale);
        
        // Map Minecraft buttons to CEF buttons:
        // MC: 0=Left, 1=Right, 2=Middle
        // CEF: 0=Left, 1=Middle, 2=Right
        int cefButton = button;
        if (button == 1) cefButton = 2; // Right click
        else if (button == 2) cefButton = 1; // Middle click
        
        System.out.println("[ArgonBrowser] Forwarding " + (pressed ? "PRESS" : "RELEASE") + " button=" + button + " (cef=" + cefButton + ") at " + physX + "," + physY);

        if (pressed) {
            browser.sendMousePress(physX, physY, cefButton);
            // Inject contextmenu event for Right Click to ensure Svelte reacts
            if (cefButton == 2) {
                browser.executeJavaScript(
                    "(function() {" +
                    "  var el = document.elementFromPoint(" + physX + ", " + physY + ");" +
                    "  if (el) {" +
                    "    var ev = new MouseEvent('contextmenu', { " +
                    "      bubbles: true, cancelable: true, view: window, button: 2, " +
                    "      clientX: " + physX + ", clientY: " + physY + ", screenX: " + physX + ", screenY: " + physY + " " +
                    "    });" +
                    "    el.dispatchEvent(ev);" +
                    "  }" +
                    "})();",
                    "", 0
                );
            }
        } else {
            browser.sendMouseRelease(physX, physY, cefButton);
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {

        int keyCode = keyInput.key();
        int scanCode = keyInput.scancode();
        int modifiers = keyInput.modifiers();

        System.out.println("[ArgonBrowser] Key Pressed: " + keyCode + " (Scan: " + scanCode + ", Mods: " + modifiers + ")");

        if (browser != null) {
            browser.sendKeyPress(keyCode, (long) scanCode, modifiers);
        }

        // Close on ESC
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

    private int getGlId(net.minecraft.util.Identifier texId) {
        net.minecraft.client.texture.AbstractTexture tex = net.minecraft.client.MinecraftClient.getInstance()
                .getTextureManager().getTexture(texId);
        if (tex == null)
            return 0;
            
        Class<?> clazz = tex.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field fId = clazz.getDeclaredField("glId");
                fId.setAccessible(true);
                return fId.getInt(tex);
            } catch (Exception ignored) {
                try {
                    java.lang.reflect.Field fId = clazz.getDeclaredField("id");
                    fId.setAccessible(true);
                    return fId.getInt(tex);
                } catch (Exception ignored2) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return 0;
    }

    @Override
    public void close() {
        System.out.println("[ArgonBrowser] close() called! Stack Trace:");
        // new Throwable().printStackTrace(); // Removed for brevity, already confirmed it's keyPressed
        
        // DO NOT NULLIFY STATIC BROWSER - keep it alive for next time
        if (browser != null) {
            System.out.println("[ArgonBrowser] Keeping browser instance alive for next use.");
        }
        // Ensure the module state is reset
        ClickGUI clickGuiModule = (ClickGUI) Argon.INSTANCE.getModuleManager().getModule(ClickGUI.class);
        if (clickGuiModule != null) {
            clickGuiModule.setEnabledStatus(false);
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

    private void renderQuad(int glId, boolean useTex) {
        // Save state
        int prevProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        int prevVao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
        boolean depthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean blendEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        boolean cullEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        int[] prevViewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, prevViewport);
        boolean scissorTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        // Setup state
        org.lwjgl.opengl.GL11.glViewport(0, 0,
                net.minecraft.client.MinecraftClient.getInstance().getWindow().getFramebufferWidth(),
                net.minecraft.client.MinecraftClient.getInstance().getWindow().getFramebufferHeight());
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        if (!blendEnabled)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Draw
        org.lwjgl.opengl.GL20.glUseProgram(blitProgram);
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(blitProgram, "useTex"),
                useTex ? 1 : 0);
        org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(blitProgram, "tex"), 0);
        org.lwjgl.opengl.GL30.glBindVertexArray(quadVao);

        if (useTex) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
        }

        org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, 6);

        // Restore state
        org.lwjgl.opengl.GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        org.lwjgl.opengl.GL30.glBindVertexArray(prevVao);
        org.lwjgl.opengl.GL20.glUseProgram(prevProgram);
        if (depthTest)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        if (!blendEnabled)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
        if (cullEnabled)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        if (scissorTest)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
