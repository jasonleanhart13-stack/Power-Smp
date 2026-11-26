package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The /ability menu. Page 1: powers. Page 2: legendary items. */
public final class PowerMenu implements InventoryHolder {
    public static final int NEXT_SLOT = 53;
    public static final int PREV_SLOT = 45;
    public static final int REROLL_SLOT = 47;

    private static final int[] LAYOUT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private final Inventory inv;
    private final int page;
    private final Map<Integer, Power> powerSlots = new HashMap<>();
    private final Map<Integer, Legendary> legendarySlots = new HashMap<>();

    public PowerMenu(PowerManager pm, Player viewer, int page) {
        this.page = page;
        inv = Bukkit.createInventory(this, 54,
                Component.text(page == 1 ? "Choose your power" : "Legendaries", NamedTextColor.DARK_GRAY));
        if (page == 1) {
            Power cur = pm.rawPower(viewer);
            Power cur2 = pm.secondRaw(viewer);
            Power[] all = Power.values();
            for (int i = 0; i < all.length && i < LAYOUT.length; i++) {
                Power pw = all[i];
                inv.setItem(LAYOUT[i], build(pm, pw, pw == cur, pw == cur2 && pm.carries(viewer, Legendary.DOUBLE_POWER)));
                powerSlots.put(LAYOUT[i], pw);
            }
            inv.setItem(49, book("How it works",
                    "Left click a power to make it your power.",
                    "Right click a power to make it your SECOND power (needs the Double Power legendary).",
                    "/ability 1 and /ability 2 use abilities. /ability 3 and 4 use the second power's.",
                    "/ability cooldown resets your cooldowns."));
            inv.setItem(REROLL_SLOT, rerollItem());
            inv.setItem(NEXT_SLOT, nav("Legendaries  →", "Click to see the legendary items."));
        } else {
            Legendary[] all = Legendary.values();
            for (int i = 0; i < all.length && i < LAYOUT.length; i++) {
                inv.setItem(LAYOUT[i], buildLegendary(pm, all[i]));
                legendarySlots.put(LAYOUT[i], all[i]);
            }
            inv.setItem(49, book("Legendaries",
                    "Click a legendary to receive the item.",
                    "Hold it (or carry it, for passive ones) to use it.",
                    "While holding one with abilities: press F (ability 1) or Shift + F (ability 2).",
                    "The Tracker: right click it to pick a player to follow."));
            inv.setItem(PREV_SLOT, nav("←  Powers", "Click to go back to the powers."));
        }
    }

    private ItemStack book(String name, String... lines) {
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta m = info.getItemMeta();
        m.displayName(Util.text(name, NamedTextColor.YELLOW));
        List<Component> lore = new ArrayList<>();
        for (String s : lines) {
            for (String w : Util.wrap(s, 40)) lore.add(Util.text(w, NamedTextColor.GRAY));
        }
        m.lore(lore);
        info.setItemMeta(m);
        return info;
    }

    private ItemStack rerollItem() {
        ItemStack it = RerollBook.create();
        ItemMeta m = it.getItemMeta();
        List<Component> lore = new ArrayList<>(m.lore());
        lore.add(Component.empty());
        lore.add(Util.text("Click to receive a Reroll Book!", NamedTextColor.AQUA));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack nav(String name, String hint) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta m = it.getItemMeta();
        m.displayName(Util.text(name, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true));
        m.lore(List.of(Util.text(hint, NamedTextColor.GRAY)));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack build(PowerManager pm, Power pw, boolean current, boolean currentSecond) {
        ItemStack it = new ItemStack(pw.icon());
        ItemMeta m = it.getItemMeta();
        String tag = current ? "  (current)" : (currentSecond ? "  (second power)" : "");
        m.displayName(Util.text(pw.symbol() + " " + pw.displayName() + tag, pw.color())
                .decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Util.text("Passives", NamedTextColor.GOLD));
        for (String s : pw.passives()) {
            List<String> lines = Util.wrap(s, 38);
            for (int i = 0; i < lines.size(); i++) {
                lore.add(Util.text((i == 0 ? "• " : "  ") + lines.get(i), NamedTextColor.GRAY));
            }
        }
        for (int slot = 1; slot <= 2; slot++) {
            Ability a = pw.ability(slot);
            lore.add(Component.empty());
            lore.add(Util.text("Ability " + slot + ": " + a.name(), NamedTextColor.GOLD)
                    .append(Util.text("  [" + Util.fmtSeconds(pm.cooldownSeconds(pw, slot)) + " cooldown]", NamedTextColor.YELLOW)));
            for (String line : Util.wrap(a.desc(), 40)) lore.add(Util.text(line, NamedTextColor.GRAY));
            String dmg = pm.damageLabel(pw, slot);
            if (dmg != null) lore.add(Util.text("Damage: " + dmg, NamedTextColor.RED));
            double len = pm.lengthSeconds(pw, slot);
            if (len > 0 && !(pw == Power.EXPLOSIVE && slot == 1)) lore.add(Util.text("Lasts: " + PowerManager.fmt(len) + " seconds", NamedTextColor.GREEN));
        }
        lore.add(Component.empty());
        lore.add(Util.text(current ? "This is your current power." : "Left click: make this your power",
                current ? NamedTextColor.GREEN : NamedTextColor.AQUA));
        m.lore(lore);
        if (current || currentSecond) m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack buildLegendary(PowerManager pm, Legendary l) {
        ItemStack it = new ItemStack(l.material());
        ItemMeta m = it.getItemMeta();
        m.displayName(Util.text("★ " + l.displayName(), l.color()).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(Util.text("Legendary", NamedTextColor.GOLD));
        boolean hasAbilities = l.ability(1) != null || l.ability(2) != null;
        for (String s : l.lines()) {
            for (String w : Util.wrap((hasAbilities ? "Passive: " : "") + s, 40)) lore.add(Util.text(w, NamedTextColor.GRAY));
        }
        for (int n = 1; n <= 2; n++) {
            Ability a = l.ability(n);
            if (a == null) continue;
            lore.add(Util.text("Ability " + n + " (" + (n == 1 ? "F" : "Shift + F") + "): " + a.name(), NamedTextColor.GOLD)
                    .append(Util.text("  [" + Util.fmtSeconds(pm.legendaryCooldownSeconds(l, n)) + " cooldown]", NamedTextColor.YELLOW)));
            for (String line : Util.wrap(a.desc(), 40)) lore.add(Util.text(line, NamedTextColor.GRAY));
            String dmg = pm.legendaryDamageLabel(l, n);
            if (dmg != null) lore.add(Util.text("Damage: " + dmg, NamedTextColor.RED));
            double len = pm.legendaryLengthSeconds(l, n);
            if (len > 0) lore.add(Util.text("Lasts: " + PowerManager.fmt(len) + " seconds", NamedTextColor.GREEN));
        }
        lore.add(Component.empty());
        lore.add(Util.text("Click to receive this legendary!", NamedTextColor.AQUA));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
    }

    public int page() { return page; }
    public Power at(int slot) { return powerSlots.get(slot); }
    public Legendary legendaryAt(int slot) { return legendarySlots.get(slot); }

    @Override
    public Inventory getInventory() { return inv; }
}
