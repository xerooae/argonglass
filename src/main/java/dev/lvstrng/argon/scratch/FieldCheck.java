package dev.lvstrng.argon.scratch;
import net.ccbluex.liquidbounce.mcef.MCEFSettings;
import java.lang.reflect.Field;

public class FieldCheck {
    public static void main(String[] args) {
        System.out.println("MCEFSettings Fields:");
        for (Field f : MCEFSettings.class.getDeclaredFields()) {
            System.out.println(" - " + f.getName() + " (" + f.getType().getName() + ")");
        }
    }
}
