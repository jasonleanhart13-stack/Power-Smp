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

/** /trust <player> and /untrust <player>: trusted players are never hurt (or affected) by YOUR abilities. */
public final class TrustCommand implements CommandExecutor, TabCompleter {
    private final PowerManager pm;

    public TrustCommand(PowerManager pm) {
        this.pm = pm;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        boolean add = cmd.getName().equalsIgnoreCase("trust");
        if (a.length == 0) {
            List<String> names = pm.trustedNames(p);
            p.sendMessage(Component.text("Usage: /" + cmd.getName() + " <player>", NamedTextColor.YELLOW));
            p.sendMessage(Component.text("You trust: " + (names.isEmpty() ? "nobody" : String.join(", ", names)), NamedTextColor.GRAY));
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            // untrust also works for people who are offline right now
            if (!add) {
                var off = Bukkit.getOfflinePlayerIfCached(a[0]);
                if (off != null) {
                    pm.setTrustById(p, off.getUniqueId(), false);
                    p.sendMessage(Component.text(off.getName() + " is no longer trusted.", NamedTextColor.GREEN));
                    return true;
                }
            }
            p.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }
        if (t == p) {
            p.sendMessage(Component.text("That's you!", NamedTextColor.RED));
            return true;
        }
        if (add) {
            if (pm.trusts(p, t)) {
                p.sendMessage(Component.text("You already trust " + t.getName() + ".", NamedTextColor.YELLOW));
                return true;
            }
            pm.setTrust(p, t, true);
            p.sendMessage(Component.text("Your abilities will no longer hurt " + t.getName() + " (hitting them yourself still does).", NamedTextColor.GREEN));
            t.sendMessage(Component.text(p.getName() + " trusts you: their abilities won't hurt you.", NamedTextColor.GREEN));
        } else {
            if (!pm.trusts(p, t)) {
                p.sendMessage(Component.text("You don't trust " + t.getName() + ".", NamedTextColor.YELLOW));
                return true;
            }
            pm.setTrust(p, t, false);
            p.sendMessage(Component.text(t.getName() + " is no longer trusted.", NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            boolean add = cmd.getName().equalsIgnoreCase("trust");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o == s) continue;
                if (add || (s instanceof Player sp && pm.trusts(sp, o))) out.add(o.getName());
            }
            if (!add && s instanceof Player sp) out.addAll(pm.trustedNames(sp));
        }
        String prefix = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
