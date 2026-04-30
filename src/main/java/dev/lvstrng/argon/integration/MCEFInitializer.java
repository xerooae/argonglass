package dev.lvstrng.argon.integration;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.MCEFDownloadManager;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.ccbluex.liquidbounce.mcef.MCEFSettings;
import net.ccbluex.liquidbounce.mcef.listeners.MCEFProgressListener;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Handles background initialization and binary downloading for MCEF.
 */
public class MCEFInitializer {
    public static float progress = 0;
    public static boolean downloading = false;
    public static String status = "Initializing...";

    public static void initialize() {
        new Thread(() -> {
            try {
                System.out.println("[ArgonBrowser] Starting background MCEF binary check...");
                File nativeDir = findNativeDir();

                if (nativeDir != null) {
                    setupPaths(nativeDir);
                } else {
                    System.out.println("[ArgonBrowser] Native binaries (libcef.dll) not found in run directory!");
                }

                // Ensure ResourceManager is ready for download check
                setupResourceManager(nativeDir);

            } catch (Exception e) {
                System.err.println("[ArgonBrowser] Background initialization failed!");
                e.printStackTrace();
            }
        }, "MCEF-Initializer").start();
    }

    private static File findNativeDir() {
        File runDir = MinecraftClient.getInstance().runDirectory;
        File[] matchingDirs = runDir.listFiles((dir, name) -> name.length() == 40);
        if (matchingDirs != null) {
            for (File hashDir : matchingDirs) {
                File win64 = new File(hashDir, "windows_amd64");
                if (win64.exists() && new File(win64, "libcef.dll").exists()) {
                    return win64;
                }
            }
        }
        return null;
    }

    private static void setupPaths(File nativeDir) {
        String path = nativeDir.getAbsolutePath();
        System.out.println("[ArgonBrowser] Found native binaries at: " + path);
        System.setProperty("jcef.library.path", path);
        String currentPath = System.getProperty("java.library.path");
        if (!currentPath.contains(path)) {
            System.setProperty("java.library.path", path + File.pathSeparator + currentPath);
        }
    }

    private static void setupResourceManager(File nativeDir) throws Exception {
        if (MCEF.INSTANCE.getResourceManager() == null) {
            Field sField = MCEF.class.getDeclaredField("settings");
            sField.setAccessible(true);
            MCEFSettings settings = new MCEFSettings();
            
            settings.appendCefSwitches("--no-proxy-server", "--disable-web-security", "--no-sandbox",
                    "--disable-gpu", "--disable-gpu-compositing", "--enable-begin-frame-scheduling",
                    "--disable-gpu-shader-disk-cache", "--disable-gpu-sandbox", "--disable-site-isolation-trials");

            if (nativeDir != null) {
                File helper = new File(nativeDir, "jcef_helper.exe");
                if (helper.exists()) {
                    settings.appendCefSwitches("--browser-subprocess-path=" + helper.getAbsolutePath());
                }
            }

            Field rmField = MCEF.class.getDeclaredField("resourceManager");
            rmField.setAccessible(true);
            Object rm = MCEF.INSTANCE.newResourceManager();
            
            Method reqMethod = rm.getClass().getDeclaredMethod("requiresDownload");
            reqMethod.setAccessible(true);
            if ((boolean) reqMethod.invoke(rm)) {
                handleDownload(rm);
            }

            rmField.set(MCEF.INSTANCE, rm);
        }
    }

    private static void handleDownload(Object rm) throws Exception {
        downloading = true;
        status = "Downloading Chromium Engine...";
        Method regMethod = rm.getClass().getDeclaredMethod("registerProgressListener", MCEFProgressListener.class);
        regMethod.setAccessible(true);
        regMethod.invoke(rm, new MCEFProgressListener() {
            @Override public void onProgressUpdate(String s, float p) { progress = p / 100f; status = s; }
            @Override public void onComplete() { progress = 1f; downloading = false; }
            @Override public void onFileStart(String s) { status = "Downloading: " + s; }
            @Override public void onFileProgress(String s, long l, long l1, boolean b) {}
            @Override public void onFileEnd(String s) {}
        });

        Method dlMethod = rm.getClass().getDeclaredMethod("downloadJcef");
        dlMethod.setAccessible(true);
        dlMethod.invoke(rm);
        downloading = false;
    }

    /**
     * MUST be called on the main/render thread to ensure CEF initialized correctly.
     */
    public static void ensureInitialized() {
        if (!MCEF.INSTANCE.isInitialized() && !downloading) {
            System.out.println("[ArgonBrowser] Invoking MCEF.INSTANCE.initialize() on thread: " + Thread.currentThread().getName());
            MCEF.INSTANCE.initialize();
        }
    }
}
