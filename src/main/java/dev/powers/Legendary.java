package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** The legendary items. Each real item carries a tag so the plugin can recognise it. */
public enum Legendary {
    COOLDOWN_REDUCER("Cooldown Reducer", Material.CLOCK, NamedTextColor.AQUA, false,
            new String[]{"Reduces ability cooldown by 30%"},
            null, null),
    POWER_AMPLIFIER("Power Amplifier", Material.AMETHYST_SHARD, NamedTextColor.LIGHT_PURPLE, false,
            new String[]{"Makes power passives better and normal abilities slightly better"},
            null, null),
    SHIELD("Shield", Material.SHIELD, NamedTextColor.GOLD, true,
            new String[]{"Never can be disabled", "Debuffs and explosions don't affect you"},
            null, null),
    SPEAR("Spear", spearMaterial(), NamedTextColor.RED, true,
            new String[]{"Has Lunge I", "Gives Speed II"},
            new Ability("Mini Dash", "Dash a few blocks forward.", 30, "entity.breeze.jump"),
            new Ability("Speed Burst", "Gain Speed V.", 90, "entity.firework_rocket.launch")),
    MACE("Mace", Material.MACE, NamedTextColor.DARK_GRAY, true,
            new String[]{},
            new Ability("Chain Grapple", "Throw a chain up to 20 blocks. Hit a block and it pulls you there; hit a player and it pulls them to you.", 20, "entity.fishing_bobber.retrieve"),
            new Ability("Chain Toss", "Throw a chain up to 10 blocks (even into the air) and launch yourself to where it lands.", 60, "item.trident.return")),
    END_SWORD("End Sword", Material.NETHERITE_SWORD, NamedTextColor.DARK_PURPLE, true,
            new String[]{"Gets Strength II"},
            new Ability("Void Step", "Teleport up to 15 blocks in the direction you are looking.", 60, "entity.enderman.teleport"),
            new Ability("Dragon Fire", "Shoot a dragon fireball that leaves end mist, slowly dealing damage to anyone in it.", 90, "entity.ender_dragon.shoot")),
    NETHER_AXE("Nether Battle Axe", Material.NETHERITE_AXE, NamedTextColor.DARK_RED, true,
            new String[]{"Has Fire Aspect built in", "Permanent Fire Resistance"},
            new Ability("Blaze Volley", "Shoot 3 blaze fireballs that damage the enemy and set them on fire.", 90, "entity.blaze.shoot"),
            new Ability("Inferno Rage", "Deal increased melee damage while on fire.", 120, "item.firecharge.use")),
    DOUBLE_POWER("Double Power", Material.NETHER_STAR, NamedTextColor.YELLOW, false,
            new String[]{"You can hold 2 separate powers at the same time"},
            null, null),
    TRACKER("Tracker", Material.COMPASS, NamedTextColor.GREEN, true,
            new String[]{"Able to track where people are"},
            null, null),
    NETHERITE_HELMET("Netherite Helmet", Material.NETHERITE_HELMET, NamedTextColor.DARK_AQUA, false,
            new String[]{"Permanent invisibility when worn", "Can see all nearby players' health when worn",
                    "Nearby players glow, but only for you, when worn"},
            null, null),
    NETHERITE_CHESTPLATE("Netherite Chestplate", Material.NETHERITE_CHESTPLATE, NamedTextColor.DARK_GREEN, false,
            new String[]{"Permanent 5 extra hearts and Hero of the Village III when worn",
                    "All food gives the hunger and saturation of a golden carrot"},
            null, null),
    NETHERITE_LEGGINGS("Netherite Leggings", Material.NETHERITE_LEGGINGS, NamedTextColor.BLUE, false,
            new String[]{"Permanent Regeneration II when worn", "Speed III while crouching"},
            null, null),
    NETHERITE_BOOTS("Netherite Boots", Material.NETHERITE_BOOTS, NamedTextColor.GOLD, false,
            new String[]{"Permanent Dolphin's Grace when worn", "Able to walk on water, lava and powder snow"},
            null, null);

    private static NamespacedKey KEY;

    private final String displayName;
    private final Material material;
    private final NamedTextColor color;
    private final boolean held;
    private final String[] lines;
    private final Ability a1;
    private final Ability a2;

    Legendary(String displayName, Material material, NamedTextColor color, boolean held,
              String[] lines, Ability a1, Ability a2) {
        this.displayName = displayName;
        this.material = material;
        this.color = color;
        this.held = held;
        this.lines = lines;
        this.a1 = a1;
        this.a2 = a2;
    }

    /** Spears are new: look the material up by name so an old/odd server can't break the plugin. */
    private static Material spearMaterial() {
        Material m = Material.matchMaterial("NETHERITE_SPEAR");
        return m != null ? m : Material.TRIDENT;
    }

    public static void init(PowersPlugin plugin) {
        KEY = new NamespacedKey(plugin, "legendary");
    }

    public String displayName() { return displayName; }
    public Material material() { return material; }
    public NamedTextColor color() { return color; }
    public boolean isHeld() { return held; }
    public String[] lines() { return lines; }
    public Ability ability(int n) { return n == 1 ? a1 : (n == 2 ? a2 : null); }

    /** The real item a player receives. */
    public ItemStack createItem() {
        ItemStack it = new ItemStack(material);
        ItemMeta m = it.getItemMeta();
        m.displayName(Util.text("★ " + displayName, color).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Util.text("Legendary", NamedTextColor.GOLD));
        boolean hasAbilities = a1 != null || a2 != null;
        for (String s : lines) lore.add(Util.text((hasAbilities ? "Passive: " : "") + s, NamedTextColor.GRAY));
        if (a1 != null) lore.add(Util.text("Ability 1 (F): ", NamedTextColor.YELLOW).append(Util.text(a1.name() + " - " + a1.desc(), NamedTextColor.GRAY)));
        if (a2 != null) lore.add(Util.text("Ability 2 (Shift + F): ", NamedTextColor.YELLOW).append(Util.text(a2.name() + " - " + a2.desc(), NamedTextColor.GRAY)));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        m.setUnbreakable(true);
        switch (this) {
            case END_SWORD -> { ench(m, "sharpness", 5); ench(m, "looting", 3); ench(m, "sweeping_edge", 3); }
            case NETHER_AXE -> { ench(m, "sharpness", 5); ench(m, "efficiency", 5); ench(m, "fire_aspect", 1); }
            case SPEAR -> { ench(m, "sharpness", 5); ench(m, "looting", 3); ench(m, "lunge", 1); ench(m, "knockback", 2); }
            case MACE -> { ench(m, "wind_burst", 1); ench(m, "density", 2); }
            case TRACKER -> ench(m, "vanishing_curse", 1);
            case NETHERITE_HELMET -> { ench(m, "protection", 5); ench(m, "aqua_affinity", 1); ench(m, "respiration", 3); }
            case NETHERITE_CHESTPLATE -> ench(m, "protection", 5);
            case NETHERITE_LEGGINGS -> { ench(m, "protection", 5); ench(m, "swift_sneak", 3); }
            case NETHERITE_BOOTS -> { ench(m, "protection", 5); ench(m, "depth_strider", 3); ench(m, "feather_falling", 4); }
            default -> { }
        }
        if (KEY != null) m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, name());
        it.setItemMeta(m);
        return it;
    }

    private static void ench(ItemMeta m, String key, int level) {
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (e != null) m.addEnchant(e, level, true);
    }

    public static Legendary of(ItemStack it) {
        if (it == null || KEY == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Legendary fromName(String s) {
        String n = s.toLowerCase().replace("_", "").replace(" ", "");
        for (Legendary l : values()) {
            if (l.name().toLowerCase().replace("_", "").equals(n)
                    || l.displayName.toLowerCase().replace(" ", "").equals(n)) return l;
        }
        return null;
    }
}
