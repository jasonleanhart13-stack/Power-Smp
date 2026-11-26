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

public final class AbilityCommand implements CommandExecutor, TabCompleter {
    private final PowerManager pm;
    private final Abilities ab;

    public AbilityCommand(PowerManager pm, Abilities ab) {
        this.pm = pm;
        this.ab = ab;
    }

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            msg(s, "Usage: /ability <1|2|3|4|menu|cooldown|set>", NamedTextColor.YELLOW);
            return true;
        }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "1", "2", "3", "4" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only.", NamedTextColor.RED); return true; }
                ab.use(p, Integer.parseInt(sub));
            }
            case "menu" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only.", NamedTextColor.RED); return true; }
                if (!p.hasPermission("powers.menu")) { msg(s, "You don't have permission.", NamedTextColor.RED); return true; }
                p.openInventory(new PowerMenu(pm, p, 1).getInventory());
            }
            case "cooldown" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only.", NamedTextColor.RED); return true; }
                if (!p.hasPermission("powers.cooldown")) { msg(s, "You don't have permission.", NamedTextColor.RED); return true; }
                pm.resetCooldowns(p);
                msg(s, "Your cooldowns were reset.", NamedTextColor.GREEN);
            }
            case "set" -> {
                if (!s.hasPermission("powers.set")) { msg(s, "You don't have permission.", NamedTextColor.RED); return true; }
                if (a.length < 2) { msg(s, "Usage: /ability set <power> [player]", NamedTextColor.YELLOW); return true; }
                Power pw = Power.fromName(a[1]);
                if (pw == null) { msg(s, "Unknown power: " + a[1], NamedTextColor.RED); return true; }
                Player target = null;
                if (a.length >= 3) target = Bukkit.getPlayerExact(a[2]);
                else if (s instanceof Player self) target = self;
                if (target == null) { msg(s, "Player not found.", NamedTextColor.RED); return true; }
                pm.setPower(target, pw);
                msg(s, target.getName() + " now has " + pw.displayName() + ".", NamedTextColor.GREEN);
            }
            default -> msg(s, "Usage: /ability <1|2|3|4|menu|cooldown|set>", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("1");
            out.add("2");
            out.add("3");
            out.add("4");
            if (s.hasPermission("powers.menu")) out.add("menu");
            if (s.hasPermission("powers.cooldown")) out.add("cooldown");
            if (s.hasPermission("powers.set")) out.add("set");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("set")) {
            for (Power p : Power.values()) out.add(p.name().toLowerCase());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
