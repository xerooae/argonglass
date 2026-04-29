package dev.lvstrng.argon;

import dev.lvstrng.argon.event.EventManager;
import dev.lvstrng.argon.gui.ClickGui;
import dev.lvstrng.argon.integration.ArgonBrowserScreen;
import dev.lvstrng.argon.integration.ArgonInteropServer;
import dev.lvstrng.argon.managers.FriendManager;
import dev.lvstrng.argon.module.ModuleManager;
import dev.lvstrng.argon.managers.ProfileManager;
import dev.lvstrng.argon.utils.rotation.RotatorManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.io.File;
import java.io.IOException;
import java.net.*;

@SuppressWarnings("all")
public final class Argon {
	public RotatorManager rotatorManager;
	public ProfileManager profileManager;
	public ModuleManager moduleManager;
	public EventManager eventManager;
	public FriendManager friendManager;
	public static MinecraftClient mc;
	public String version = " b1.3";
	public static boolean BETA;
	public static Argon INSTANCE;
	public boolean guiInitialized;
	// Legacy native GUI — kept for fallback
	public ClickGui clickGui;
	// LiquidBounce-style browser GUI
	public ArgonInteropServer interopServer;
	public boolean useBrowserGui = true; // set false to revert to native GUI
	public Screen previousScreen = null;
	public long lastModified;
	public File argonJar;

	public Argon() throws InterruptedException, IOException {
		INSTANCE = this;
		this.eventManager = new EventManager();
		this.moduleManager = new ModuleManager();
		this.clickGui = new ClickGui();
		this.rotatorManager = new RotatorManager();
		this.profileManager = new ProfileManager();
		this.friendManager = new FriendManager();

		this.getProfileManager().loadProfile();
		this.setLastModified();

		this.guiInitialized = false;
		mc = MinecraftClient.getInstance();

		// Phase 2: Start the interop HTTP server for LiquidBounce Svelte GUI
		try {
			this.interopServer = ArgonInteropServer.getInstance();
			this.interopServer.start();
			System.out.println("[Argon] Interop server running at " + interopServer.getUrl());
		} catch (Exception e) {
			System.err.println("[Argon] Failed to start interop server, falling back to native GUI: " + e.getMessage());
			this.useBrowserGui = false;
		}

		// Phase 3: Start MCEF background initialization and binary download
		dev.lvstrng.argon.integration.MCEFInitializer.initialize();
	}

	public ProfileManager getProfileManager() {
		return profileManager;
	}

	public ModuleManager getModuleManager() {
		return moduleManager;
	}

	public FriendManager getFriendManager() {
		return friendManager;
	}

	public EventManager getEventManager() {
		return eventManager;
	}

	public ClickGui getClickGui() {
		return clickGui;
	}

	/**
	 * Phase 3: Opens the LiquidBounce Svelte GUI inside an MCEF browser window.
	 * Falls back to the native ClickGui if MCEF/interop server failed to start.
	 */
	public void openGui() {
		if (useBrowserGui && interopServer != null) {
			String guiUrl = interopServer.getUrl() + "/resource/liquidbounce/index.html";
			mc.execute(() -> mc.setScreen(new ArgonBrowserScreen(guiUrl)));
		} else {
			mc.execute(() -> mc.setScreen(clickGui));
		}
	}

	public void resetModifiedDate() {
		this.argonJar.setLastModified(lastModified);
	}

	public String getVersion() {
		return version;
	}

	public void setLastModified() {
		try {
			this.argonJar = new File(Argon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			// Comment out when debugging
			this.lastModified = argonJar.lastModified();
		} catch (URISyntaxException ignored) {}
	}
}