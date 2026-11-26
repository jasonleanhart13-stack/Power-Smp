package dev.powers;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Location;
import org.bukkit.Color;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Holds all per-player state: powers, cooldowns, boss bars, timers, and runs the passive tick loop. */
public final class PowerManager {
    public final PowersPlugin plugin;
    public final NamespacedKey powerKey;
    public final NamespacedKey power2Key;
    public final NamespacedKey projKey;
    private final NamespacedKey kbKey;
    public final NamespacedKey stealKey;

    private final Map<UUID, Power> cache = new HashMap<>();
    private final Map<UUID, Power> second = new HashMap<>();
    private final Set<UUID> secondLoaded = new HashSet<>();
    private final Map<UUID, Set<Legendary>> carried = new HashMap<>();
    private final Map<String, Map<UUID, Long>> timers = new HashMap<>();
    private final Map<UUID, Map<Material, Long>> disabledItems = new HashMap<>();
    private final Map<UUID, Long> lastHealthShow = new HashMap<>();
    private final Map<UUID, Double> drown = new HashMap<>();
    private final Set<UUID> kbOn = new HashSet<>();
    private final Map<UUID, Long> carriedAt = new HashMap<>();
    private final Map<UUID, Set<UUID>> trusts = new HashMap<>();
    private final Map<String, TextDisplay> floating = new HashMap<>();
    private final Map<String, BlockDisplay> outlines = new HashMap<>();
    private final Set<UUID> chestOn = new HashSet<>();
    private final NamespacedKey chestKey;
    /** Netherite Boots: temporary blocks over water/lava/powder snow, and where each wearer last left fire. */
    private final Map<Block, BlockData> platOrig = new HashMap<>();
    private final Map<Block, Material> platType = new HashMap<>();
    private final Map<Block, Long> platEnd = new HashMap<>();
    private final Map<UUID, Location> trail = new HashMap<>();
    /** Entities set on fire by an ability: id -> {end time, source id as two longs are avoided; source kept separately}. */
    private final Map<UUID, Long> fireEnd = new HashMap<>();
    private final Map<UUID, UUID> fireSource = new HashMap<>();

    public final Map<UUID, Double> absorbed = new HashMap<>();
    public final Set<UUID> hidden = new HashSet<>();
    /** Spear: when a player last swung and their exhaustion at that moment. */
    public final Map<UUID, double[]> swing = new HashMap<>();
    /** Tracker: where each player last used an item. */
    public final Map<UUID, UseRecord> lastUse = new HashMap<>();
    /** True while the plugin itself is dealing damage, so our damage handlers ignore it. */
    public boolean internalDamage = false;

    public record UseRecord(String player, String what, String world, int x, int y, int z, long time) {}

    /** Boss bar slots: 1-2 primary power, 3-4 second power (Double Power), 5-6 legendary item. */
    private static final int SLOTS = 12;

    private static final class Cd {
        final long[] end = new long[SLOTS];
        final long[] total = new long[SLOTS];
        final String[] label = new String[SLOTS];
        final BossBar[] bar = new BossBar[SLOTS];
        final long[] activeEnd = new long[SLOTS];
        final String[] activeLabel = new String[SLOTS];
        final long[] activeCd = new long[SLOTS];
        final long[] activeTotal = new long[SLOTS];
    }
    private final Map<UUID, Cd> cds = new HashMap<>();

    private static final Power[] NONE_POWERS = new Power[0];
    private static final PotionEffectType[] NONE = new PotionEffectType[0];
    private static final Map<Power, PotionEffectType[]> PASSIVE = new EnumMap<>(Power.class);
    static {
        PASSIVE.put(Power.SPEED, new PotionEffectType[]{PotionEffectType.SPEED});
        PASSIVE.put(Power.STRENGTH, new PotionEffectType[]{PotionEffectType.STRENGTH});
        PASSIVE.put(Power.SPIDER, new PotionEffectType[]{PotionEffectType.WEAVING});
        PASSIVE.put(Power.MINER, new PotionEffectType[]{PotionEffectType.HASTE, PotionEffectType.RESISTANCE});
        PASSIVE.put(Power.FIRE, new PotionEffectType[]{PotionEffectType.FIRE_RESISTANCE});
    }
    /** Conditional passive effects (applied only in some situations) that also need cleanup. */
    private static final PotionEffectType[] EXTRA = {PotionEffectType.CONDUIT_POWER, PotionEffectType.SPEED};

    private int tick = 0;

    public PowerManager(PowersPlugin plugin) {
        this.plugin = plugin;
        this.powerKey = new NamespacedKey(plugin, "power");
        this.power2Key = new NamespacedKey(plugin, "power2");
        this.projKey = new NamespacedKey(plugin, "proj");
        this.kbKey = new NamespacedKey(plugin, "absorbed_kb");
        this.stealKey = new NamespacedKey(plugin, "health_steal");
        this.chestKey = new NamespacedKey(plugin, "chestplate_hearts");
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("trust");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    Set<UUID> set = new HashSet<>();
                    for (String v : plugin.getConfig().getStringList("trust." + k)) set.add(UUID.fromString(v));
                    trusts.put(UUID.fromString(k), set);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ trust

    /** True if "owner" trusts "other": owner's abilities never affect other. */
    public boolean trusts(Player owner, Player other) {
        Set<UUID> s = trusts.get(owner.getUniqueId());
        return s != null && s.contains(other.getUniqueId());
    }

    public void setTrust(Player owner, Player other, boolean on) {
        Set<UUID> s = trusts.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>());
        if (on) s.add(other.getUniqueId()); else s.remove(other.getUniqueId());
        java.util.List<String> out = new ArrayList<>();
        for (UUID u : s) out.add(u.toString());
        plugin.getConfig().set("trust." + owner.getUniqueId(), out.isEmpty() ? null : out);
        plugin.saveConfig();
    }

    public void setTrustById(Player owner, UUID other, boolean on) {
        Set<UUID> s = trusts.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>());
        if (on) s.add(other); else s.remove(other);
        java.util.List<String> out = new ArrayList<>();
        for (UUID u : s) out.add(u.toString());
        plugin.getConfig().set("trust." + owner.getUniqueId(), out.isEmpty() ? null : out);
        plugin.saveConfig();
    }

    public java.util.List<String> trustedNames(Player owner) {
        java.util.List<String> names = new ArrayList<>();
        Set<UUID> s = trusts.get(owner.getUniqueId());
        if (s == null) return names;
        for (UUID u : s) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            names.add(n == null ? u.toString() : n);
        }
        return names;
    }

    // ------------------------------------------------------------------ powers

    public void ensurePower(Player p) {
        String s = p.getPersistentDataContainer().get(powerKey, PersistentDataType.STRING);
        Power pw = null;
        if (s != null) {
            try { pw = Power.valueOf(s); } catch (IllegalArgumentException ignored) { }
        }
        if (pw == null) {
            Power[] all = Power.values();
            pw = all[ThreadLocalRandom.current().nextInt(all.length)];
            p.getPersistentDataContainer().set(powerKey, PersistentDataType.STRING, pw.name());
            Component title = Component.text(pw.symbol() + " " + pw.displayName(), pw.color());
            p.showTitle(Title.title(title, Component.text("is your power! Use /ability 1, /ability 2", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.sendMessage(Component.text("You received the ", NamedTextColor.GRAY)
                    .append(title).append(Component.text(" power!", NamedTextColor.GRAY)));
        }
        cache.put(p.getUniqueId(), pw);
        refreshCarried(p);
    }

    /** The primary power stored for this player, ignoring "disabled" state. */
    public Power rawPower(Player p) {
        Power r = cache.get(p.getUniqueId());
        if (r == null) {
            ensurePower(p);
            r = cache.get(p.getUniqueId());
        }
        return r;
    }

    /** The second power stored for this player (only usable while carrying Double Power). */
    public Power secondRaw(Player p) {
        UUID id = p.getUniqueId();
        if (!secondLoaded.contains(id)) {
            String s = p.getPersistentDataContainer().get(power2Key, PersistentDataType.STRING);
            if (s != null) {
                try { second.put(id, Power.valueOf(s)); } catch (IllegalArgumentException ignored) { }
            }
            secondLoaded.add(id);
        }
        return second.get(id);
    }

    /** The primary power currently in effect (null if disabled by the Disable power). */
    public Power power(Player p) {
        Power r = rawPower(p);
        if (r == null) return null;
        if (has("disabled", p.getUniqueId())) return null;
        return r;
    }

    /** The second power currently in effect (needs Double Power in the inventory, and not disabled). */
    public Power secondPower(Player p) {
        if (has("disabled", p.getUniqueId())) return null;
        Power s = secondRaw(p);
        if (s == null || !carries(p, Legendary.DOUBLE_POWER)) return null;
        return s;
    }

    /** Every power currently in effect (0, 1 or 2). */
    public Power[] powers(Player p) {
        Power a = power(p);
        Power b = secondPower(p);
        if (a != null && b != null) return new Power[]{a, b};
        if (a != null) return new Power[]{a};
        if (b != null) return new Power[]{b};
        return NONE_POWERS;
    }

    public boolean hasPower(Player p, Power pw) {
        return power(p) == pw || secondPower(p) == pw;
    }

    /** Owns the power, even if it is currently disabled. */
    public boolean ownsPower(Player p, Power pw) {
        return rawPower(p) == pw || (secondRaw(p) == pw && carries(p, Legendary.DOUBLE_POWER));
    }

    public boolean is(Player p, Power which) { return hasPower(p, which); }

    /** Is this power's ability n active right now (for whichever slot the power occupies)? */
    public boolean act(Player p, Power pw, int n) {
        UUID id = p.getUniqueId();
        if (power(p) == pw && has("active" + n, id)) return true;
        return secondPower(p) == pw && has("active" + (n + 2), id);
    }

    /** The slot (1-4) that ability n of this power uses for this player. */
    public int slotFor(Player p, Power pw, int n) {
        return power(p) == pw ? n : n + 2;
    }

    public void setPower(Player p, Power pw) {
        Power old = rawPower(p);
        if (old != null) strip(p, old);
        setKb(p, false);
        UUID id = p.getUniqueId();
        for (String k : new String[]{"active1", "active2", "active3", "active4", "jinx"}) clearTimer(k, id);
        absorbed.remove(id);
        resetCooldowns(p);
        if (pw == secondRaw(p)) {
            second.remove(id);
            p.getPersistentDataContainer().remove(power2Key);
        }
        p.getPersistentDataContainer().set(powerKey, PersistentDataType.STRING, pw.name());
        cache.put(id, pw);
        p.sendMessage(Component.text("Your power is now ", NamedTextColor.GRAY)
                .append(Component.text(pw.symbol() + " " + pw.displayName(), pw.color())));
    }

    /** Sets the second power (Double Power). Returns false if it is the same as the primary power. */
    public boolean setSecond(Player p, Power pw) {
        if (pw == rawPower(p)) return false;
        UUID id = p.getUniqueId();
        Power old = secondRaw(p);
        if (old != null) strip(p, old);
        for (String k : new String[]{"active3", "active4"}) clearTimer(k, id);
        p.getPersistentDataContainer().set(power2Key, PersistentDataType.STRING, pw.name());
        second.put(id, pw);
        secondLoaded.add(id);
        p.sendMessage(Component.text("Your second power is now ", NamedTextColor.GRAY)
                .append(Component.text(pw.symbol() + " " + pw.displayName(), pw.color())));
        return true;
    }

    public boolean isAlly(Player a, Player b) {
        if (a == null || b == null) return false;
        if (trusts(a, b)) return true;
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(a.getName());
        return t != null && t.hasEntry(b.getName());
    }

    // ------------------------------------------------------------------ legendary helpers

    public void refreshCarried(Player p) {
        UUID id = p.getUniqueId();
        EnumSet<Legendary> set = EnumSet.noneOf(Legendary.class);
        for (ItemStack it : p.getInventory().getContents()) {
            Legendary l = Legendary.of(it);
            if (l != null) set.add(l);
        }
        Legendary onCursor = Legendary.of(p.getItemOnCursor());
        if (onCursor != null) set.add(onCursor);
        Set<Legendary> old = carried.put(id, set);
        carriedAt.put(id, System.currentTimeMillis());
        if (old != null) {
            for (Legendary l : set) if (!old.contains(l)) popup(p, l, true);
            for (Legendary l : old) if (!set.contains(l)) popup(p, l, false);
        }
        // First time holding Double Power: give a random second power
        if (set.contains(Legendary.DOUBLE_POWER) && cache.get(id) != null && secondRaw(p) == null) {
            randomSecond(p, null);
        }
    }

    /** Has this legendary anywhere in their inventory (re-checked at least every half second). */
    public boolean carries(Player p, Legendary l) {
        UUID id = p.getUniqueId();
        Long at = carriedAt.get(id);
        if (at == null || System.currentTimeMillis() - at > 500) {
            if (p.isOnline()) refreshCarried(p);
        }
        Set<Legendary> s = carried.get(id);
        return s != null && s.contains(l);
    }

    /** Big title for everyone when a legendary is gained or lost. */
    private void popup(Player p, Legendary l, boolean gained) {
        if (l == Legendary.TRACKER) return; // the Tracker is announced to nobody
        Component title = Component.text("★ " + l.displayName() + " ★", l.color());
        Component sub = gained
                ? Component.text(p.getName() + " now holds this legendary!", NamedTextColor.GOLD)
                : Component.text(p.getName() + " no longer holds this legendary!", NamedTextColor.RED);
        broadcastTitle(title, sub);
        Bukkit.broadcast(Component.text("★ ", NamedTextColor.GOLD).append(sub.color(NamedTextColor.GOLD))
                .append(Component.text(" (" + l.displayName() + ")", l.color())));
    }

    public void voidPopup(Legendary l, String who) {
        if (l == Legendary.TRACKER) return;
        Component title = Component.text("★ " + l.displayName() + " ★", l.color());
        Component sub = Component.text((who == null ? "It" : "It, dropped by " + who + ",") + " was lost to the void!", NamedTextColor.DARK_RED);
        broadcastTitle(title, sub);
        Bukkit.broadcast(Component.text("★ The " + l.displayName() + " was lost to the void!", NamedTextColor.DARK_RED));
    }

    private void broadcastTitle(Component title, Component sub) {
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(t);
            o.playSound(o.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private Power randomPower(Power... not) {
        Power[] all = Power.values();
        for (int tries = 0; tries < 100; tries++) {
            Power c = all[ThreadLocalRandom.current().nextInt(all.length)];
            boolean bad = false;
            for (Power n : not) if (n == c) bad = true;
            if (!bad) return c;
        }
        return all[0];
    }

    /** Picks a random second power that is different from the main power (and from "avoid", if given). */
    public void randomSecond(Player p, Power avoid) {
        Power main = cache.get(p.getUniqueId());
        if (main == null) return;
        setSecond(p, randomPower(main, avoid));
    }

    /** Reroll Book: new random main power, and a new second power too if you carry Double Power. */
    public void reroll(Player p) {
        Power oldMain = rawPower(p);
        Power oldSecond = secondRaw(p);
        Power n = randomPower(oldMain);
        setPower(p, n);
        if (carries(p, Legendary.DOUBLE_POWER)) {
            setSecond(p, randomPower(n, oldSecond));
        }
        Component title = Component.text(n.symbol() + " " + n.displayName(), n.color());
        p.showTitle(Title.title(title, Component.text("is your new power!", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
    }

    /**
     * Sets a target on fire from an ability for exactly 5 seconds. Water does NOT put it out, and Fire Resistance
     * (including the Fire power) does NOT protect: those targets take 0.5 hearts of true damage per second instead.
     */
    public void igniteByAbility(LivingEntity target, int ignoredTicks, LivingEntity source) {
        if (target == source) return;
        target.setFireTicks(100);
        fireEnd.put(target.getUniqueId(), System.currentTimeMillis() + 5000L);
        if (source != null) fireSource.put(target.getUniqueId(), source.getUniqueId());
    }

    private void tickAbilityFire(boolean damageNow) {
        if (fireEnd.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(fireEnd.keySet())) {
            Entity en = Bukkit.getEntity(id);
            long end = fireEnd.get(id);
            if (!(en instanceof LivingEntity le) || !le.isValid() || le.isDead() || end <= now) {
                fireEnd.remove(id);
                fireSource.remove(id);
                if (en instanceof LivingEntity le2 && le2.isValid() && end <= now) le2.setFireTicks(0);
                continue;
            }
            // keep burning even in water
            int left = (int) ((end - now) / 50);
            if (le.getFireTicks() < left) le.setFireTicks(left);
            if (!damageNow) continue;
            boolean resistant = le.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                    || (le instanceof Player pl && (hasPower(pl, Power.FIRE) || wears(pl, Legendary.NETHERITE_BOOTS)));
            if (!resistant && !le.isInWater()) continue; // everybody else already takes normal fire damage
            Entity src = Bukkit.getEntity(fireSource.getOrDefault(id, id));
            dealDamage(le, 1.0, src instanceof LivingEntity ls ? ls : null);
        }
    }

    // ------------------------------------------------------------------ floating text above an entity (only the viewer sees it)

    /** Wearing this legendary armor piece (in its armor slot). */
    public boolean wears(Player p, Legendary l) {
        PlayerInventory inv = p.getInventory();
        return Legendary.of(inv.getHelmet()) == l || Legendary.of(inv.getChestplate()) == l
                || Legendary.of(inv.getLeggings()) == l || Legendary.of(inv.getBoots()) == l;
    }

    public boolean hasOutline(Player viewer, LivingEntity target) {
        BlockDisplay d = outlines.get(viewer.getUniqueId() + ":" + target.getUniqueId());
        return d != null && d.isValid();
    }

    /**
     * A glow-like outline of the target that ONLY the viewer can see (it is a private, glowing body-sized box
     * that follows the target, so it also shows through walls).
     */
    public void showOutline(Player viewer, LivingEntity target, BooleanSupplier keep) {
        String key = viewer.getUniqueId() + ":" + target.getUniqueId();
        BlockDisplay old = outlines.remove(key);
        if (old != null && old.isValid()) old.remove();
        final BlockDisplay d = target.getWorld().spawn(target.getLocation(), BlockDisplay.class, bd -> {
            bd.setVisibleByDefault(false);
            bd.setPersistent(false);
            bd.setBlock(Material.LIGHT_GRAY_STAINED_GLASS.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-0.3f, 0f, -0.3f), new AxisAngle4f(),
                    new Vector3f(0.6f, 1.8f, 0.6f), new AxisAngle4f()));
            bd.setGlowing(true);
            bd.setGlowColorOverride(Color.WHITE);
            bd.setTeleportDuration(2);
        });
        viewer.showEntity(plugin, d);
        outlines.put(key, d);
        new BukkitRunnable() {
            @Override public void run() {
                if (!d.isValid() || !target.isValid() || target.isDead() || !viewer.isOnline() || !keep.getAsBoolean()) {
                    d.remove();
                    outlines.remove(key, d);
                    cancel();
                    return;
                }
                d.teleport(target.getLocation());
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    public boolean hasFloating(Player viewer, LivingEntity target) {
        TextDisplay d = floating.get(viewer.getUniqueId() + ":" + target.getUniqueId());
        return d != null && d.isValid();
    }

    public boolean isAbilityFire(UUID id) {
        Long end = fireEnd.get(id);
        return end != null && end > System.currentTimeMillis();
    }

    public void showAbove(Player viewer, LivingEntity target, Supplier<Component> text, int ticks) {
        showAbove(viewer, target, text, ticks, null);
    }

    public void showAbove(Player viewer, LivingEntity target, Supplier<Component> text, int ticks, BooleanSupplier keep) {
        String key = viewer.getUniqueId() + ":" + target.getUniqueId();
        TextDisplay old = floating.remove(key);
        if (old != null && old.isValid()) old.remove();
        Location loc = target.getLocation().add(0, target.getHeight() + 0.45, 0);
        final TextDisplay td = target.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(text.get());
        });
        viewer.showEntity(plugin, td);
        floating.put(key, td);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!td.isValid() || !target.isValid() || target.isDead() || !viewer.isOnline() || t >= ticks
                        || (keep != null && !keep.getAsBoolean())) {
                    td.remove();
                    floating.remove(key, td);
                    cancel();
                    return;
                }
                t += 2;
                td.teleport(target.getLocation().add(0, target.getHeight() + 0.45, 0));
                td.text(text.get());
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    /** Has the legendary Shield anywhere in the inventory. */
    public boolean shielded(Player p) {
        return carries(p, Legendary.SHIELD);
    }

    /** A passive chance, boosted by the Power Amplifier. */
    public double chance(Player p, double base) {
        return carries(p, Legendary.POWER_AMPLIFIER) ? Math.min(1.0, base * 1.5) : base;
    }

    public void recordUse(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        recordUseNamed(p, it.getType().name().toLowerCase().replace('_', ' '));
    }

    public void recordUseNamed(Player p, String what) {
        var l = p.getLocation();
        lastUse.put(p.getUniqueId(), new UseRecord(p.getName(), what,
                l.getWorld() == null ? "?" : l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ timers

    public void setTimer(String key, UUID id, long millis) {
        timers.computeIfAbsent(key, k -> new HashMap<>()).put(id, System.currentTimeMillis() + millis);
    }

    public boolean has(String key, UUID id) {
        Map<UUID, Long> m = timers.get(key);
        if (m == null) return false;
        Long t = m.get(id);
        if (t == null) return false;
        if (t <= System.currentTimeMillis()) {
            m.remove(id);
            return false;
        }
        return true;
    }

    public void clearTimer(String key, UUID id) {
        Map<UUID, Long> m = timers.get(key);
        if (m != null) m.remove(id);
    }

    public void onDeath(Player p) {
        for (int i = 1; i <= SLOTS; i++) finishAbility(p, i);
        UUID id = p.getUniqueId();
        for (Map<UUID, Long> m : timers.values()) m.remove(id);
        absorbed.remove(id);
        drown.remove(id);
        kbOn.remove(id);
    }

    // ------------------------------------------------------------------ tags / damage helpers

    public void tag(Entity e, String value) {
        e.getPersistentDataContainer().set(projKey, PersistentDataType.STRING, value);
    }

    public String tagOf(Entity e) {
        return e.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
    }

    private boolean reflecting = false;

    /** TRUE damage: ignores armor, protection and resistance. Used for every ability's damage. */
    public void dealDamage(LivingEntity target, double amount, LivingEntity source) {
        if (source instanceof Player sp) {
            if (carries(sp, Legendary.POWER_AMPLIFIER)) amount *= 1.2;
            if (target instanceof Player tp && sp != tp && trusts(sp, tp)) return;
        }
        if (amount <= 0 || target.isDead() || !target.isValid() || target.isInvulnerable()) return;
        if (target instanceof Player pl) {
            GameMode gm = pl.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
            UUID id = pl.getUniqueId();
            if (hasPower(pl, Power.ABSORBED)) {
                if (act(pl, Power.ABSORBED, 2)) {
                    absorbed.merge(id, amount, Double::sum);
                    absorbCue(pl);
                    return;
                }
                if (source != null && source != pl && !reflecting) {
                    boolean strong = act(pl, Power.ABSORBED, 1);
                    if (strong) absorbCue(pl);
                    // passive: 10% chance to send 25% back. Ability 1: half back.
                    if (strong || ThreadLocalRandom.current().nextDouble() < chance(pl, 0.10)) {
                        reflecting = true;
                        try {
                            dealDamage(source, amount * (strong ? 0.5 : 0.25), pl);
                        } finally {
                            reflecting = false;
                        }
                    }
                }
            }
        }
        double hp = target.getHealth();
        if (amount < hp) {
            target.setHealth(hp - amount);
            target.playHurtAnimation(0f);
            if (target instanceof Player) Util.sound(target.getLocation(), "entity.player.hurt", 1f, 1f);
        } else {
            internalDamage = true;
            try {
                target.setNoDamageTicks(0);
                target.damage(1_000_000.0, source);
            } finally {
                internalDamage = false;
            }
        }
    }

    /** The sound and sparkle played every time an Absorbed ability catches a hit. */
    public void absorbCue(Player pl) {
        Location l = pl.getLocation().add(0, 1, 0);
        pl.getWorld().spawnParticle(Particle.ENCHANT, l, 20, 0.4, 0.8, 0.4, 0.5);
        Util.sound(l, "block.amethyst_cluster.break", 1.2f, 0.5f);
        Util.sound(l, "block.sculk_sensor.clicking", 1.0f, 0.7f);
        Util.sound(l, "entity.warden.sonic_charge", 0.4f, 2.0f);
    }

    /** Explosion-type ability damage: the legendary Shield makes you immune. */
    public void dealExplosion(LivingEntity target, double amount, LivingEntity source) {
        if (target instanceof Player pl && shielded(pl)) return;
        dealDamage(target, amount, source);
    }

    public void freeze(LivingEntity target, long millis) {
        if (target instanceof Player pl && (hasPower(pl, Power.ICE) || shielded(pl))) return;
        setTimer("freeze", target.getUniqueId(), millis);
    }

    public boolean throttle(UUID id, long ms) {
        long now = System.currentTimeMillis();
        Long last = lastHealthShow.get(id);
        if (last != null && now - last < ms) return false;
        lastHealthShow.put(id, now);
        return true;
    }

    // ------------------------------------------------------------------ disabled items

    public void disableItem(UUID id, Material m, long millis) {
        disabledItems.computeIfAbsent(id, k -> new HashMap<>()).put(m, System.currentTimeMillis() + millis);
    }

    public boolean isItemDisabled(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        Map<Material, Long> m = disabledItems.get(p.getUniqueId());
        if (m == null) return false;
        Long t = m.get(it.getType());
        if (t == null) return false;
        if (t <= System.currentTimeMillis()) {
            m.remove(it.getType());
            return false;
        }
        return !(it.getType() == Material.WIND_CHARGE && ownsPower(p, Power.WIND));
    }

    // ------------------------------------------------------------------ attributes

    public void setMod(LivingEntity e, Attribute attr, NamespacedKey key, double amount) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(key);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    private void setKb(Player p, boolean on) {
        UUID id = p.getUniqueId();
        if (on && kbOn.add(id)) {
            setMod(p, Attribute.KNOCKBACK_RESISTANCE, kbKey, 0.4);
        } else if (!on && kbOn.remove(id)) {
            setMod(p, Attribute.KNOCKBACK_RESISTANCE, kbKey, 0);
        }
    }

    // ------------------------------------------------------------------ config: cooldowns and damage

    public static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }

    public int cooldownSeconds(Power pw, int slot) {
        return plugin.getConfig().getInt("cooldowns." + pw.name() + "." + slot, pw.ability(slot).cooldown());
    }

    /** Cooldown in ms for this player (the Cooldown Reducer takes 30% off). */
    public long cooldownMs(Player p, Power pw, int slot) {
        double mult = carries(p, Legendary.COOLDOWN_REDUCER) ? 0.7 : 1.0;
        return Math.round(cooldownSeconds(pw, slot) * 1000.0 * mult);
    }

    public int legendaryCooldownSeconds(Legendary l, int n) {
        Ability a = l.ability(n);
        return plugin.getConfig().getInt("legendary-cooldowns." + l.name() + "." + n, a == null ? 0 : a.cooldown());
    }

    public long legendaryCooldownMs(Player p, Legendary l, int n) {
        double mult = carries(p, Legendary.COOLDOWN_REDUCER) ? 0.7 : 1.0;
        return Math.round(legendaryCooldownSeconds(l, n) * 1000.0 * mult);
    }

    /** Length in seconds of this ability (server-wide setting); 0 means it is instant and has no length. */
    public double lengthSeconds(Power pw, int slot) {
        return plugin.getConfig().getDouble("lengths." + pw.name() + "." + slot, Abilities.duration(pw, slot));
    }

    public static int legendaryDefaultLength(Legendary l, int n) {
        if (l == Legendary.NETHER_AXE && n == 2) return 15;
        if (l == Legendary.SPEAR && n == 2) return 10;
        return 0;
    }

    public double legendaryLengthSeconds(Legendary l, int n) {
        return plugin.getConfig().getDouble("legendary-lengths." + l.name() + "." + n, legendaryDefaultLength(l, n));
    }

    /** Default damage (in hearts) of a power's ability, or null if it does not deal damage. */
    public static Double defaultDamage(Power pw, int slot) {
        return switch (pw) {
            case GRAVITY -> slot == 1 ? 2.0 : null;
            case EXPLOSIVE -> slot == 1 ? 3.0 : 5.0;
            case WATER -> slot == 2 ? 4.0 : null;
            case SOUL -> slot == 2 ? 3.0 : null;
            case LIGHTNING -> slot == 1 ? 1.5 : 2.0;
            case ICE -> slot == 2 ? 3.0 : null;
            case HEALTH -> slot == 2 ? 2.0 : null;
            default -> null;
        };
    }

    public static Double legendaryDefaultDamage(Legendary l, int n) {
        if (l == Legendary.NETHER_AXE && n == 1) return 3.0;
        if (l == Legendary.END_SWORD && n == 2) return 0.5;
        return null;
    }

    public boolean hasDamage(Power pw, int slot) {
        return defaultDamage(pw, slot) != null;
    }

    /** Hearts of damage for this ability (server-wide setting), or -1 if it has no damage. */
    public double hearts(Power pw, int slot) {
        Double def = defaultDamage(pw, slot);
        if (def == null) return -1;
        return plugin.getConfig().getDouble("damage." + pw.name() + "." + slot, def);
    }

    public double legendaryHearts(Legendary l, int n) {
        Double def = legendaryDefaultDamage(l, n);
        if (def == null) return -1;
        return plugin.getConfig().getDouble("legendary-damage." + l.name() + "." + n, def);
    }

    /** Text for menus/info like "3 hearts", or null if the ability deals no damage. */
    public String damageLabel(Power pw, int slot) {
        if (!hasDamage(pw, slot)) return null;
        if (pw == Power.WATER && slot == 2 && !plugin.getConfig().isSet("damage.WATER.2")) return "3-5 hearts";
        double h = hearts(pw, slot);
        String base = fmt(h) + (h == 1.0 ? " heart" : " hearts");
        return pw == Power.HEALTH ? base + " stolen" : base;
    }

    public String legendaryDamageLabel(Legendary l, int n) {
        Double def = legendaryDefaultDamage(l, n);
        if (def == null) return null;
        double h = legendaryHearts(l, n);
        String base = fmt(h) + (h == 1.0 ? " heart" : " hearts");
        return (l == Legendary.END_SWORD) ? base + " per second in the mist" : base + " per hit";
    }

    // ------------------------------------------------------------------ cooldowns / boss bars

    public long remainingMs(Player p, int slot) {
        Cd c = cds.get(p.getUniqueId());
        if (c == null) return 0;
        return Math.max(0, c.end[slot - 1] - System.currentTimeMillis());
    }

    /** Starts the cooldown bar for a slot right now. */
    public void startCooldown(Player p, String label, int slot, long totalMs) {
        Cd c = cds.computeIfAbsent(p.getUniqueId(), k -> new Cd());
        c.total[slot - 1] = totalMs;
        c.end[slot - 1] = System.currentTimeMillis() + totalMs;
        c.label[slot - 1] = label;
        if (p.isOnline()) updateBars(p, System.currentTimeMillis());
    }

    /** Starts an ability. Its cooldown only begins once it has finished (after durationMs, or when finishAbility is called). */
    public void beginAbility(Player p, String label, int slot, long durationMs, long cooldownMs) {
        if (durationMs <= 0) {
            startCooldown(p, label, slot, cooldownMs);
            return;
        }
        Cd c = cds.computeIfAbsent(p.getUniqueId(), k -> new Cd());
        c.activeEnd[slot - 1] = System.currentTimeMillis() + durationMs;
        c.activeTotal[slot - 1] = durationMs;
        c.activeLabel[slot - 1] = label;
        updateBars(p, System.currentTimeMillis());
        c.activeCd[slot - 1] = cooldownMs;
    }

    public boolean isRunning(Player p, int slot) {
        Cd c = cds.get(p.getUniqueId());
        return c != null && c.activeEnd[slot - 1] > 0;
    }

    /** Ends a running ability early and starts its cooldown now. */
    public void finishAbility(Player p, int slot) {
        Cd c = cds.get(p.getUniqueId());
        if (c == null) return;
        int i = slot - 1;
        if (c.activeEnd[i] == 0) return;
        String label = c.activeLabel[i];
        long cd = c.activeCd[i];
        c.activeEnd[i] = 0;
        c.activeLabel[i] = null;
        if (label != null) startCooldown(p, label, slot, cd);
    }

    public void resetCooldowns(Player p) {
        Cd c = cds.get(p.getUniqueId());
        if (c == null) return;
        for (int i = 0; i < SLOTS; i++) {
            c.end[i] = 0;
            c.activeEnd[i] = 0;
            c.activeLabel[i] = null;
            if (c.bar[i] != null) {
                p.hideBossBar(c.bar[i]);
                c.bar[i] = null;
            }
        }
    }

    private void updateBars(Player p, long now) {
        Cd c = cds.get(p.getUniqueId());
        if (c == null) return;
        for (int i = 0; i < SLOTS; i++) {
            if (c.activeEnd[i] > 0 && now >= c.activeEnd[i]) {
                String lab = c.activeLabel[i];
                long cd = c.activeCd[i];
                c.activeEnd[i] = 0;
                c.activeLabel[i] = null;
                if (lab != null) startCooldown(p, lab, i + 1, cd);
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            long activeRem = c.activeEnd[i] - now;
            long rem = c.end[i] - now;
            Component title = null;
            float prog = 0f;
            if (c.activeEnd[i] > 0 && activeRem > 0 && c.activeTotal[i] > 0) {
                // ability is running: bar shows how long it has left
                prog = Math.max(0f, Math.min(1f, (float) activeRem / c.activeTotal[i]));
                title = Component.text(c.activeLabel[i] + " (active)  ", NamedTextColor.YELLOW)
                        .append(Component.text(String.format("%.1fs left", activeRem / 1000.0), NamedTextColor.WHITE));
            } else if (rem > 0 && c.total[i] > 0) {
                prog = Math.max(0f, Math.min(1f, (float) rem / c.total[i]));
                title = Component.text(c.label[i] + " (cooldown)  ", NamedTextColor.YELLOW)
                        .append(Component.text(String.format("%.1fs", rem / 1000.0), NamedTextColor.WHITE));
            }
            if (title != null) {
                if (c.bar[i] == null) {
                    c.bar[i] = BossBar.bossBar(title, prog, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                    p.showBossBar(c.bar[i]);
                } else {
                    c.bar[i].name(title);
                    c.bar[i].progress(prog);
                }
            } else if (c.bar[i] != null) {
                p.hideBossBar(c.bar[i]);
                c.bar[i] = null;
            }
        }
    }

    public void onQuit(Player p) {
        Cd c = cds.get(p.getUniqueId());
        if (c != null) {
            for (int i = 0; i < SLOTS; i++) {
                if (c.bar[i] != null) {
                    p.hideBossBar(c.bar[i]);
                    c.bar[i] = null;
                }
            }
        }
        kbOn.remove(p.getUniqueId());
        chestOn.remove(p.getUniqueId());
        trail.remove(p.getUniqueId());
        carried.remove(p.getUniqueId());
        carriedAt.remove(p.getUniqueId());
    }

    public void shutdown() {
        for (TextDisplay td : floating.values()) td.remove();
        for (BlockDisplay bd : outlines.values()) bd.remove();
        outlines.clear();
        fireEnd.clear();
        fireSource.clear();
        floating.clear();
        tickPlatforms(true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            onQuit(p);
            setMod(p, Attribute.KNOCKBACK_RESISTANCE, kbKey, 0);
            setMod(p, Attribute.MAX_HEALTH, stealKey, 0);
            setMod(p, Attribute.MAX_HEALTH, chestKey, 0);
        }
    }

    // ------------------------------------------------------------------ tick loop (runs every server tick)

    public void tick() {
        tick++;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (tick % 2 == 0) updateBars(p, now);
            if (tick % 10 == 0) refreshCarried(p);
            Power raw = rawPower(p);
            if (raw == null || p.isDead()) continue;
            if (tick % 2 == 0) legendaryTick(p, tick % 10 == 0);
            Power[] ps = powers(p);
            if (ps.length == 0) {
                if (tick % 10 == 0) {
                    strip(p, raw);
                    Power s2 = secondRaw(p);
                    if (s2 != null) strip(p, s2);
                    setKb(p, false);
                }
                continue;
            }
            for (Power pw : ps) {
                if (pw == Power.SPIDER) {
                    climb(p);
                } else if (pw == Power.WIND && act(p, Power.WIND, 1)) {
                    p.setCooldown(Material.WIND_CHARGE, 0);
                }
                if (tick % 5 == 0) applyPassives(p, pw);
            }
            if (tick % 10 == 0) slowTick(p, ps);
        }
        tickDrown();
        if (tick % 2 == 0) tickFreeze();
        if (tick % 2 == 0) tickPlatforms(false);
        if (tick % 2 == 0) tickAbilityFire(tick % 20 == 0);
    }

    private void holdEffect(Player p, PotionEffectType type, int amp) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur == null || cur.getDuration() < 20 || cur.getAmplifier() < amp) Util.passiveEffect(p, type, 60, amp);
    }

    /** Passives of legendary items: they always work, wherever the item is in the inventory. */
    private void legendaryTick(Player p, boolean slow) {
        if (carries(p, Legendary.END_SWORD)) holdEffect(p, PotionEffectType.STRENGTH, 1);
        if (carries(p, Legendary.SPEAR)) {
            holdEffect(p, PotionEffectType.SPEED, 1);
            double[] sw = swing.get(p.getUniqueId());
            if (sw != null && System.currentTimeMillis() - sw[0] < 3000) {
                // Lunging must not cost hunger: put back anything lost since the swing (eating still works)
                if (p.getExhaustion() > sw[1]) p.setExhaustion((float) sw[1]);
                int food = p.getFoodLevel();
                if (food < (int) sw[2]) {
                    p.setFoodLevel((int) sw[2]);
                    p.setSaturation((float) Math.max(sw[3], p.getSaturation()));
                } else {
                    sw[2] = food;
                    if (p.getSaturation() > sw[3]) sw[3] = p.getSaturation();
                }
            }
        }
        if (carries(p, Legendary.NETHER_AXE)) holdEffect(p, PotionEffectType.FIRE_RESISTANCE, 0);
        armorTick(p, slow);
        if (carries(p, Legendary.SHIELD)) {
            if (p.getCooldown(Material.SHIELD) > 0) p.setCooldown(Material.SHIELD, 0);
            if (slow) {
                for (PotionEffectType t : Util.HARMFUL) p.removePotionEffect(t);
            }
        }
    }

    /** Passives of the legendary armor: they only work while it is worn. */
    private void armorTick(Player p, boolean slow) {
        PlayerInventory inv = p.getInventory();
        UUID id = p.getUniqueId();
        boolean helm = Legendary.of(inv.getHelmet()) == Legendary.NETHERITE_HELMET;
        boolean chest = Legendary.of(inv.getChestplate()) == Legendary.NETHERITE_CHESTPLATE;
        boolean legs = Legendary.of(inv.getLeggings()) == Legendary.NETHERITE_LEGGINGS;
        boolean boots = Legendary.of(inv.getBoots()) == Legendary.NETHERITE_BOOTS;
        if (helm) {
            holdEffect(p, PotionEffectType.INVISIBILITY, 0);
            if (slow) {
                for (Player o : p.getWorld().getNearbyPlayers(p.getLocation(), 15)) {
                    if (o == p || o.isDead() || o.getGameMode() == GameMode.SPECTATOR || hidden.contains(o.getUniqueId())) continue;
                    // glow: a private outline only the helmet wearer can see
                    if (!hasOutline(p, o)) {
                        showOutline(p, o, () -> o.getWorld().equals(p.getWorld())
                                && o.getLocation().distanceSquared(p.getLocation()) <= 17 * 17
                                && wears(p, Legendary.NETHERITE_HELMET));
                    }
                    if (!hasFloating(p, o)) {
                        showAbove(p, o, () -> Component.text(Util.healthLine(o), NamedTextColor.RED), 72000,
                                () -> o.getWorld().equals(p.getWorld())
                                        && o.getLocation().distanceSquared(p.getLocation()) <= 20 * 20
                                        && wears(p, Legendary.NETHERITE_HELMET));
                    }
                }
            }
        }
        if (chest) {
            holdEffect(p, PotionEffectType.HERO_OF_THE_VILLAGE, 2);
            if (chestOn.add(id)) setMod(p, Attribute.MAX_HEALTH, chestKey, 10.0); // +5 hearts
        } else if (chestOn.remove(id)) {
            setMod(p, Attribute.MAX_HEALTH, chestKey, 0);
        }
        if (legs) {
            holdEffect(p, PotionEffectType.REGENERATION, 1);
            // crouching gives Speed III (short effect, refreshed while crouched, so it ends right after you stand up)
            PotionEffect sp = p.getPotionEffect(PotionEffectType.SPEED);
            if (p.isSneaking()) {
                if (sp == null || sp.getAmplifier() < 2 || sp.getDuration() < 8) Util.passiveEffect(p, PotionEffectType.SPEED, 12, 2);
            } else if (sp != null && sp.getAmplifier() == 2 && sp.getDuration() <= 12) {
                p.removePotionEffect(PotionEffectType.SPEED);
            }
        }
        if (boots) {
            holdEffect(p, PotionEffectType.DOLPHINS_GRACE, 0);
            bootsWalk(p);
        } else {
            trail.remove(id);
        }
    }

    private static Material platformFor(Material t) {
        return switch (t) {
            case WATER -> Material.LIGHT_BLUE_STAINED_GLASS;
            case LAVA -> Material.ORANGE_STAINED_GLASS;
            case POWDER_SNOW -> Material.SNOW_BLOCK;
            default -> null;
        };
    }

    /** Netherite Boots: walk on water/lava/powder snow (sneak to sink). */
    private void bootsWalk(Player p) {
        UUID id = p.getUniqueId();
        Location l = p.getLocation();
        World w = l.getWorld();
        long now = System.currentTimeMillis();
        if (p.isSneaking()) return;
        int top = (int) Math.floor(l.getY() - 0.05);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                Block b = w.getBlockAt(l.getBlockX() + dx, top, l.getBlockZ() + dz);
                int steps = 0;
                while (b.getType().isAir() && steps < 6 && b.getY() > w.getMinHeight()) {
                    b = b.getRelative(0, -1, 0);
                    steps++;
                }
                Material pt = platType.get(b);
                if (pt != null) {
                    if (b.getType() == pt) {
                        platEnd.put(b, now + 600L);
                        continue;
                    }
                    platType.remove(b);
                    platOrig.remove(b);
                    platEnd.remove(b);
                }
                Material kind = platformFor(b.getType());
                if (kind == null) continue;
                if (platformFor(b.getRelative(0, 1, 0).getType()) != null) continue; // only the surface
                if (l.getY() - b.getY() < 0.9) continue; // swimming / submerged: don't box the player in
                platOrig.put(b, b.getBlockData());
                platType.put(b, kind);
                platEnd.put(b, now + 600L);
                b.setType(kind, false);
            }
        }
    }

    private void tickPlatforms(boolean force) {
        if (platEnd.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Block, Long>> it = platEnd.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Long> en = it.next();
            if (!force && en.getValue() > now) continue;
            Block b = en.getKey();
            Material pt = platType.remove(b);
            BlockData orig = platOrig.remove(b);
            it.remove();
            Material cur = b.getType();
            if (orig != null && (cur == pt || cur.isAir())) b.setBlockData(orig, false);
        }
    }

    private void applyPassives(Player p, Power pw) {
        int amp = carries(p, Legendary.POWER_AMPLIFIER) ? 1 : 0;
        for (PotionEffectType t : PASSIVE.getOrDefault(pw, NONE)) {
            PotionEffect cur = p.getPotionEffect(t);
            if (cur == null || cur.getDuration() < 30 || cur.getAmplifier() < amp) Util.passiveEffect(p, t, 100, amp);
        }
    }

    private void slowTick(Player p, Power[] ps) {
        boolean hasAbs = false;
        for (Power x : ps) if (x == Power.ABSORBED) hasAbs = true;
        setKb(p, hasAbs);
        Material under = p.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();
        for (Power pw : ps) {
            switch (pw) {
                case FIRE -> {
                    if (act(p, Power.FIRE, 1) && p.getFireTicks() > 0) {
                        Util.effect(p, PotionEffectType.REGENERATION, 40, 0);
                    }
                }
                case SOUL -> {
                    if (under == Material.SOUL_SAND || under == Material.SOUL_SOIL) {
                        Util.passiveEffect(p, PotionEffectType.SPEED, 40, 1);
                    }
                    p.removePotionEffect(PotionEffectType.WITHER);
                }
                case ICE -> {
                    if (under == Material.ICE || under == Material.PACKED_ICE
                            || under == Material.BLUE_ICE || under == Material.FROSTED_ICE) {
                        Util.passiveEffect(p, PotionEffectType.SPEED, 40, 4);
                    }
                    p.setFreezeTicks(0);
                }
                case WATER -> {
                    boolean rain = p.getWorld().hasStorm()
                            && p.getLocation().getBlockY() >= p.getWorld().getHighestBlockYAt(p.getLocation());
                    if (p.isInWater() || rain) Util.passiveEffect(p, PotionEffectType.CONDUIT_POWER, 80, 0);
                }
                case DARKNESS -> {
                    p.removePotionEffect(PotionEffectType.BLINDNESS);
                    p.removePotionEffect(PotionEffectType.DARKNESS);
                }
                case POISON -> p.removePotionEffect(PotionEffectType.POISON);
                default -> { }
            }
        }
    }

    /** Removes passive/conditional effects that belong to a power (only the ones this plugin marked as ambient). */
    private void strip(Player p, Power pw) {
        for (PotionEffectType t : PASSIVE.getOrDefault(pw, NONE)) removeIfAmbient(p, t);
        if (pw == Power.WATER || pw == Power.SOUL || pw == Power.ICE) {
            for (PotionEffectType t : EXTRA) removeIfAmbient(p, t);
        }
    }

    private void removeIfAmbient(Player p, PotionEffectType t) {
        PotionEffect e = p.getPotionEffect(t);
        if (e != null && e.isAmbient()) p.removePotionEffect(t);
    }

    /** Spider wall climbing: walk into a wall to climb, sneak to hold on. */
    private void climb(Player p) {
        if (p.isFlying() || p.isInWater()) return;
        Vector dir = Util.flat(p.getLocation().getDirection());
        if (dir.lengthSquared() < 0.01) return;
        Input in = p.getCurrentInput();
        if (!in.isForward() && !in.isSneak()) return;
        var base = p.getLocation().clone().add(dir.clone().multiply(0.6));
        boolean wall = base.getBlock().isSolid() || base.clone().add(0, 1, 0).getBlock().isSolid();
        if (!wall) return;
        Vector v = p.getVelocity();
        if (in.isForward()) {
            p.setVelocity(new Vector(v.getX(), 0.22, v.getZ()));
        } else if (v.getY() < 0) {
            p.setVelocity(new Vector(v.getX(), 0.0, v.getZ()));
        }
        p.setFallDistance(0f);
    }

    /** Water power's drowning aura. */
    private void tickDrown() {
        Set<UUID> hit = new HashSet<>();
        double radius = plugin.getConfig().getDouble("drowning.radius", 8.0);
        for (Player w : Bukkit.getOnlinePlayers()) {
            if (w.isDead() || !hasPower(w, Power.WATER)) continue;
            double rate = act(w, Power.WATER, 1) ? 14.0 : 1.8; // meter points per second
            for (Player o : w.getWorld().getNearbyPlayers(w.getLocation(), radius)) {
                GameMode gm = o.getGameMode();
                if (o == w || o.isDead() || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
                if (hasPower(o, Power.WATER) || isAlly(w, o) || shielded(o)) continue;
                drown.merge(o.getUniqueId(), rate * 0.05, Double::sum);
                hit.add(o.getUniqueId());
            }
        }
        if (drown.isEmpty()) return;
        for (Player o : Bukkit.getOnlinePlayers()) {
            UUID id = o.getUniqueId();
            Double m = drown.get(id);
            if (m == null) continue;
            if (!hit.contains(id)) m = m - 3.0 * 0.05; // recover 3 points per second
            m = Math.min(100.0, m);
            if (m <= 0) {
                drown.remove(id);
                continue;
            }
            drown.put(id, m);
            if (!o.isInWater()) o.setRemainingAir((int) (o.getMaximumAir() * (1.0 - m / 100.0)));
            if (m >= 100.0 && tick % 20 == 0) o.damage(2.0);
        }
    }

    /** Keeps frozen entities (Ice power) in the freezing state. */
    private void tickFreeze() {
        Map<UUID, Long> fz = timers.get("freeze");
        if (fz == null || fz.isEmpty()) return;
        for (UUID id : new ArrayList<>(fz.keySet())) {
            if (!has("freeze", id)) continue;
            Entity en = Bukkit.getEntity(id);
            if (en instanceof LivingEntity le) {
                le.setFreezeTicks(le.getMaxFreezeTicks());
                if (tick % 20 == 0) Util.effect(le, PotionEffectType.SLOWNESS, 40, 0);
            }
        }
    }
}
