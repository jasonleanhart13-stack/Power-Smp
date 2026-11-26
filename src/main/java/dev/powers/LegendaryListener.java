package dev.powers;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event handling for the legendary items. */
public final class LegendaryListener implements Listener {
    private final PowersPlugin plugin;
    private final PowerManager pm;
    private final LegendaryAbilities la;

    /** Dropped legendary item entities we are watching (for the void): entity id -> who dropped it. */
    private final Map<UUID, String> tracked = new HashMap<>();
    private final Map<UUID, BukkitTask> trails = new HashMap<>();
    private String deathOwner = null;
    private final java.util.Set<UUID> boosted = new java.util.HashSet<>();

    public LegendaryListener(PowersPlugin plugin, PowerManager pm, LegendaryAbilities la) {
        this.plugin = plugin;
        this.pm = pm;
        this.la = la;
        for (World w : Bukkit.getWorlds()) {
            for (Item it : w.getEntitiesByClass(Item.class)) protect(it, null);
        }
        new BukkitRunnable() {
            @Override public void run() { voidScan(); }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // ------------------------------------------------------------------ abilities: F and Shift + F

    @EventHandler(priority = EventPriority.LOW)
    public void onKey(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        Legendary l = Legendary.of(p.getInventory().getItemInMainHand());
        if (l == null || (l.ability(1) == null && l.ability(2) == null)) return;
        e.setCancelled(true); // the F key does NOT swap hands while holding a legendary with abilities
        la.use(p, p.isSneaking() ? 2 : 1);
    }

    // ------------------------------------------------------------------ Spear: lunging costs no hunger

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (Legendary.of(p.getInventory().getItemInMainHand()) == Legendary.SPEAR) {
            pm.swing.put(p.getUniqueId(), new double[]{System.currentTimeMillis(), p.getExhaustion(),
                    p.getFoodLevel(), p.getSaturation()});
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getFoodLevel() >= p.getFoodLevel()) return;
        double[] sw = pm.swing.get(p.getUniqueId());
        if (sw != null && System.currentTimeMillis() - sw[0] < 3000
                && Legendary.of(p.getInventory().getItemInMainHand()) == Legendary.SPEAR) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ End Sword mist

    @EventHandler
    public void onCloud(AreaEffectCloudApplyEvent e) {
        AreaEffectCloud cloud = e.getEntity();
        if (cloud.getParticle() != Particle.DRAGON_BREATH) return;
        if (!(cloud.getSource() instanceof Player owner)) return;
        List<LivingEntity> hit = new ArrayList<>(e.getAffectedEntities());
        e.getAffectedEntities().clear();
        double dmg = pm.legendaryHearts(Legendary.END_SWORD, 2) * 2;
        for (LivingEntity le : hit) {
            if (le == owner || (le instanceof Player lp && pm.isAlly(owner, lp))) continue;
            pm.dealDamage(le, dmg, owner);
        }
    }

    // End Sword's dragon fireball leaves a mist that is 1 block wider
    @EventHandler
    public void onDragonHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.DragonFireball df) || !"dragonfire".equals(pm.tagOf(df))) return;
        final Location loc = df.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getWorld() == null) return;
            for (Entity en : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
                if (en instanceof AreaEffectCloud c && c.getParticle() == Particle.DRAGON_BREATH && boosted.add(c.getUniqueId())) {
                    c.setRadius(c.getRadius() + 1.0f);
                }
            }
        }, 1L);
    }

    // ------------------------------------------------------------------ Legendary armor

    @EventHandler(priority = EventPriority.LOW)
    public void onArmorDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        // Boots: walk on lava without being hurt by it (fire from enemy abilities still hurts)
        if ((c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK
                || c == EntityDamageEvent.DamageCause.LAVA || c == EntityDamageEvent.DamageCause.HOT_FLOOR)
                && pm.wears(p, Legendary.NETHERITE_BOOTS) && !pm.isAbilityFire(p.getUniqueId())) {
            e.setCancelled(true);
            p.setFireTicks(0);
        }
    }

    // Chestplate: all food counts as a golden carrot (6 hunger, 14.4 saturation)
    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!pm.wears(p, Legendary.NETHERITE_CHESTPLATE) || !e.getItem().getType().isEdible()) return;
        final int food = p.getFoodLevel();
        final float sat = p.getSaturation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            int nf = Math.min(20, food + 6);
            p.setFoodLevel(nf);
            p.setSaturation(Math.min((float) nf, sat + 14.4f));
        });
    }

    // ------------------------------------------------------------------ Tracker

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrackerUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (Legendary.of(e.getItem()) != Legendary.TRACKER) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.openInventory(new TrackerMenu(pm, p).getInventory());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTrackerMenu(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TrackerMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        UUID target = menu.at(e.getRawSlot());
        if (target == null) return;
        Player t = Bukkit.getPlayer(target);
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        if (t == null) {
            p.sendActionBar(Util.text("That player went offline.", NamedTextColor.RED));
            return;
        }
        follow(p, t);
    }

    @EventHandler
    public void onTrackerDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof TrackerMenu) e.setCancelled(true);
    }

    /** A red trail toward the target, drawn only for the tracker's user, with the distance shown live. */
    private void follow(Player p, Player t) {
        BukkitTask old = trails.remove(p.getUniqueId());
        if (old != null) old.cancel();
        Util.sound(p.getLocation(), "block.note_block.chime", 1f, 1.4f);
        final Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.6f);
        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                ticks += 2;
                if (!p.isOnline() || !t.isOnline() || t.isDead() || ticks > 1200
                        || pm.hidden.contains(t.getUniqueId())
                        || Legendary.of(p.getInventory().getItemInMainHand()) != Legendary.TRACKER
                                && Legendary.of(p.getInventory().getItemInOffHand()) != Legendary.TRACKER) {
                    if (p.isOnline()) p.sendActionBar(Util.text("Tracking stopped.", NamedTextColor.GRAY));
                    trails.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                if (!t.getWorld().equals(p.getWorld())) {
                    if (ticks % 10 == 0) p.sendActionBar(Util.text(t.getName() + " is in another world.", NamedTextColor.RED));
                    return;
                }
                Location from = p.getLocation().add(0, 0.9, 0);
                Vector dir = t.getLocation().add(0, 0.9, 0).toVector().subtract(from.toVector());
                double dist = dir.length();
                if (ticks % 10 == 0) {
                    p.sendActionBar(Util.text("Tracking " + t.getName() + ": " + (int) dist + " blocks away", NamedTextColor.RED));
                }
                if (dist < 2) return;
                dir.normalize();
                double len = Math.min(dist - 1, 25);
                for (double d = 1.5; d <= len; d += 0.8) {
                    // spawnParticle on the PLAYER only sends it to that player
                    p.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0, red);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        trails.put(p.getUniqueId(), task);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pm.swing.remove(id);
        BukkitTask t = trails.remove(id);
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ unbreakable / fireproof / never despawn

    private static boolean isLeg(ItemStack it) {
        return Legendary.of(it) != null;
    }

    private void protect(Item item, String owner) {
        Legendary l = Legendary.of(item.getItemStack());
        if (l == null) return;
        item.setInvulnerable(true);      // no fire, lava, cactus, explosions
        item.setUnlimitedLifetime(true); // never despawns
        if (owner == null) owner = deathOwner;
        if (owner != null || !tracked.containsKey(item.getUniqueId())) tracked.put(item.getUniqueId(), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent e) {
        protect(e.getEntity(), null);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Item it) protect(it, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        protect(e.getItemDrop(), e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        deathOwner = e.getEntity().getName();
        Bukkit.getScheduler().runTask(plugin, () -> deathOwner = null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (isLeg(e.getItem())) e.setCancelled(true);
    }

    /** A legendary that falls out of the world is lost: tell everyone. */
    private void voidScan() {
        boosted.removeIf(id -> { Entity en = Bukkit.getEntity(id); return en == null || !en.isValid(); });
        for (UUID id : new ArrayList<>(tracked.keySet())) {
            Entity en = Bukkit.getEntity(id);
            if (en == null || !en.isValid()) {
                tracked.remove(id);
                continue;
            }
            if (!(en instanceof Item it)) continue;
            if (en.getLocation().getY() < en.getWorld().getMinHeight() - 4) {
                Legendary l = Legendary.of(it.getItemStack());
                String who = tracked.remove(id);
                it.remove();
                if (l != null) pm.voidPopup(l, who);
            }
        }
    }

    // ------------------------------------------------------------------ legendaries can't be stored anywhere

    private static boolean isBundle(ItemStack it) {
        return it != null && it.getType().name().endsWith("BUNDLE");
    }

    private static boolean storageType(InventoryType t) {
        return t != InventoryType.PLAYER && t != InventoryType.CRAFTING && t != InventoryType.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStorageClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof PowerMenu || e.getInventory().getHolder() instanceof TrackerMenu) return;
        ItemStack cursor = e.getCursor();
        ItemStack cur = e.getCurrentItem();
        boolean block = false;
        // bundles
        if ((isBundle(cur) && isLeg(cursor)) || (isBundle(cursor) && isLeg(cur))) block = true;
        Inventory top = e.getView().getTopInventory();
        if (storageType(top.getType())) {
            Inventory clicked = e.getClickedInventory();
            if (clicked != null && clicked.equals(top)) {
                if (isLeg(cursor)) block = true;
                if (e.getClick() == ClickType.NUMBER_KEY) {
                    int hb = e.getHotbarButton();
                    if (hb >= 0 && isLeg(e.getWhoClicked().getInventory().getItem(hb))) block = true;
                }
                if (e.getClick() == ClickType.SWAP_OFFHAND && isLeg(e.getWhoClicked().getInventory().getItemInOffHand())) block = true;
            } else if (clicked != null && e.isShiftClick() && isLeg(cur)) {
                block = true;
            }
        }
        if (block) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                p.sendActionBar(Util.text("Legendaries can't be stored!", NamedTextColor.RED));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStorageDrag(InventoryDragEvent e) {
        if (!isLeg(e.getOldCursor())) return;
        Inventory top = e.getView().getTopInventory();
        if (!storageType(top.getType())) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isLeg(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (isLeg(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityStore(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = e.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!isLeg(hand)) return;
        Entity en = e.getRightClicked();
        if (en instanceof ItemFrame || en instanceof ArmorStand || en instanceof Allay || en instanceof AbstractHorse) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (isLeg(e.getPlayerItem()) || isLeg(e.getArmorStandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockStore(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!isLeg(e.getItem())) return;
        String n = e.getClickedBlock().getType().name();
        if (n.equals("DECORATED_POT") || n.equals("CHISELED_BOOKSHELF") || n.equals("LECTERN") || n.equals("JUKEBOX")
                || n.endsWith("CAMPFIRE") || n.equals("COMPOSTER") || n.endsWith("SHELF") || n.equals("FLOWER_POT")) {
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
        }
    }
}
