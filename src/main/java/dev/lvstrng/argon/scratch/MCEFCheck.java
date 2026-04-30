package dev.lvstrng.argon.scratch;
import net.ccbluex.liquidbounce.mcef.MCEF;
import java.lang.reflect.Method;

public class MCEFCheck {
    public static void main(String[] args) {
        System.out.println("MCEF Methods:");
        for (Method m : MCEF.class.getDeclaredMethods()) {
            System.out.println(" - " + m.getName());
        }
    }
}
