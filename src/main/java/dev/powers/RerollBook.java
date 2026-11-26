package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** The Reroll Book: craftable, right-click to get a new random power (and a new second power with Double Power). */
public final class RerollBook implements Listener {
    public static NamespacedKey KEY;
    private static NamespacedKey TAG;
    private final PowerManager pm;

    public RerollBook(PowerManager pm) {
        this.pm = pm;
    }

    public static void init(PowersPlugin plugin) {
        KEY = new NamespacedKey(plugin, "reroll_book");
        TAG = new NamespacedKey(plugin, "reroll_book_item");
        Bukkit.removeRecipe(KEY);
        ShapedRecipe r = new ShapedRecipe(KEY, create());
        r.shape("DED", "TNT", "DED");
        r.setIngredient('D', Material.DIAMOND_BLOCK);
        r.setIngredient('E', Material.ENCHANTED_GOLDEN_APPLE);
        r.setIngredient('T', Material.TOTEM_OF_UNDYING);
        r.setIngredient('N', Material.NETHERITE_INGOT);
        Bukkit.addRecipe(r);
    }

    public static ItemStack create() {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Util.text("Reroll Book", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
                Util.text("Right click to randomize your power.", NamedTextColor.GRAY),
                Util.text("With the Double Power legendary, both powers are randomized.", NamedTextColor.GRAY)));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public static boolean is(ItemStack it) {
        if (it == null || TAG == null || it.getType() != Material.BOOK || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(TAG, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!is(it)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        if (it.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null);
            else p.getInventory().setItemInMainHand(null);
        } else {
            it.setAmount(it.getAmount() - 1);
        }
        pm.reroll(p);
        Util.sound(p.getLocation(), "entity.player.levelup", 1f, 0.8f);
    }
}
