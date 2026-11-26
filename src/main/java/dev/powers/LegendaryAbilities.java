package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;

/** The abilities of the legendary items (F = ability 1, Shift + F = ability 2 while holding one). */
public final class LegendaryAbilities {
    private final PowersPlugin plugin;
    private final PowerManager pm;
    private final Abilities ab;

    public LegendaryAbilities(PowersPlugin plugin, PowerManager pm, Abilities ab) {
        this.plugin = plugin;
        this.pm = pm;
        this.ab = ab;
    }

    /** Bar/cooldown slots 5-12: two per legendary that has abilities. */
    private static int slotOf(Legendary l, int n) {
        int idx = switch (l) {
            case MACE -> 0;
            case END_SWORD -> 1;
            case NETHER_AXE -> 2;
            default -> 3; // SPEAR
        };
        return 5 + idx * 2 + (n - 1);
    }

    private boolean fail(Player p, String msg) {
        p.sendActionBar(Util.text(msg, NamedTextColor.RED));
        return false;
    }

    public void use(Player p, int n) {
        pm.refreshCarried(p);
        Legendary l = Legendary.of(p.getInventory().getItemInMainHand());
        if (l == null) {
            fail(p, "Hold a legendary item to use its abilities.");
            return;
        }
        Ability a = l.ability(n);
        if (a == null) {
            fail(p, l.displayName() + " has no ability " + n + ".");
            return;
        }
        int slot = slotOf(l, n); // every legendary ability has its own bar/cooldown
        if (pm.isRunning(p, slot)) {
            fail(p, a.name() + " is still active!");
            return;
        }
        long rem = pm.remainingMs(p, slot);
        if (rem > 0) {
            fail(p, String.format("%s is on cooldown: %.1fs", a.name(), rem / 1000.0));
            return;
        }
        boolean ok = switch (l) {
            case MACE -> n == 1 ? grapple(p) : chainToss(p);
            case END_SWORD -> n == 1 ? voidStep(p) : dragonFire(p);
            case NETHER_AXE -> n == 1 ? blazeVolley(p) : infernoRage(p);
            case SPEAR -> n == 1 ? miniDash(p) : speedBurst(p);
            default -> false;
        };
        if (!ok) return;
        Util.sound(p.getLocation(), a.sound(), 1.2f, 1.0f);
        long dur = PowerManager.legendaryDefaultLength(l, n) > 0
                ? Math.round(pm.legendaryLengthSeconds(l, n) * 1000 * boost(p)) : 0L;
        pm.beginAbility(p, "★ " + a.name(), slot, dur, pm.legendaryCooldownMs(p, l, n));
        p.sendActionBar(Util.text("★ " + a.name() + "!", NamedTextColor.GOLD));
    }

    // ------------------------------------------------------------------ helpers

    private void chainLine(Location from, Location to) {
        World w = from.getWorld();
        if (w == null) return;
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.normalize();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(150, 150, 160), 1.2f);
        for (double i = 0; i < len; i += 0.4) {
            w.spawnParticle(Particle.DUST, from.clone().add(d.clone().multiply(i)), 1, 0, 0, 0, 0, dust);
        }
    }

    // ------------------------------------------------------------------ MACE

    private double boost(Player p) {
        return pm.carries(p, Legendary.POWER_AMPLIFIER) ? 1.2 : 1.0;
    }

    /** Ability 1: hit a block and the chain pulls YOU there; hit a player/mob and it pulls THEM to you. */
    private boolean grapple(Player p) {
        double range = 20 * boost(p);
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        RayTraceResult r = w.rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.4, en ->
                en != p && en instanceof LivingEntity le && !(le instanceof ArmorStand)
                        && !(le instanceof Player pl && (pl.getGameMode() == GameMode.SPECTATOR || pm.isAlly(p, pl))));
        if (r == null) return fail(p, "Nothing in range");
        Location from = eye.clone().subtract(0, 0.3, 0);
        if (r.getHitEntity() instanceof LivingEntity victim) {
            Vector pull = p.getLocation().toVector().subtract(victim.getLocation().toVector());
            double dist = pull.length();
            if (dist < 2.0) return fail(p, "Too close to grapple.");
            pull.normalize().multiply(Math.min(3.0, 0.6 + dist * 0.13));
            pull.setY(pull.getY() + 0.3);
            victim.setVelocity(pull);
            pm.setTimer("nofall", victim.getUniqueId(), 4000L);
            chainLine(from, victim.getLocation().add(0, 1, 0));
            if (victim instanceof Player vp) vp.sendActionBar(Util.text("You were yanked by " + p.getName() + "'s chain!", NamedTextColor.RED));
            return true;
        }
        Vector hit = r.getHitPosition();
        Vector pull = hit.clone().subtract(p.getLocation().toVector().add(new Vector(0, 1, 0)));
        double dist = pull.length();
        if (dist < 1.5) return fail(p, "Too close to grapple.");
        pull.normalize().multiply(Math.min(3.2, 0.6 + dist * 0.14));
        pull.setY(pull.getY() + 0.25);
        p.setVelocity(pull);
        p.setFallDistance(0f);
        pm.setTimer("nofall", p.getUniqueId(), 5000L);
        chainLine(from, hit.toLocation(w));
        return true;
    }

    /** Ability 2: throw the chain up to 10 blocks (blocks or open air) and launch yourself to where it lands. */
    private boolean chainToss(Player p) {
        double range = 10 * boost(p);
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        Vector dir = eye.getDirection();
        RayTraceResult r = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        Vector spotV = r != null ? r.getHitPosition() : eye.toVector().add(dir.clone().multiply(range));
        Location spot = spotV.toLocation(w);
        Vector launch = spotV.clone().subtract(p.getLocation().toVector().add(new Vector(0, 1, 0)));
        double dist = launch.length();
        if (dist < 1.5) return fail(p, "Too close to toss.");
        launch.normalize().multiply(Math.min(2.6, 0.5 + dist * 0.17));
        launch.setY(launch.getY() + 0.3);
        p.setVelocity(launch);
        p.setFallDistance(0f);
        pm.setTimer("nofall", p.getUniqueId(), 5000L);
        chainLine(eye.clone().subtract(0, 0.3, 0), spot);
        return true;
    }

    // ------------------------------------------------------------------ SPEAR

    private boolean miniDash(Player p) {
        Vector v = Util.flat(p.getLocation().getDirection());
        if (v.lengthSquared() < 0.01) v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() < 0.01) return fail(p, "Look forward to dash.");
        v.normalize().multiply(1.2).setY(0.12);
        p.setVelocity(v);
        p.setFallDistance(0f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.2, 0), 15, 0.3, 0.1, 0.3, 0.02);
        return true;
    }

    private boolean speedBurst(Player p) {
        double secs = pm.legendaryLengthSeconds(Legendary.SPEAR, 2) * boost(p);
        Util.effect(p, PotionEffectType.SPEED, (int) (secs * 20), 4); // Speed V
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.05);
        return true;
    }

    // ------------------------------------------------------------------ END SWORD

    private boolean voidStep(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double max = 15 * boost(p);
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, max, FluidCollisionMode.NEVER, true);
        double dist = r != null ? Math.max(0, r.getHitPosition().distance(eye.toVector()) - 0.8) : max;
        Location dest = null;
        for (double d = dist; d >= 0; d -= 0.5) {
            Location t = eye.clone().add(dir.clone().multiply(d)).subtract(0, 1.62, 0);
            if (t.getBlock().isPassable() && t.clone().add(0, 1, 0).getBlock().isPassable()) {
                dest = t;
                break;
            }
        }
        if (dest == null) return fail(p, "No room to teleport.");
        Location from = p.getLocation();
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        p.teleport(dest);
        p.setFallDistance(0f);
        dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        return true;
    }

    private boolean dragonFire(Player p) {
        DragonFireball f = p.launchProjectile(DragonFireball.class, p.getEyeLocation().getDirection());
        pm.tag(f, "dragonfire");
        return true;
    }

    // ------------------------------------------------------------------ NETHER BATTLE AXE

    private boolean blazeVolley(Player p) {
        Vector dir = p.getEyeLocation().getDirection();
        for (int i = -1; i <= 1; i++) {
            Vector d = dir.clone().rotateAroundY(Math.toRadians(i * 8.0)).multiply(1.2);
            SmallFireball f = p.launchProjectile(SmallFireball.class, d);
            f.setIsIncendiary(false);
            pm.tag(f, "axefire");
        }
        return true;
    }

    private boolean infernoRage(Player p) {
        double secs = pm.legendaryLengthSeconds(Legendary.NETHER_AXE, 2) * boost(p);
        pm.setTimer("axeboost", p.getUniqueId(), Math.round(secs * 1000));
        Util.effect(p, PotionEffectType.FIRE_RESISTANCE, (int) (secs * 20), 0);
        p.setFireTicks((int) (secs * 20));
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.05);
        return true;
    }
}
