package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Util {
    private Util() {}

    /** Effects treated as "debuffs" by Poison's Virulence ability. */
    public static final Set<PotionEffectType> HARMFUL = Set.of(
            PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA,
            PotionEffectType.BLINDNESS, PotionEffectType.HUNGER, PotionEffectType.WEAKNESS,
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.LEVITATION,
            PotionEffectType.DARKNESS, PotionEffectType.UNLUCK);

    /** Normal (ability) effect: no particles, icon shown. */
    public static void effect(LivingEntity e, PotionEffectType type, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(type, ticks, amp, false, false, true));
    }

    /** Passive effect: marked "ambient" so the plugin can tell it apart from ability effects. */
    public static void passiveEffect(LivingEntity e, PotionEffectType type, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(type, ticks, amp, true, false, true));
    }

    public static void sound(Location l, String key, float vol, float pitch) {
        if (l.getWorld() == null || key == null) return;
        l.getWorld().playSound(l, key, SoundCategory.PLAYERS, vol, pitch);
    }

    public static Component text(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** Horizontal unit vector of v (or a zero vector if v points straight up/down). */
    public static Vector flat(Vector v) {
        Vector f = v.clone().setY(0);
        if (f.lengthSquared() < 1.0E-4) return new Vector(0, 0, 0);
        return f.normalize();
    }

    public static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    public static String healthLine(LivingEntity e) {
        return String.format("%s  ❤ %.1f / %.1f", e.getName(), e.getHealth(), maxHealth(e));
    }

    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + w.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    public static String fmtSeconds(int s) {
        if (s < 60) return s + "s";
        int m = s / 60, r = s % 60;
        return r == 0 ? m + "m" : m + "m " + r + "s";
    }
}
