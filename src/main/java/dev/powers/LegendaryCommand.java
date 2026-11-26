package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /legendary 1|2 (use the held legendary's abilities), list, give. */
public final class LegendaryCommand implements CommandExecutor, TabCompleter {
    private final PowerManager pm;
    private final LegendaryAbilities la;

    public LegendaryCommand(PowerManager pm, LegendaryAbilities la) {
        this.pm = pm;
        this.la = la;
    }

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            msg(s, "Usage: /legendary <list|give>", NamedTextColor.YELLOW);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "list" -> {
                for (Legendary l : Legendary.values()) {
                    s.sendMessage(Component.text("★ " + l.displayName(), l.color()));
                    for (String line : l.lines()) s.sendMessage(Component.text("   " + line, NamedTextColor.GRAY));
                }
            }
            case "give" -> {
                if (!s.hasPermission("powers.set")) { msg(s, "You don't have permission.", NamedTextColor.RED); return true; }
                if (a.length < 2) { msg(s, "Usage: /legendary give <name> [player]", NamedTextColor.YELLOW); return true; }
                Legendary l = Legendary.fromName(a[1]);
                if (l == null) { msg(s, "Unknown legendary: " + a[1], NamedTextColor.RED); return true; }
                Player target = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player self ? self : null);
                if (target == null) { msg(s, "Player not found.", NamedTextColor.RED); return true; }
                target.getInventory().addItem(l.createItem()).values()
                        .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
                pm.refreshCarried(target);
                msg(s, "Gave " + l.displayName() + " to " + target.getName() + ".", NamedTextColor.GREEN);
            }
            default -> msg(s, "Usage: /legendary <list|give>", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("list");
            if (s.hasPermission("powers.set")) out.add("give");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Legendary l : Legendary.values()) out.add(l.name().toLowerCase());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
