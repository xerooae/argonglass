package dev.lvstrng.argon.module;

import dev.lvstrng.argon.utils.EncryptedString;

public enum Category {
	COMBAT(EncryptedString.of("Combat")),
	MOVEMENT(EncryptedString.of("Movement")),
	PLAYER(EncryptedString.of("Player")),
	RENDER(EncryptedString.of("Render")),
	WORLD(EncryptedString.of("World")),
	EXPLOIT(EncryptedString.of("Exploit")),
	MISC(EncryptedString.of("Misc")),
	CLIENT(EncryptedString.of("Client"));
	public final CharSequence name;

	Category(CharSequence name) {
		this.name = name;
	}
}
