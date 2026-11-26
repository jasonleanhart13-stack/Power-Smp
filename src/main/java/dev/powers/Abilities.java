package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WitherSkull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Implements all 40 abilities. */
public final class Abilities {
    private final PowersPlugin plugin;
    private final PowerManager pm;
    private final Random rng = new Random();

    /** Pending "restore the world" jobs; all run on shutdown so nothing is left behind. */
    private final Set<Runnable> cleanups = new LinkedHashSet<>();
    private final Map<Block, Long> clouds = new HashMap<>();
    /** What each cloud block replaced, so it can be put back. */
    private final Map<Block, BlockData> cloudOrig = new HashMap<>();
    /** Blocks that belong to an active Sculk Domain. */
    public final Map<Block, Dome> domainBlocks = new HashMap<>();
    private final Map<String, Set<UUID>> waveHits = new HashMap<>();

    /** A sculk dome: its center and radius. */
    public record Dome(Location center, double radius) {}
    private int tickCounter = 0;

    private static final PotionEffectType[] NEGATIVE = {
            PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.WEAKNESS,
            PotionEffectType.POISON, PotionEffectType.HUNGER, PotionEffectType.WITHER, PotionEffectType.NAUSEA};
    private static final PotionEffectType[] POSITIVE = {
            PotionEffectType.SPEED, PotionEffectType.HASTE, PotionEffectType.STRENGTH,
            PotionEffectType.RESISTANCE, PotionEffectType.REGENERATION, PotionEffectType.JUMP_BOOST,
            PotionEffectType.ABSORPTION, PotionEffectType.HEALTH_BOOST};

    public Abilities(PowersPlugin plugin, PowerManager pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    // ------------------------------------------------------------------ entry point

    /** Offset added to timer keys: 0 for the primary power, 2 for the Double Power second power. */
    private int koff = 0;
    /** Multiplier for durations while an ability is being started (length setting / default length). */
    private double scale = 1.0;

    private int T(int ticks) { return Math.max(1, (int) Math.round(ticks * scale)); }
    private long M(long ms) { return Math.max(50L, Math.round(ms * scale)); }

    /** slot 1-2: primary power abilities. slot 3-4: second power (Double Power) abilities. */
    public void use(Player p, int slot) {
        boolean second = slot > 2;
        Power pw = second ? pm.secondPower(p) : pm.power(p);
        int n = second ? slot - 2 : slot;
        if (pw == null) {
            if (second) {
                p.sendActionBar(Util.text("You need a second power and the Double Power legendary.", NamedTextColor.RED));
            } else {
                p.sendActionBar(Util.text("Your power is disabled!", NamedTextColor.RED));
            }
            return;
        }
        if (pm.isRunning(p, slot)) {
            p.sendActionBar(Util.text("Ability " + slot + " is still active!", NamedTextColor.RED));
            return;
        }
        long rem = pm.remainingMs(p, slot);
        if (rem > 0) {
            p.sendActionBar(Util.text(String.format("Ability %d is on cooldown: %.1fs", slot, rem / 1000.0), NamedTextColor.RED));
            return;
        }
        koff = second ? 2 : 0;
        // the server's "length" setting for this ability scales every duration inside it
        double base = duration(pw, n);
        double len = pm.lengthSeconds(pw, n);
        scale = base > 0 && len > 0 ? len / base : 1.0;
        boolean ok;
        try {
            ok = run(p, pw, n);
        } finally {
            koff = 0;
            scale = 1.0;
        }
        if (!ok) return;
        Ability ab = pw.ability(n);
        Util.sound(p.getLocation(), ab.sound(), 1.2f, 1.0f);
        long barMs = base > 0 ? Math.round(len * 1000) : 0L;
        pm.beginAbility(p, pw.symbol() + " " + ab.name(), slot, barMs, pm.cooldownMs(p, pw, n));
        p.sendActionBar(Component.text(pw.symbol() + " " + ab.name() + "!", pw.color()));
    }

    private boolean run(Player p, Power pw, int s) {
        return switch (pw) {
            case SPEED -> s == 1 ? doubleJump(p) : bigJump(p);
            case STRENGTH -> s == 1 ? timed(p, 1, 15) : buff(p, PotionEffectType.STRENGTH, 200, 1);
            case SPIDER -> s == 1 ? timed(p, 1, 15) : timed(p, 2, 30);
            case MINER -> s == 1 ? buff(p, PotionEffectType.HASTE, 300, 5) : buff(p, PotionEffectType.RESISTANCE, 200, 2);
            case FIRE -> s == 1 ? timed(p, 1, 15) : inferno(p);
            case DISABLE -> s == 1 ? itemLock(p) : powerLock(p);
            case HEALTH -> s == 1 ? buff(p, PotionEffectType.REGENERATION, 300, 2) : lifeSteal(p);
            case ABSORBED -> s == 1 ? timed(p, 1, 20) : absorbRelease(p);
            case GRAVITY -> s == 1 ? slam(p) : gravityWell(p);
            case EXPLOSIVE -> s == 1 ? bomb(p) : detonate(p);
            case MESSY -> s == 1 ? root(p) : scramble(p);
            case WATER -> s == 1 ? timed(p, 1, 15) : tridentWave(p);
            case SOUL -> s == 1 ? timed(p, 1, 30) : skulls(p);
            case LIGHTNING -> s == 1 ? storm(p) : timed(p, 2, 30);
            case DARKNESS -> s == 1 ? shadowStep(p) : domain(p);
            case LIGHT -> s == 1 ? radiance(p) : vanish(p);
            case WIND -> s == 1 ? timed(p, 1, 5) : cloudWalk(p);
            case GAMBLE -> s == 1 ? curseRoll(p) : blessRoll(p);
            case ICE -> s == 1 ? timed(p, 1, 15) : icicles(p);
            case POISON -> s == 1 ? toxicCloud(p) : virulence(p);
        };
    }

    // ------------------------------------------------------------------ helpers

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("tuning." + key, def);
    }

    private boolean fail(Player p, String msg) {
        p.sendActionBar(Util.text(msg, NamedTextColor.RED));
        return false;
    }

    private boolean timed(Player p, int slot, int seconds) {
        pm.setTimer("active" + (slot + koff), p.getUniqueId(), M(seconds * 1000L));
        p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.5);
        return true;
    }

    private boolean buff(Player p, PotionEffectType type, int ticks, int amp) {
        ticks = T(ticks);
        if (pm.carries(p, Legendary.POWER_AMPLIFIER)) ticks = (int) (ticks * 1.25);
        Util.effect(p, type, ticks, amp);
        p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.5);
        return true;
    }

    private LivingEntity targetLiving(Player p, int range) {
        Entity e = p.getTargetEntity(range);
        if (e instanceof LivingEntity le && !(le instanceof ArmorStand) && le != p) return le;
        return null;
    }

    private Player targetPlayer(Player p, int range) {
        Entity e = p.getTargetEntity(range);
        if (e instanceof Player t && t != p && t.getGameMode() != GameMode.SPECTATOR) return t;
        return null;
    }

    /** Players (not allies, not creative/spectator) and hostile mobs near a point. */
    public List<LivingEntity> enemiesAt(Player owner, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        if (c.getWorld() == null) return out;
        for (Entity e : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!(e instanceof LivingEntity le) || e == owner || e instanceof ArmorStand) continue;
            if (!(le instanceof Player || le instanceof Monster)) continue;
            if (le instanceof Player pl) {
                GameMode gm = pl.getGameMode();
                if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) continue;
                if (pm.isAlly(owner, pl)) continue;
            }
            if (le.getLocation().distanceSquared(c) > r * r) continue;
            out.add(le);
        }
        return out;
    }

    private Vector away(Location from, Location to, double strength, double up) {
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() > 0.01) v.normalize().multiply(strength);
        v.setY(up);
        return v;
    }

    /** Runs a "put the world back" job later; also runs it on plugin shutdown. */
    private void restoreLater(long ticks, Runnable r) {
        Runnable once = new Runnable() {
            boolean done = false;
            @Override public void run() {
                if (done) return;
                done = true;
                cleanups.remove(this);
                r.run();
            }
        };
        cleanups.add(once);
        Bukkit.getScheduler().runTaskLater(plugin, once, ticks);
    }

    /** How long (seconds) each ability is "active". The cooldown only starts after this. */
    static int duration(Power pw, int s) {
        return switch (pw) {
            case SPEED -> s == 1 ? 0 : 5;
            case STRENGTH -> s == 1 ? 15 : 10;
            case SPIDER -> s == 1 ? 15 : 30;      // Venom Bite ends early when the hit lands
            case MINER -> s == 1 ? 15 : 10;
            case FIRE -> s == 1 ? 15 : 10;
            case DISABLE -> s == 1 ? 10 : 20;
            case HEALTH -> s == 1 ? 15 : 30;
            case ABSORBED -> s == 1 ? 20 : 5;
            case GRAVITY -> s == 1 ? 5 : 10;      // Slam ends early when the enemy lands
            case EXPLOSIVE -> s == 1 ? 6 : 0;     // Bomb ends early when it lands
            case MESSY -> s == 1 ? 10 : 0;
            case WATER -> s == 1 ? 15 : 2;
            case SOUL -> s == 1 ? 30 : 3;
            case LIGHTNING -> s == 1 ? 15 : 30;
            case DARKNESS -> s == 1 ? 0 : 30;
            case LIGHT -> s == 1 ? 15 : 20;
            case WIND -> s == 1 ? 5 : 10;
            case GAMBLE -> 20;
            case ICE -> s == 1 ? 15 : 2;
            case POISON -> s == 1 ? 15 : 30;
        };
    }

    /** Puts back whatever a cloud block replaced (unless someone built something else there). */
    private void restoreCloud(Block b) {
        BlockData orig = cloudOrig.remove(b);
        Material cur = b.getType();
        if (cur == Material.WHITE_STAINED_GLASS || cur == Material.AIR) {
            if (orig != null) b.setBlockData(orig, false);
            else if (cur == Material.WHITE_STAINED_GLASS) b.setType(Material.AIR, false);
        }
    }

    public boolean isCloud(Block b) {
        return b != null && clouds.containsKey(b);
    }

    public Dome domeAt(Block b) {
        return domainBlocks.get(b);
    }

    public boolean isInside(Dome d, Player p) {
        Location l = p.getLocation();
        return l.getWorld() != null && l.getWorld().equals(d.center().getWorld())
                && l.distanceSquared(d.center()) <= d.radius() * d.radius();
    }

    /** True only the first time a given trident wave hits a given entity. */
    public boolean firstWaveHit(String waveId, UUID victim) {
        Set<UUID> s = waveHits.get(waveId);
        return s != null && s.add(victim);
    }

    /** Called every tick by the plugin. */
    public void tick() {
        tickCounter++;
        if (tickCounter % 10 != 0 || clouds.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = clouds.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (en.getValue() <= now) {
                restoreCloud(en.getKey());
                it.remove();
            }
        }
    }

    public void shutdown() {
        for (Runnable r : new ArrayList<>(cleanups)) r.run();
        for (Block b : new ArrayList<>(clouds.keySet())) restoreCloud(b);
        clouds.clear();
        cloudOrig.clear();
    }

    // ------------------------------------------------------------------ SPEED

    private boolean doubleJump(Player p) {
        Vector v = Util.flat(p.getLocation().getDirection()).multiply(cfg("double-jump-forward", 0.45));
        v.setY(cfg("double-jump-up", 0.85));
        p.setVelocity(v);
        p.setFallDistance(0f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15, 0.3, 0.1, 0.3, 0.02);
        return true;
    }

    private boolean bigJump(Player p) {
        Util.effect(p, PotionEffectType.SPEED, T(100), 2);
        Vector v = Util.flat(p.getLocation().getDirection()).multiply(cfg("big-jump-horizontal", 4.6));
        v.setY(cfg("big-jump-vertical", 2.6));
        p.setVelocity(v);
        p.setFallDistance(0f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 40, 0.5, 0.2, 0.5, 0.1);
        return true;
    }

    // ------------------------------------------------------------------ FIRE

    private boolean inferno(Player p) {
        Location c = p.getLocation();
        for (LivingEntity e : enemiesAt(p, c, 8)) {
            if (e instanceof Player) pm.igniteByAbility(e, 160, p);
        }
        removeWater(c, 8, T(200));
        c.getWorld().spawnParticle(Particle.FLAME, c.clone().add(0, 1, 0), 150, 4, 1, 4, 0.05);
        return true;
    }

    private void removeWater(Location c, int r, long ticks) {
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        Map<Block, BlockData> saved = new HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;
                    int by = cy + y;
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(cx + x, by, cz + z);
                    Material t = b.getType();
                    BlockData d = b.getBlockData();
                    if (t == Material.WATER || t == Material.KELP || t == Material.KELP_PLANT
                            || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS || t == Material.BUBBLE_COLUMN) {
                        saved.put(b, d);
                        b.setType(Material.AIR, false);
                    } else if (d instanceof Waterlogged wl && wl.isWaterlogged()) {
                        saved.put(b, d);
                        Waterlogged copy = (Waterlogged) d.clone();
                        copy.setWaterlogged(false);
                        b.setBlockData(copy, false);
                    }
                }
            }
        }
        if (saved.isEmpty()) return;
        restoreLater(ticks, () -> saved.forEach((b, d) -> {
            Material cur = b.getType();
            if (cur == Material.AIR || cur == d.getMaterial()) b.setBlockData(d, false);
        }));
    }

    // ------------------------------------------------------------------ DISABLE

    private boolean itemLock(Player p) {
        Player t = targetPlayer(p, 30);
        if (t == null) return fail(p, "Look at a player to lock their item.");
        ItemStack hand = t.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return fail(p, t.getName() + " isn't holding anything.");
        if (pm.shielded(t)) {
            return fail(p, "The Shield protects them from being disabled!");
        }
        if (hand.getType() == Material.WIND_CHARGE && pm.ownsPower(t, Power.WIND)) {
            return fail(p, "Wind charges can't be disabled!");
        }
        pm.disableItem(t.getUniqueId(), hand.getType(), M(10_000L));
        t.setCooldown(hand.getType(), T(200));
        t.sendMessage(Component.text("Your held item has been disabled for 10 seconds!", NamedTextColor.RED));
        t.getWorld().spawnParticle(Particle.SMOKE, t.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
        return true;
    }

    private boolean powerLock(Player p) {
        Player t = targetPlayer(p, 30);
        if (t == null) return fail(p, "Look at a player to lock their power.");
        if (pm.shielded(t)) return fail(p, "The Shield protects them from being disabled!");
        pm.setTimer("disabled", t.getUniqueId(), M(20_000L));
        t.sendMessage(Component.text("Your power has been disabled for 20 seconds!", NamedTextColor.RED));
        t.getWorld().spawnParticle(Particle.SMOKE, t.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.02);
        return true;
    }

    // ------------------------------------------------------------------ HEALTH

    private boolean lifeSteal(Player p) {
        LivingEntity t = targetLiving(p, 25);
        if (t == null) return fail(p, "Look at an enemy to steal their hearts.");
        AttributeInstance vi = t.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance si = p.getAttribute(Attribute.MAX_HEALTH);
        if (vi == null || si == null) return fail(p, "You can't steal health from that.");
        double amt = Math.min(pm.hearts(Power.HEALTH, 2) * 2, Math.max(0.0, vi.getValue() - 2.0));
        if (amt <= 0) return fail(p, "That target has too little health.");
        pm.setMod(t, Attribute.MAX_HEALTH, pm.stealKey, -amt);
        pm.setMod(p, Attribute.MAX_HEALTH, pm.stealKey, amt);
        p.setHealth(Math.min(si.getValue(), p.getHealth() + amt));
        t.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 8, 0.4, 0.3, 0.4, 0);
        restoreLater(T(600), () -> {
            if (t.isValid()) pm.setMod(t, Attribute.MAX_HEALTH, pm.stealKey, 0);
            if (p.isOnline()) pm.setMod(p, Attribute.MAX_HEALTH, pm.stealKey, 0);
        });
        return true;
    }

    // ------------------------------------------------------------------ ABSORBED

    private boolean absorbRelease(Player p) {
        UUID id = p.getUniqueId();
        pm.absorbed.put(id, 0.0);
        pm.setTimer("active" + (2 + koff), id, M(5000L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Double total = pm.absorbed.remove(id);
            if (total == null || total <= 0 || !p.isOnline()) return;
            double dmg = total * 0.5; // stored damage is cut in half, then released
            Location c = p.getLocation();
            for (LivingEntity e : enemiesAt(p, c, 6)) {
                pm.dealDamage(e, dmg, p);
                e.setVelocity(away(c, e.getLocation(), 1.2, 0.5));
            }
            c.getWorld().spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 6, 1.5, 0.5, 1.5, 0);
            Util.sound(c, "entity.generic.explode", 1.2f, 1.2f);
            p.sendActionBar(Util.text(String.format("Released %.1f damage!", dmg), NamedTextColor.LIGHT_PURPLE));
        }, T(100));
        return true;
    }

    // ------------------------------------------------------------------ GRAVITY

    private boolean slam(Player p) {
        LivingEntity t = targetLiving(p, 20);
        if (t == null) return fail(p, "Look at an enemy to slam them.");
        pm.setTimer("nofall", t.getUniqueId(), 8000L);
        final int sl = 1 + koff;
        final int maxTicks = T(80);
        t.setVelocity(new Vector(0, 1.3, 0));
        t.getWorld().spawnParticle(Particle.PORTAL, t.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
        new BukkitRunnable() {
            int ticks = 0;
            boolean slammed = false;
            @Override public void run() {
                if (!t.isValid()) { pm.finishAbility(p, sl); cancel(); return; }
                ticks++;
                if (ticks == 14 && !slammed) {
                    slammed = true;
                    t.setVelocity(new Vector(0, -3.0, 0));
                }
                if (slammed && ticks > 16 && t.isOnGround()) {
                    pm.dealDamage(t, pm.hearts(Power.GRAVITY, 1) * 2, p);
                    Util.sound(t.getLocation(), "entity.generic.explode", 1f, 1.4f);
                    t.getWorld().spawnParticle(Particle.EXPLOSION, t.getLocation(), 3, 0.3, 0.1, 0.3, 0);
                    pm.finishAbility(p, sl);
                    cancel();
                    return;
                }
                if (ticks > maxTicks) {
                    pm.finishAbility(p, sl);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    private boolean gravityWell(Player p) {
        Location c = p.getLocation();
        for (LivingEntity e : enemiesAt(p, c, 8)) {
            if (e instanceof Player t && !pm.ownsPower(t, Power.WIND) && !pm.shielded(t)) {
                pm.setTimer("nojump", t.getUniqueId(), M(10_000L));
                pm.setTimer("nowind", t.getUniqueId(), M(10_000L));
                t.setCooldown(Material.WIND_CHARGE, T(200));
                t.sendMessage(Component.text("Gravity crushes you: no jumping or wind charges for 10 seconds!", NamedTextColor.RED));
            }
        }
        c.getWorld().spawnParticle(Particle.PORTAL, c.clone().add(0, 1, 0), 200, 4, 1, 4, 0.5);
        return true;
    }

    // ------------------------------------------------------------------ EXPLOSIVE

    private boolean bomb(Player p) {
        Snowball s = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().multiply(1.5));
        s.setItem(new ItemStack(Material.TNT));
        pm.tag(s, "bomb");
        return true;
    }

    /** Called by the listener when a bomb lands. */
    public void explodeBomb(Player p, Location loc) {
        pm.finishAbility(p, pm.slotFor(p, Power.EXPLOSIVE, 1));
        World w = loc.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        Util.sound(loc, "entity.generic.explode", 1.5f, 1.0f);
        for (LivingEntity le : enemiesAt(p, loc, 4)) {
            pm.dealExplosion(le, pm.hearts(Power.EXPLOSIVE, 1) * 2, p);
            le.setVelocity(away(loc, le.getLocation(), 0.8, 0.4));
        }
    }

    private boolean detonate(Player p) {
        Location c = p.getLocation();
        c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 3, 1, 0.5, 1, 0);
        Util.sound(c, "entity.generic.explode", 2f, 0.8f);
        for (LivingEntity le : enemiesAt(p, c, 6)) {
            pm.dealExplosion(le, pm.hearts(Power.EXPLOSIVE, 2) * 2, p);
            le.setVelocity(away(c, le.getLocation(), 1.6, 0.7));
        }
        return true;
    }

    // ------------------------------------------------------------------ MESSY

    private boolean root(Player p) {
        boolean self = plugin.getConfig().getBoolean("messy-ability1-targets-self", false);
        Player t = self ? p : targetPlayer(p, 30);
        if (t == null) return fail(p, "Look at a player to root them.");
        if (pm.shielded(t)) return fail(p, "The Shield protects them!");
        pm.setTimer("root", t.getUniqueId(), M(10_000L));
        t.setVelocity(new Vector(0, 0, 0));
        t.sendActionBar(Util.text("You are stuck in place!", NamedTextColor.RED));
        t.getWorld().spawnParticle(Particle.CLOUD, t.getLocation(), 20, 0.3, 0.1, 0.3, 0.02);
        return true;
    }

    private boolean scramble(Player p) {
        Player t = targetPlayer(p, 30);
        if (t == null) return fail(p, "Look at a player to scramble their hotbar.");
        if (pm.shielded(t)) return fail(p, "The Shield protects them!");
        PlayerInventory inv = t.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(inv.getItem(i));
        Collections.shuffle(items, rng);
        for (int i = 0; i < 9; i++) inv.setItem(i, items.get(i));
        t.sendMessage(Component.text("Your hotbar was scrambled!", NamedTextColor.RED));
        return true;
    }

    // ------------------------------------------------------------------ WATER

    private boolean tridentWave(Player p) {
        Vector dir = Util.flat(p.getLocation().getDirection());
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        Location origin = p.getLocation().add(0, 1.0, 0).add(dir.clone().multiply(1.5));
        final String wid = "wave:" + UUID.randomUUID();
        waveHits.put(wid, new HashSet<>());
        Bukkit.getScheduler().runTaskLater(plugin, () -> waveHits.remove(wid), 60L);
        for (int i = -4; i <= 4; i++) {
            Location at = origin.clone().add(side.clone().multiply(i * 0.9));
            Trident tr = p.getWorld().spawn(at, Trident.class);
            tr.setShooter(p);
            tr.setGravity(false);
            tr.setPierceLevel(4);
            tr.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            tr.setVelocity(dir.clone().multiply(1.8));
            pm.tag(tr, wid);
            Bukkit.getScheduler().runTaskLater(plugin, () -> tr.remove(), T(30));
        }
        p.getWorld().spawnParticle(Particle.SPLASH, origin, 80, 3, 0.5, 0.5, 0.1);
        return true;
    }

    // ------------------------------------------------------------------ SOUL

    private boolean skulls(Player p) {
        // three skulls, one after another, 0.5 s apart, each aimed where you look at that moment
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                WitherSkull s = p.launchProjectile(WitherSkull.class, p.getEyeLocation().getDirection().multiply(1.1));
                s.setYield(0f);
                pm.tag(s, "skull");
            }, i * 10L);
        }
        return true;
    }

    // ------------------------------------------------------------------ LIGHTNING

    private boolean storm(Player p) {
        Location c = p.getLocation().clone();
        final int strikes = T(30);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ >= strikes) { cancel(); return; }
                List<LivingEntity> en = enemiesAt(p, c, 10);
                Location at;
                if (!en.isEmpty() && rng.nextInt(100) < 70) {
                    at = en.get(rng.nextInt(en.size())).getLocation();
                } else {
                    at = c.clone().add((rng.nextDouble() * 2 - 1) * 10, 0, (rng.nextDouble() * 2 - 1) * 10);
                }
                c.getWorld().strikeLightningEffect(at);
                for (LivingEntity e : enemiesAt(p, at, 3)) pm.dealDamage(e, pm.hearts(Power.LIGHTNING, 1) * 2, p);
            }
        }.runTaskTimer(plugin, 0L, 10L);
        return true;
    }

    // ------------------------------------------------------------------ DARKNESS

    private boolean shadowStep(Player p) {
        LivingEntity t = targetLiving(p, 30);
        if (t == null) return fail(p, "Look at an enemy to teleport behind them.");
        Location tl = t.getLocation();
        Vector facing = Util.flat(tl.getDirection());
        Location dest = tl.clone().add(facing.clone().multiply(-1.6));
        dest.setYaw(tl.getYaw());
        dest.setPitch(0f);
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) {
            dest = tl.clone().add(facing.clone().multiply(-0.6));
            dest.setYaw(tl.getYaw());
            dest.setPitch(0f);
        }
        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        p.teleport(dest);
        dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        return true;
    }

    private boolean domain(Player p) {
        World w = p.getWorld();
        Location c = p.getLocation();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        final int R = (int) Math.round(cfg("domain-radius", 22));
        final Dome dome = new Dome(c.clone(), R);
        // The shell replaces whatever is in the way (stone, dirt, water...) and puts it all back afterwards.
        // It grows from the bottom up over about half a second.
        List<Block> shell = new ArrayList<>();
        for (int x = -R; x <= R; x++) {
            for (int y = -R; y <= R; y++) {
                for (int z = -R; z <= R; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d > R || d <= R - 1.4) continue;
                    int by = cy + y;
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                    shell.add(w.getBlockAt(cx + x, by, cz + z));
                }
            }
        }
        shell.sort(java.util.Comparator.comparingInt(Block::getY));
        final Map<Block, BlockData> saved = new HashMap<>();
        final int steps = 10;
        final int per = (shell.size() + steps - 1) / steps;
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                int from = n * per, to = Math.min(shell.size(), from + per);
                for (int k = from; k < to; k++) {
                    Block b = shell.get(k);
                    Material t = b.getType();
                    if (t == Material.SCULK) continue;
                    if (!t.isAir()) {
                        // leave unbreakable blocks and anything with an inventory/tile data alone
                        if (t.getHardness() < 0) continue;
                        if (b.getState() instanceof org.bukkit.block.TileState) continue;
                    }
                    saved.put(b, b.getBlockData());
                    b.setType(Material.SCULK, false);
                    domainBlocks.put(b, dome);
                }
                if (++n >= steps || to >= shell.size()) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
        restoreLater(T(600), () -> {
            for (Map.Entry<Block, BlockData> en : saved.entrySet()) {
                Block b = en.getKey();
                Material cur = b.getType();
                // put the original back unless somebody built something else there
                if (cur == Material.SCULK || cur == Material.AIR) b.setBlockData(en.getValue(), false);
                domainBlocks.remove(b);
            }
        });
        Location center = c.clone();
        final int pulses = T(30);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (n++ >= pulses || !p.isOnline()) { cancel(); return; }
                for (LivingEntity e : enemiesAt(p, center, R - 0.5)) {
                    Util.effect(e, PotionEffectType.SLOWNESS, 60, 1);
                    if (e instanceof Player) Util.effect(e, PotionEffectType.DARKNESS, 100, 0);
                }
                w.spawnParticle(Particle.SCULK_SOUL, center.clone().add(0, 1, 0), 40, R / 2.0, R / 4.0, R / 2.0, 0.02);
            }
        }.runTaskTimer(plugin, 0L, 20L);
        return true;
    }

    // ------------------------------------------------------------------ LIGHT

    private boolean radiance(Player p) {
        List<LivingEntity> en = enemiesAt(p, p.getLocation(), 20);
        if (en.isEmpty()) return fail(p, "No enemies nearby.");
        for (LivingEntity e : en) {
            // outline only the Light player can see (not a real glow effect, so nobody else sees it)
            final long until = System.currentTimeMillis() + T(300) * 50L;
            pm.showOutline(p, e, () -> System.currentTimeMillis() < until);
            // health shows above the enemy's head (only you can see it), not in chat
            pm.showAbove(p, e, () -> Component.text(Util.healthLine(e), NamedTextColor.YELLOW), T(300));
        }
        return true;
    }

    private boolean vanish(Player p) {
        UUID id = p.getUniqueId();
        Util.effect(p, PotionEffectType.INVISIBILITY, T(400), 0);
        pm.hidden.add(id);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != p) o.hidePlayer(plugin, p);
        }
        restoreLater(T(400), () -> {
            pm.hidden.remove(id);
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(plugin, p);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        });
        return true;
    }

    // ------------------------------------------------------------------ WIND

    private boolean cloudWalk(Player p) {
        pm.setTimer("active" + (2 + koff), p.getUniqueId(), M(10_000L));
        final int steps = T(100);
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline() || n++ >= steps) { cancel(); return; }
                Location l = p.getLocation();
                World w = l.getWorld();
                int by = l.getBlockY() - 1;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx * dx + dz * dz > 5) continue;
                        Block b = w.getBlockAt(l.getBlockX() + dx, by, l.getBlockZ() + dz);
                        if (clouds.containsKey(b)) {
                            clouds.put(b, System.currentTimeMillis() + 3500L);
                            continue;
                        }
                        // replaces whatever is there (ground, water...) for a moment, then puts it back
                        Material t = b.getType();
                        if (!t.isAir()) {
                            if (t.getHardness() < 0) continue;
                            if (b.getState() instanceof org.bukkit.block.TileState) continue;
                        }
                        cloudOrig.put(b, b.getBlockData());
                        b.setType(Material.WHITE_STAINED_GLASS, false);
                        clouds.put(b, System.currentTimeMillis() + 3500L);
                    }
                }
                w.spawnParticle(Particle.CLOUD, l, 6, 1, 0.1, 1, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    // ------------------------------------------------------------------ GAMBLE

    private boolean curseRoll(Player p) {
        LivingEntity t = targetLiving(p, 25);
        if (t == null) return fail(p, "Look at an enemy to curse them.");
        PotionEffectType type = NEGATIVE[rng.nextInt(NEGATIVE.length)];
        Util.effect(t, type, T(400), 1);
        p.sendActionBar(Util.text("Rolled: " + type.getKey().getKey().replace('_', ' ') + " II", NamedTextColor.GOLD));
        return true;
    }

    private boolean blessRoll(Player p) {
        PotionEffectType type = POSITIVE[rng.nextInt(POSITIVE.length)];
        Util.effect(p, type, T(400), 1);
        p.sendActionBar(Util.text("Rolled: " + type.getKey().getKey().replace('_', ' ') + " II", NamedTextColor.GOLD));
        return true;
    }

    // ------------------------------------------------------------------ ICE

    private boolean icicles(Player p) {
        // three icicles, one after another, 0.5 s apart, each aimed where you look at that moment
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                Snowball s = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().multiply(2.0));
                s.setItem(new ItemStack(Material.PACKED_ICE));
                s.setGravity(false);
                pm.tag(s, "icicle");
                Bukkit.getScheduler().runTaskLater(plugin, () -> s.remove(), T(40));
            }, i * 10L);
        }
        return true;
    }

    // ------------------------------------------------------------------ POISON

    private boolean toxicCloud(Player p) {
        Location c = p.getLocation();
        for (LivingEntity e : enemiesAt(p, c, 10)) Util.effect(e, PotionEffectType.POISON, T(300), 0);
        for (Player o : c.getWorld().getNearbyPlayers(c, 10)) {
            if (o != p && pm.isAlly(p, o)) o.removePotionEffect(PotionEffectType.POISON);
        }
        c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 1, 0), 120, 4, 1, 4, 0.05);
        return true;
    }

    private boolean virulence(Player p) {
        int boosted = 0;
        for (LivingEntity e : enemiesAt(p, p.getLocation(), 12)) {
            for (PotionEffect ef : new ArrayList<>(e.getActivePotionEffects())) {
                if (!Util.HARMFUL.contains(ef.getType())) continue;
                e.addPotionEffect(new PotionEffect(ef.getType(), T(600), Math.min(ef.getAmplifier() + 1, 9), false, true, true));
                boosted++;
            }
        }
        if (boosted == 0) return fail(p, "No debuffs nearby to boost.");
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 60, 3, 1, 3, 0.05);
        return true;
    }
}
