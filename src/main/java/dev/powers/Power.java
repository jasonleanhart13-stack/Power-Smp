package dev.powers;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

public enum Power {
    SPEED("Speed", Material.SUGAR, "»", NamedTextColor.AQUA,
            new String[]{"Permanent Speed I", "No fall damage"},
            new Ability("Double Jump", "Launch yourself a few blocks up and forward.", 5, "entity.bat.takeoff"),
            new Ability("Big Jump", "Speed III for 5s and a huge launch: 50+ blocks out and 30 blocks up.", 90, "entity.firework_rocket.launch")),

    STRENGTH("Strength", Material.IRON_SWORD, "⚔", NamedTextColor.RED,
            new String[]{"Permanent Strength I", "10% chance to land a crit when you are not already critting"},
            new Ability("Crit Frenzy", "Every hit is a guaranteed crit.", 90, "entity.iron_golem.attack"),
            new Ability("Rage", "Gain Strength II.", 120, "entity.ravager.roar")),

    SPIDER("Spider", Material.SPIDER_EYE, "✱", NamedTextColor.DARK_RED,
            new String[]{"Permanent Weaving I", "Climb walls (walk into a wall, sneak to hold on)"},
            new Ability("Evasion", "25% chance to take no damage from players.", 90, "entity.spider.ambient"),
            new Ability("Venom Bite", "Your next hit applies Slowness II.", 120, "entity.spider.hurt")),

    MINER("Miner", Material.DIAMOND_PICKAXE, "⛏", NamedTextColor.GRAY,
            new String[]{"Permanent Haste I", "Permanent Resistance I"},
            new Ability("Overdrive", "Gain Haste VI.", 90, "block.anvil.use"),
            new Ability("Iron Skin", "Gain Resistance III.", 120, "block.grindstone.use")),

    FIRE("Fire", Material.BLAZE_POWDER, "♨", NamedTextColor.GOLD,
            new String[]{"Permanent Fire Resistance", "Crossbow arrows are on fire"},
            new Ability("Cinder Heal", "Regeneration I while you are on fire.", 90, "item.firecharge.use"),
            new Ability("Inferno", "Sets nearby players on fire and removes all water around you.", 120, "entity.blaze.shoot")),

    DISABLE("Disable", Material.BARRIER, "✖", NamedTextColor.DARK_GRAY,
            new String[]{"10% chance to disable a shield even without an axe", "5% chance the victim's next wind charge fails (does not stack)"},
            new Ability("Item Lock", "Disable the item an enemy is holding.", 90, "item.shield.break"),
            new Ability("Power Lock", "Disable an enemy's power.", 120, "block.beacon.deactivate")),

    HEALTH("Health", Material.GOLDEN_APPLE, "❤", NamedTextColor.GREEN,
            new String[]{"Shows enemy health every so often when you hit them", "5% chance to gain Regeneration II for 3 seconds when you hit an enemy"},
            new Ability("Mend", "Gain Regeneration III.", 90, "entity.player.levelup"),
            new Ability("Life Steal", "Steal max health from an enemy.", 120, "entity.witch.drink")),

    ABSORBED("Absorbed", Material.NETHERITE_CHESTPLATE, "◆", NamedTextColor.DARK_PURPLE,
            new String[]{"10% chance to reflect part of the damage you take", "Netherite-level knockback resistance"},
            new Ability("Reflect", "Enemies take half of the damage they deal to you.", 90, "block.respawn_anchor.charge"),
            new Ability("Absorb & Release", "Absorb all damage, then release half of it in a burst around you.", 120, "block.beacon.power_select")),

    GRAVITY("Gravity", Material.MACE, "⬇", NamedTextColor.DARK_BLUE,
            new String[]{"Your axes act like an unenchanted mace (smash bonus when falling)", "5% chance to pull an enemy to you after hitting them"},
            new Ability("Slam", "Throw an enemy up, then slam them down.", 60, "entity.ender_dragon.flap"),
            new Ability("Gravity Well", "Nearby enemies can't jump or use wind charges.", 90, "entity.enderman.teleport")),

    EXPLOSIVE("Explosive", Material.TNT, "✸", NamedTextColor.RED,
            new String[]{"Crossbow fireworks deal more damage", "Immune to explosion damage"},
            new Ability("Bomb", "Throw a bomb that explodes on impact (no block damage).", 90, "entity.tnt.primed"),
            new Ability("Self Detonate", "Explode in an area and launch enemies away (no block damage).", 150, "entity.generic.explode")),

    MESSY("Messy", Material.CHORUS_FRUIT, "✿", NamedTextColor.YELLOW,
            new String[]{"1% chance to shove an enemy's main-hand item into their offhand", "5% chance to freeze an enemy in place for half a second"},
            new Ability("Root", "Hold an enemy in place.", 90, "entity.cat.hiss"),
            new Ability("Scramble", "Shuffle an enemy's hotbar.", 150, "entity.item.pickup")),

    WATER("Water", Material.HEART_OF_THE_SEA, "≈", NamedTextColor.BLUE,
            new String[]{"Nearby enemies slowly drown (they recover when they leave)", "Conduit Power while in water or rain"},
            new Ability("Deluge", "The drowning aura speeds up massively.", 90, "entity.dolphin.splash"),
            new Ability("Trident Wave", "Send a wave of tridents that push enemies back and deal damage.", 120, "item.trident.throw")),

    SOUL("Soul", Material.SOUL_LANTERN, "☠", NamedTextColor.DARK_AQUA,
            new String[]{"Speed II on soul sand and soul soil", "Immune to Wither"},
            new Ability("Withering Touch", "Every hit inflicts Wither.", 90, "particle.soul_escape"),
            new Ability("Skull Barrage", "Fire 3 wither skulls that deal true damage and apply Wither.", 90, "entity.wither.shoot")),

    LIGHTNING("Lightning", Material.LIGHTNING_ROD, "⚡", NamedTextColor.YELLOW,
            new String[]{"Thrown tridents that hit strike lightning (1 heart)", "Crossbow arrows strike lightning (half a heart)"},
            new Ability("Storm", "Lightning rains down around you.", 90, "entity.lightning_bolt.thunder"),
            new Ability("Thunder Touch", "25% chance to strike with lightning on every hit.", 120, "entity.lightning_bolt.impact")),

    DARKNESS("Darkness", Material.SCULK_SHRIEKER, "●", NamedTextColor.DARK_GRAY,
            new String[]{"Crossbow arrows inflict Blindness and Darkness", "Immune to Blindness and Darkness"},
            new Ability("Shadow Step", "Teleport behind an enemy.", 15, "entity.enderman.teleport"),
            new Ability("Sculk Domain", "A huge sculk dome. Enemies inside get Slowness and Darkness. It can only be broken from outside.", 210, "entity.warden.sonic_charge")),

    LIGHT("Light", Material.GLOWSTONE, "☀", NamedTextColor.WHITE,
            new String[]{"Crossbow arrows make enemies glow", "Damage boost by day, a smaller boost at night"},
            new Ability("Radiance", "Reveal the health of nearby enemies and make them glow.", 60, "block.beacon.activate"),
            new Ability("Vanish", "True invisibility.", 120, "entity.evoker.cast_spell")),

    WIND("Wind", Material.WIND_CHARGE, "☁", NamedTextColor.WHITE,
            new String[]{"Wind charges are stronger", "Wind charges can't be disabled by other abilities"},
            new Ability("Gale", "No wind charge cooldown.", 60, "entity.wind_charge.wind_burst"),
            new Ability("Cloud Walk", "Spawn clouds under your feet that you can walk on. Nothing can be placed on them.", 90, "entity.phantom.flap")),

    GAMBLE("Gamble", Material.RABBIT_FOOT, "♠", NamedTextColor.GOLD,
            new String[]{"5% chance to deal an extra heart of damage", "5% chance not to use up XP bottles, pearls, gapples, egapples or wind charges"},
            new Ability("Curse Roll", "Give an enemy a random Tier II negative effect.", 90, "block.note_block.bell"),
            new Ability("Blessing Roll", "Gain a random Tier II positive effect.", 120, "entity.experience_orb.pickup")),

    ICE("Ice", Material.BLUE_ICE, "❄", NamedTextColor.AQUA,
            new String[]{"Speed V on ice", "Immune to the freezing effect"},
            new Ability("Frostbite", "Every hit freezes the enemy.", 90, "block.powder_snow.break"),
            new Ability("Icicles", "Shoot 3 icicles that deal true damage and freeze.", 120, "entity.snow_golem.shoot")),

    POISON("Poison", Material.POISONOUS_POTATO, "☣", NamedTextColor.DARK_GREEN,
            new String[]{"Immune to Poison", "Crossbow arrows inflict Poison"},
            new Ability("Toxic Cloud", "Poison nearby enemies and cure nearby allies.", 90, "entity.witch.throw"),
            new Ability("Virulence", "Debuffs on nearby enemies go up one tier and last longer.", 150, "entity.puffer_fish.sting"));

    private final String displayName;
    private final Material icon;
    private final String symbol;
    private final NamedTextColor color;
    private final String[] passives;
    private final Ability a1;
    private final Ability a2;

    Power(String displayName, Material icon, String symbol, NamedTextColor color,
          String[] passives, Ability a1, Ability a2) {
        this.displayName = displayName;
        this.icon = icon;
        this.symbol = symbol;
        this.color = color;
        this.passives = passives;
        this.a1 = a1;
        this.a2 = a2;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String symbol() { return symbol; }
    public NamedTextColor color() { return color; }
    public String[] passives() { return passives; }
    public Ability ability(int slot) { return slot == 1 ? a1 : a2; }

    public static Power fromName(String s) {
        for (Power p : values()) {
            if (p.name().equalsIgnoreCase(s) || p.displayName.equalsIgnoreCase(s)) return p;
        }
        return null;
    }
}
