package dev.powers;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class PowerListener implements Listener {
    private final PowersPlugin plugin;
    private final PowerManager pm;
    private final Abilities ab;
    private final Random rng = new Random();

    public PowerListener(PowersPlugin plugin, PowerManager pm, Abilities ab) {
        this.plugin = plugin;
        this.pm = pm;
        this.ab = ab;
    }

    // ------------------------------------------------------------------ join / quit / death

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pm.ensurePower(p);
        p.discoverRecipe(RerollBook.KEY);
        for (UUID id : pm.hidden) {
            Player h = Bukkit.getPlayer(id);
            if (h != null && h != p) p.hidePlayer(plugin, h);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pm.onQuit(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        pm.onDeath(e.getEntity());
    }

    // ------------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (pm.internalDamage) return;
        if (e instanceof EntityDamageByEntityEvent be) {
            String t = pm.tagOf(be.getDamager());
            if (t != null && (t.startsWith("wave:") || t.equals("skull") || t.equals("axefire"))) {
                handleTagged(be, t);
                return;
            }
        }
        Entity ent = e.getEntity();
        DamageCause cause = e.getCause();
        UUID eid = ent.getUniqueId();

        if (cause == DamageCause.FALL && pm.has("nofall", eid)) {
            e.setCancelled(true);
            return;
        }

        Player victim = ent instanceof Player pl ? pl : null;
        Power vp = victim == null ? null : pm.power(victim);
        boolean explosion = cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION;

        if (victim != null && pm.shielded(victim)) {
            // Legendary Shield: explosions and debuff damage don't affect you
            if (explosion || cause == DamageCause.WITHER || cause == DamageCause.POISON || cause == DamageCause.FREEZE
                    || cause == DamageCause.MAGIC) {
                e.setCancelled(true);
                return;
            }
        }

        if (victim != null && pm.powers(victim).length > 0) {
            if (cause == DamageCause.FALL && pm.hasPower(victim, Power.SPEED)) { e.setCancelled(true); return; }
            if (pm.hasPower(victim, Power.EXPLOSIVE) && explosion) {
                e.setCancelled(true);
                return;
            }
            if (pm.hasPower(victim, Power.ICE) && cause == DamageCause.FREEZE) { e.setCancelled(true); return; }
            if (pm.hasPower(victim, Power.SOUL) && cause == DamageCause.WITHER) { e.setCancelled(true); return; }
            if (pm.hasPower(victim, Power.POISON) && cause == DamageCause.POISON) { e.setCancelled(true); return; }
            if (pm.act(victim, Power.ABSORBED, 2)) {
                pm.absorbed.merge(eid, e.getFinalDamage(), Double::sum);
                e.setCancelled(true);
                pm.absorbCue(victim);
                return;
            }
        }

        if (e instanceof EntityDamageByEntityEvent ee) handleHit(ee, victim, vp);
    }

    private void handleHit(EntityDamageByEntityEvent e, Player victim, Power vp) {
        Entity damager = e.getDamager();
        Entity ent = e.getEntity();
        Player attacker = null;
        Projectile proj = null;
        boolean melee = damager instanceof Player;
        if (damager instanceof Player pl) {
            attacker = pl;
        } else if (damager instanceof Projectile pr) {
            proj = pr;
            if (pr.getShooter() instanceof Player pl) attacker = pl;
        }

        // Item Lock: a disabled item can't be used to attack
        if (melee && attacker != null && pm.isItemDisabled(attacker, attacker.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            attacker.sendActionBar(Util.text("That item is disabled!", NamedTextColor.RED));
            return;
        }

        // Spider: Evasion
        if (victim != null && attacker != null && attacker != victim
                && pm.act(victim, Power.SPIDER, 1) && rng.nextDouble() < pm.chance(victim, 0.25)) {
            e.setCancelled(true);
            Util.sound(victim.getLocation(), "entity.spider.step", 1f, 1.5f);
            victim.sendActionBar(Util.text("Dodged!", NamedTextColor.DARK_RED));
            return;
        }

        if (attacker != null && attacker != ent && ent instanceof LivingEntity target) {
            for (Power ap : pm.powers(attacker)) attackerEffects(e, attacker, ap, target, melee, proj);
            // Nether Battle Axe: Inferno Rage makes melee hits stronger while you are on fire
            if (melee && pm.has("axeboost", attacker.getUniqueId()) && attacker.getFireTicks() > 0) {
                e.setDamage(e.getDamage() * 1.5);
            }
        }

        if (victim != null && pm.hasPower(victim, Power.ABSORBED)) reflect(e, victim, damager, proj);
    }

    /** Trident waves and wither skulls: cancel the vanilla hit and deal true damage instead. */
    private void handleTagged(EntityDamageByEntityEvent e, String tag) {
        e.setCancelled(true);
        if (!(e.getEntity() instanceof LivingEntity le) || le instanceof ArmorStand) return;
        if (!(e.getDamager() instanceof Projectile pr) || !(pr.getShooter() instanceof Player shooter)) return;
        if (le == shooter || (le instanceof Player lp && pm.isAlly(shooter, lp))) return;
        if (tag.equals("axefire")) {
            pm.dealDamage(le, pm.legendaryHearts(Legendary.NETHER_AXE, 1) * 2, shooter);
            pm.igniteByAbility(le, 100, shooter);
            return;
        }
        if (tag.equals("skull")) {
            pm.dealDamage(le, pm.hearts(Power.SOUL, 2) * 2, shooter);
            Util.effect(le, PotionEffectType.WITHER, 100, 0);
            return;
        }
        if (!ab.firstWaveHit(tag, le.getUniqueId())) return;
        if (plugin.getConfig().isSet("damage.WATER.2")) {
            pm.dealDamage(le, pm.hearts(Power.WATER, 2) * 2, shooter);
        } else {
            pm.dealDamage(le, 6.0 + 2.0 * rng.nextInt(3), shooter); // 3, 4 or 5 hearts
        }
        Vector dir = pr.getVelocity().clone().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = le.getLocation().toVector().subtract(pr.getLocation().toVector()).setY(0);
        }
        if (dir.lengthSquared() > 1.0E-4) dir.normalize().multiply(1.4);
        dir.setY(0.35);
        final Vector push = dir;
        Bukkit.getScheduler().runTask(plugin, () -> le.setVelocity(push));
    }

    private void attackerEffects(EntityDamageByEntityEvent e, Player atk, Power ap, LivingEntity target,
                                 boolean melee, Projectile proj) {
        AbstractArrow arrow = proj instanceof AbstractArrow aa ? aa : null;
        boolean xbow = arrow != null && arrow.isShotFromCrossbow();
        UUID aid = atk.getUniqueId();
        switch (ap) {
            case STRENGTH -> {
                if (melee && !isNaturalCrit(atk)) {
                    boolean force = pm.act(atk, Power.STRENGTH, 1);
                    if (force || rng.nextDouble() < pm.chance(atk, 0.10)) {
                        e.setDamage(e.getDamage() * 1.5);
                        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.1);
                        Util.sound(target.getLocation(), "entity.player.attack.crit", 1f, 1f);
                    }
                }
            }
            case SPIDER -> {
                if (melee && pm.act(atk, Power.SPIDER, 2)) {
                    int vs = pm.slotFor(atk, Power.SPIDER, 2);
                    pm.clearTimer("active" + vs, aid);
                    pm.finishAbility(atk, vs);
                    Util.effect(target, PotionEffectType.SLOWNESS, 300, 1);
                    atk.sendActionBar(Util.text("Venom applied!", NamedTextColor.DARK_RED));
                }
            }
            case FIRE -> {
                if (xbow) pm.igniteByAbility(target, 100, atk);
            }
            case DISABLE -> {
                if (target instanceof Player t) disablePassives(atk, t);
            }
            case HEALTH -> {
                if (melee) {
                    if (pm.throttle(aid, 4000)) {
                        // shown above the enemy's head (only the attacker sees it)
                        Bukkit.getScheduler().runTask(plugin, () ->
                                pm.showAbove(atk, target, () -> net.kyori.adventure.text.Component.text(
                                        Util.healthLine(target), NamedTextColor.RED), 60));
                    }
                    if (rng.nextDouble() < pm.chance(atk, 0.05)) Util.effect(atk, PotionEffectType.REGENERATION, 60, 1);
                }
            }
            case GRAVITY -> {
                if (melee) gravityPassives(e, atk, target);
            }
            case EXPLOSIVE -> {
                if (proj instanceof Firework) e.setDamage(e.getDamage() * 1.75);
            }
            case MESSY -> {
                if (melee && target instanceof Player t) messyPassives(atk, t);
            }
            case SOUL -> {
                if (pm.act(atk, Power.SOUL, 1)) Util.effect(target, PotionEffectType.WITHER, 100, 0);
            }
            case LIGHTNING -> {
                if (proj instanceof Trident) {
                    strike(target);
                    e.setDamage(e.getDamage() + 2.0);
                } else if (xbow) {
                    strike(target);
                    e.setDamage(e.getDamage() + 1.0);
                }
                if (pm.act(atk, Power.LIGHTNING, 2) && rng.nextDouble() < pm.chance(atk, 0.25)) {
                    strike(target);
                    final double thunder = pm.hearts(Power.LIGHTNING, 2) * 2;
                    Bukkit.getScheduler().runTask(plugin, () -> pm.dealDamage(target, thunder, atk));
                }
            }
            case DARKNESS -> {
                if (xbow) {
                    Util.effect(target, PotionEffectType.BLINDNESS, 100, 0);
                    Util.effect(target, PotionEffectType.DARKNESS, 100, 0);
                }
            }
            case LIGHT -> {
                boolean day = atk.getWorld().getTime() < 12300;
                e.setDamage(e.getDamage() * (day ? 1.25 : 1.1));
                if (xbow) Util.effect(target, PotionEffectType.GLOWING, 200, 0);
            }
            case GAMBLE -> {
                if (rng.nextDouble() < pm.chance(atk, 0.05)) {
                    e.setDamage(e.getDamage() + 2.0);
                    Util.sound(target.getLocation(), "entity.experience_orb.pickup", 1f, 1.6f);
                }
            }
            case ICE -> {
                if (pm.act(atk, Power.ICE, 1)) pm.freeze(target, 5000L);
            }
            case POISON -> {
                if (xbow) Util.effect(target, PotionEffectType.POISON, 160, 0);
            }
            default -> { }
        }
    }

    private boolean isNaturalCrit(Player a) {
        return a.getFallDistance() > 0 && !a.isOnGround() && !a.isClimbing() && !a.isInWater()
                && !a.hasPotionEffect(PotionEffectType.BLINDNESS) && !a.isInsideVehicle() && !a.isSprinting();
    }

    private void strike(LivingEntity target) {
        target.getWorld().strikeLightningEffect(target.getLocation());
    }

    private void disablePassives(Player atk, Player t) {
        if (pm.shielded(t)) return;
        if (t.isBlocking() && rng.nextDouble() < pm.chance(atk, 0.10)) {
            t.setCooldown(Material.SHIELD, 100);
            t.clearActiveItem();
            Util.sound(t.getLocation(), "item.shield.break", 1f, 1f);
            t.sendActionBar(Util.text("Your shield was disabled!", NamedTextColor.RED));
        }
        if (rng.nextDouble() < pm.chance(atk, 0.05) && !pm.ownsPower(t, Power.WIND) && !pm.has("jinx", t.getUniqueId())) {
            pm.setTimer("jinx", t.getUniqueId(), 600_000L);
        }
    }

    private void gravityPassives(EntityDamageByEntityEvent e, Player atk, LivingEntity target) {
        ItemStack hand = atk.getInventory().getItemInMainHand();
        if (Tag.ITEMS_AXES.isTagged(hand.getType())) {
            double fd = atk.getFallDistance();
            if (fd > 1.5 && !atk.isOnGround()) {
                double bonus = 0;
                double d = fd;
                double t1 = Math.min(d, 3);
                bonus += t1 * 4;
                d -= t1;
                double t2 = Math.min(d, 5);
                bonus += t2 * 2;
                d -= t2;
                if (d > 0) bonus += d;
                e.setDamage(e.getDamage() + bonus);
                atk.setFallDistance(0f);
                Util.sound(target.getLocation(), "item.mace.smash_ground", 1f, 1f);
                target.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation(), 1);
            }
        }
        if (rng.nextDouble() < pm.chance(atk, 0.05)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Vector pull = atk.getLocation().toVector().subtract(target.getLocation().toVector());
                if (pull.lengthSquared() > 0.01) target.setVelocity(pull.normalize().multiply(1.1).setY(0.25));
            });
        }
    }

    private void messyPassives(Player atk, Player t) {
        if (pm.shielded(t)) return;
        if (rng.nextDouble() < pm.chance(atk, 0.01)) {
            PlayerInventory inv = t.getInventory();
            ItemStack main = inv.getItemInMainHand();
            ItemStack off = inv.getItemInOffHand();
            inv.setItemInMainHand(off);
            inv.setItemInOffHand(main);
            t.sendActionBar(Util.text("Your item was shoved into your offhand!", NamedTextColor.YELLOW));
        }
        if (rng.nextDouble() < pm.chance(atk, 0.05)) {
            pm.setTimer("root", t.getUniqueId(), 500L);
            Bukkit.getScheduler().runTask(plugin, () -> t.setVelocity(new Vector(0, 0, 0)));
        }
    }

    private void reflect(EntityDamageByEntityEvent e, Player victim, Entity damager, Projectile proj) {
        LivingEntity src = null;
        if (damager instanceof LivingEntity l) src = l;
        else if (proj != null && proj.getShooter() instanceof LivingEntity l2) src = l2;
        if (src == null || src == victim) return;
        double fd = e.getFinalDamage();
        if (fd <= 0) return;
        double portion = 0;
        if (pm.act(victim, Power.ABSORBED, 1)) {
            portion = 0.5; // ability 1: half of the damage goes back to the attacker
            pm.absorbCue(victim);
        } else if (rng.nextDouble() < pm.chance(victim, 0.10)) {
            portion = 0.25; // passive: 10% chance, a quarter of the damage goes back
        }
        if (portion > 0) pm.dealDamage(src, fd * portion, victim);
    }

    // ------------------------------------------------------------------ potion immunities

    @EventHandler(ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getNewEffect() == null) return;
        PotionEffectType t = e.getNewEffect().getType();
        if (pm.shielded(p) && Util.HARMFUL.contains(t)) {
            e.setCancelled(true);
            return;
        }
        if ((pm.hasPower(p, Power.DARKNESS) && (t == PotionEffectType.BLINDNESS || t == PotionEffectType.DARKNESS))
                || (pm.hasPower(p, Power.POISON) && t == PotionEffectType.POISON)
                || (pm.hasPower(p, Power.SOUL) && t == PotionEffectType.WITHER)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ projectiles

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack bow = e.getBow();
        if (bow == null || bow.getType() != Material.CROSSBOW) return;
        if (pm.hasPower(p, Power.FIRE)) e.getProjectile().setFireTicks(200);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr.getShooter() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        boolean ownsWind = pm.ownsPower(p, Power.WIND);
        boolean wind = pr instanceof WindCharge;

        if (wind) {
            if (!ownsWind && pm.has("nowind", id)) {
                e.setCancelled(true);
                p.sendActionBar(Util.text("You can't use wind charges right now!", NamedTextColor.RED));
                return;
            }
            if (!ownsWind && pm.has("jinx", id)) {
                pm.clearTimer("jinx", id);
                Bukkit.getScheduler().runTask(plugin, () -> pr.remove());
                p.sendActionBar(Util.text("Your wind charge fizzled!", NamedTextColor.RED));
                return;
            }
            if (pm.act(p, Power.WIND, 1)) {
                Bukkit.getScheduler().runTask(plugin, () -> p.setCooldown(Material.WIND_CHARGE, 0));
            }
        }
        if (pm.hasPower(p, Power.GAMBLE) && rng.nextDouble() < pm.chance(p, 0.05)) {
            Material m = null;
            if (pr instanceof EnderPearl) m = Material.ENDER_PEARL;
            else if (pr instanceof ThrownExpBottle) m = Material.EXPERIENCE_BOTTLE;
            else if (wind) m = Material.WIND_CHARGE;
            if (m != null) refund(p, m);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        String tag = pm.tagOf(pr);
        if (tag == null) {
            if (pr instanceof WindCharge && pr.getShooter() instanceof Player ws && pm.ownsPower(ws, Power.WIND)) {
                boostWind(pr.getLocation());
            }
            return;
        }
        if (!(pr.getShooter() instanceof Player p)) return;
        switch (tag) {
            case "bomb" -> {
                ab.explodeBomb(p, pr.getLocation());
                pr.remove();
            }
            case "icicle" -> {
                if (e.getHitEntity() instanceof LivingEntity le && le != p && !(le instanceof ArmorStand)) {
                    pm.dealDamage(le, pm.hearts(Power.ICE, 2) * 2, p);
                    pm.freeze(le, 4000L);
                }
                pr.remove();
            }
            default -> { }
        }
    }

    @EventHandler
    public void onExplosionPrime(ExplosionPrimeEvent e) {
        if ("skull".equals(pm.tagOf(e.getEntity()))) e.setCancelled(true);
    }

    /** Wind power: stronger wind charges. Runs a tick later and sets the final velocity itself. */
    private void boostWind(Location loc) {
        double mult = plugin.getConfig().getDouble("tuning.wind-boost", 1.7);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (loc.getWorld() == null) return;
            for (Entity en : loc.getWorld().getNearbyEntities(loc, 3.5, 3.5, 3.5)) {
                if (!(en instanceof LivingEntity le) || le instanceof ArmorStand) continue;
                Vector away = le.getLocation().add(0, 0.9, 0).toVector().subtract(loc.toVector());
                double len = away.length();
                if (len > 3.5) continue;
                double fall = 1.0 - len / 3.5;
                if (len < 0.05) away = new Vector(0, 1, 0);
                else away.normalize();
                Vector v = away.multiply(0.75 * mult * fall);
                v.setY(v.getY() + 0.35 * mult * fall);
                le.setVelocity(v);
            }
        });
    }

    // ------------------------------------------------------------------ Gamble refunds

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (pm.isItemDisabled(p, e.getItem())) {
            e.setCancelled(true);
            return;
        }
        Material m = e.getItem().getType();
        if (pm.hasPower(p, Power.GAMBLE) && (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE)
                && rng.nextDouble() < pm.chance(p, 0.05)) {
            refund(p, m);
        }
    }

    private void refund(Player p, Material m) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m));
            for (ItemStack i : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), i);
            p.sendActionBar(Util.text("Lucky! That item wasn't used up.", NamedTextColor.GOLD));
        });
    }

    // ------------------------------------------------------------------ Disable / Gravity / Messy movement control

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (pm.isItemDisabled(e.getPlayer(), e.getItem())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Util.text("That item is disabled!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (pm.isItemDisabled(p, p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        Block b = e.getBlock();
        Abilities.Dome dome = ab.domeAt(b);
        if (dome != null) {
            if (ab.isInside(dome, p)) {
                e.setCancelled(true);
                p.sendActionBar(Util.text("The sculk dome can only be broken from outside!", NamedTextColor.RED));
                return;
            }
            ab.domainBlocks.remove(b);
            e.setDropItems(false);
            e.setExpToDrop(0);
        }
    }

    private static final org.bukkit.block.BlockFace[] FACES = {
            org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN, org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};

    /** On, inside or within one block (including diagonals) of a cloud block. */
    private boolean touchesCloud(Block b) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (ab.isCloud(b.getRelative(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /** Standing on (or just above) a cloud. */
    private boolean onCloud(Player p) {
        Block feet = p.getLocation().getBlock();
        return touchesCloud(feet) || touchesCloud(feet.getRelative(0, -1, 0));
    }

    // Nothing can be placed on or next to Wind's clouds (checked on the click AND on the placement, so spam clicking can't slip through)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCloudClick(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = e.getClickedBlock();
        ItemStack it = e.getItem();
        if (clicked == null || it == null) return;
        if (!touchesCloud(clicked) && !onCloud(e.getPlayer())) return;
        if (it.getType().isBlock() || it.getType().name().endsWith("_BUCKET")) {
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (touchesCloud(e.getBlockPlaced()) || touchesCloud(e.getBlockAgainst()) || onCloud(e.getPlayer())) {
            e.setCancelled(true);
            e.setBuild(false);
            e.getPlayer().sendActionBar(Util.text("You can't build on clouds!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (touchesCloud(e.getBlockClicked()) || touchesCloud(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (pm.has("nojump", id) || pm.has("root", id)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!pm.has("root", p.getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location n = from.clone();
            n.setYaw(to.getYaw());
            n.setPitch(to.getPitch());
            if (to.getY() < from.getY()) n.setY(to.getY());
            e.setTo(n);
        }
    }

    // ------------------------------------------------------------------ optional hotkeys

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!plugin.getConfig().getBoolean("hotkeys.enabled", false)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ab.use(p, p.isSneaking() ? 2 : 1);
    }

    // ------------------------------------------------------------------ menu

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PowerMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (menu.page() == 1 && slot == PowerMenu.NEXT_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new PowerMenu(pm, p, 2).getInventory()));
            return;
        }
        if (menu.page() == 2 && slot == PowerMenu.PREV_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new PowerMenu(pm, p, 1).getInventory()));
            return;
        }
        if (!p.hasPermission("powers.menu")) return;
        if (menu.page() == 1 && slot == PowerMenu.REROLL_SLOT) {
            p.getInventory().addItem(RerollBook.create()).values()
                    .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            p.sendMessage(Util.text("You received a Reroll Book!", NamedTextColor.LIGHT_PURPLE));
            return;
        }
        if (menu.page() == 2) {
            Legendary l = menu.legendaryAt(slot);
            if (l == null) return;
            p.getInventory().addItem(l.createItem()).values()
                    .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            pm.refreshCarried(p);
            p.sendMessage(Util.text("You received the " + l.displayName() + " legendary!", l.color()));
            Util.sound(p.getLocation(), "entity.player.levelup", 1f, 1.2f);
            return;
        }
        Power pw = menu.at(slot);
        if (pw == null) return;
        if (e.getClick().isRightClick()) {
            if (!pm.carries(p, Legendary.DOUBLE_POWER)) {
                p.sendMessage(Util.text("You need the Double Power legendary to hold a second power.", NamedTextColor.RED));
                return;
            }
            if (!pm.setSecond(p, pw)) {
                p.sendMessage(Util.text("That is already your main power.", NamedTextColor.RED));
                return;
            }
        } else {
            pm.setPower(p, pw);
        }
        Util.sound(p.getLocation(), "entity.experience_orb.pickup", 1f, 1.2f);
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PowerMenu) e.setCancelled(true);
    }
}
