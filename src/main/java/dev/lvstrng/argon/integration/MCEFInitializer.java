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
                System.out.println("[ArgonBrowser] Starting background MCEF initialization...");
                
                // 1. Force-populate singleton fields via reflection
                if (MCEF.INSTANCE.getResourceManager() == null) {
                    // Settings
                    Field sField = MCEF.class.getDeclaredField("settings");
                    sField.setAccessible(true);
                    if (sField.get(MCEF.INSTANCE) == null) {
                        sField.set(MCEF.INSTANCE, new MCEFSettings());
                    }

                    // ResourceManager
                    Field rmField = MCEF.class.getDeclaredField("resourceManager");
                    rmField.setAccessible(true);
                    
                    Object rm = MCEF.INSTANCE.newResourceManager();
                    Class<?> dmClass = rm.getClass();
                    
                    // Check for downloads
                    Method reqMethod = dmClass.getDeclaredMethod("requiresDownload");
                    reqMethod.setAccessible(true);
                    if ((boolean) reqMethod.invoke(rm)) {
                        downloading = true;
                        status = "Downloading Chromium Engine...";
                        System.out.println("[ArgonBrowser] Background: CEF binaries missing. Downloading...");
                        
                        // Register listener
                        Method regMethod = dmClass.getDeclaredMethod("registerProgressListener", MCEFProgressListener.class);
                        regMethod.setAccessible(true);
                        regMethod.invoke(rm, new MCEFProgressListener() {
                            @Override public void onProgressUpdate(String s, float p) { 
                                progress = p / 100f; 
                                status = s;
                            }
                            @Override public void onComplete() { progress = 1f; downloading = false; }
                            @Override public void onFileStart(String s) { status = "Downloading: " + s; }
                            @Override public void onFileProgress(String s, long l, long l1, boolean b) {}
                            @Override public void onFileEnd(String s) {}
                        });

                        Method dlMethod = dmClass.getDeclaredMethod("downloadJcef");
                        dlMethod.setAccessible(true);
                        dlMethod.invoke(rm);
                        
                        downloading = false;
                        status = "Extraction complete!";
                        System.out.println("[ArgonBrowser] Background: Download complete!");
                    }
                    
                    rmField.set(MCEF.INSTANCE, rm);
                }

                // 2. Initialize the library
                if (!MCEF.INSTANCE.isInitialized()) {
                    System.out.println("[ArgonBrowser] Background: Invoking MCEF.INSTANCE.initialize()...");
                    MCEF.INSTANCE.initialize();
                }

                if (MCEF.INSTANCE.isInitialized()) {
                    System.out.println("[ArgonBrowser] Background: MCEF initialized successfully!");
                } else {
                    System.err.println("[ArgonBrowser] Background: MCEF failed to initialize.");
                }

            } catch (Exception e) {
                System.err.println("[ArgonBrowser] Background initialization failed!");
                e.printStackTrace();
            }
        }, "MCEF-Initializer").start();
    }
}
