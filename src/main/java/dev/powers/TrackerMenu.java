package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Tracker's menu: the head of every online player. Click one to follow a red trail to them. */
public final class TrackerMenu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, UUID> slots = new HashMap<>();

    public TrackerMenu(PowerManager pm, Player viewer) {
        inv = Bukkit.createInventory(this, 54, Component.text("Tracker - pick a player", NamedTextColor.DARK_GRAY));
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (slot >= 54) break;
            if (o == viewer || o.getGameMode() == GameMode.SPECTATOR || pm.hidden.contains(o.getUniqueId())) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwningPlayer(o);
            m.displayName(Util.text(o.getName(), NamedTextColor.GREEN));
            List<Component> lore = new ArrayList<>();
            if (o.getWorld().equals(viewer.getWorld())) {
                lore.add(Util.text((int) o.getLocation().distance(viewer.getLocation()) + " blocks away", NamedTextColor.GRAY));
            } else {
                lore.add(Util.text("In another world", NamedTextColor.GRAY));
            }
            lore.add(Util.text("Click to follow a red trail to them", NamedTextColor.YELLOW));
            m.lore(lore);
            head.setItemMeta(m);
            inv.setItem(slot, head);
            slots.put(slot, o.getUniqueId());
            slot++;
        }
    }

    public UUID at(int slot) { return slots.get(slot); }

    @Override
    public Inventory getInventory() { return inv; }
}
