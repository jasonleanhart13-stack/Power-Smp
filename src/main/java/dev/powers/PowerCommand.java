package dev.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /power power  and  /power <power> ability <1|2> change <cooldown|damage> <number> */
public final class PowerCommand implements CommandExecutor, TabCompleter {
    private final PowersPlugin plugin;
    private final PowerManager pm;

    public PowerCommand(PowersPlugin plugin, PowerManager pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private void usage(CommandSender s) {
        msg(s, "Usage: /power power", NamedTextColor.YELLOW);
        msg(s, "       /power <power> ability <1|2> change <cooldown|damage|length> <number>", NamedTextColor.YELLOW);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { usage(s); return true; }
        if (a.length == 1 && a[0].equalsIgnoreCase("power")) {
            if (!(s instanceof Player p)) { msg(s, "Players only.", NamedTextColor.RED); return true; }
            showInfo(p);
            return true;
        }
        if (a.length != 6 || !a[1].equalsIgnoreCase("ability") || !a[3].equalsIgnoreCase("change")) {
            usage(s);
            return true;
        }
        if (!s.hasPermission("powers.admin")) { msg(s, "You don't have permission.", NamedTextColor.RED); return true; }
        int n;
        try { n = Integer.parseInt(a[2]); } catch (NumberFormatException e) { n = 0; }
        if (n != 1 && n != 2) { msg(s, "Ability must be 1 or 2.", NamedTextColor.RED); return true; }
        double value;
        try { value = Double.parseDouble(a[5]); } catch (NumberFormatException e) {
            msg(s, "'" + a[5] + "' is not a number.", NamedTextColor.RED);
            return true;
        }
        if (value < 0) { msg(s, "The number can't be negative.", NamedTextColor.RED); return true; }
        String what = a[4].toLowerCase();
        Power pw = Power.fromName(a[0]);
        Legendary lg = pw == null ? Legendary.fromName(a[0]) : null;
        if (pw == null && lg == null) { msg(s, "Unknown power or legendary: " + a[0], NamedTextColor.RED); return true; }
        String name = pw != null ? pw.displayName() : lg.displayName();

        switch (what) {
            case "cooldown" -> {
                if (lg != null && lg.ability(n) == null) { msg(s, name + " has no ability " + n + ".", NamedTextColor.RED); return true; }
                int secs = (int) Math.round(value);
                plugin.getConfig().set((pw != null ? "cooldowns." + pw.name() : "legendary-cooldowns." + lg.name()) + "." + n, secs);
                plugin.saveConfig();
                msg(s, name + " ability " + n + " cooldown is now " + Util.fmtSeconds(secs) + " for everyone.", NamedTextColor.GREEN);
            }
            case "damage" -> {
                boolean ok = pw != null ? pm.hasDamage(pw, n) : (lg != null && PowerManager.legendaryDefaultDamage(lg, n) != null);
                if (!ok) { msg(s, name + " ability " + n + " doesn't deal damage.", NamedTextColor.RED); return true; }
                plugin.getConfig().set((pw != null ? "damage." + pw.name() : "legendary-damage." + lg.name()) + "." + n, value);
                plugin.saveConfig();
                msg(s, name + " ability " + n + " damage is now " + PowerManager.fmt(value) + " hearts for everyone.", NamedTextColor.GREEN);
            }
            case "length" -> {
                double base = pw != null ? Abilities.duration(pw, n) : PowerManager.legendaryDefaultLength(lg, n);
                if (base <= 0) {
                    msg(s, name + " ability " + n + " is instant, so it has no length to change.", NamedTextColor.RED);
                    return true;
                }
                if (value <= 0) { msg(s, "The length must be more than 0 seconds.", NamedTextColor.RED); return true; }
                plugin.getConfig().set((pw != null ? "lengths." + pw.name() : "legendary-lengths." + lg.name()) + "." + n, value);
                plugin.saveConfig();
                msg(s, name + " ability " + n + " now lasts " + PowerManager.fmt(value) + " seconds for everyone.", NamedTextColor.GREEN);
            }
            default -> usage(s);
        }
        return true;
    }

    private void showInfo(Player p) {
        Power main = pm.rawPower(p);
        Power sec = pm.secondRaw(p);
        pm.refreshCarried(p);
        boolean dbl = pm.carries(p, Legendary.DOUBLE_POWER);
        p.sendMessage(Component.text("── Your power ──", NamedTextColor.GOLD));
        describe(p, main, 1);
        if (sec != null && dbl) describe(p, sec, 3);
        else if (sec != null) p.sendMessage(Component.text("(Second power " + sec.displayName() + " is inactive without the Double Power legendary.)", NamedTextColor.DARK_GRAY));
        List<String> have = new ArrayList<>();
        for (Legendary l : Legendary.values()) if (pm.carries(p, l)) have.add(l.displayName());
        if (!have.isEmpty()) {
            p.sendMessage(Component.text("Legendaries carried: " + String.join(", ", have), NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void describe(Player p, Power pw, int firstSlot) {
        p.sendMessage(Component.text(pw.symbol() + " " + pw.displayName(), pw.color()));
        p.sendMessage(Component.text("Passives:", NamedTextColor.GOLD));
        for (String s : pw.passives()) p.sendMessage(Component.text(" • " + s, NamedTextColor.GRAY));
        for (int n = 1; n <= 2; n++) {
            Ability ab = pw.ability(n);
            String dmg = pm.damageLabel(pw, n);
            double len = pm.lengthSeconds(pw, n);
            p.sendMessage(Component.text("/ability " + (firstSlot + n - 1) + " - " + ab.name(), NamedTextColor.GOLD)
                    .append(Component.text(" [" + Util.fmtSeconds(pm.cooldownSeconds(pw, n)) + " cooldown"
                            + (len > 0 && !(pw == Power.EXPLOSIVE && n == 1) ? ", lasts " + PowerManager.fmt(len) + "s" : "")
                            + (dmg != null ? ", " + dmg : "") + "]", NamedTextColor.YELLOW)));
            p.sendMessage(Component.text("    " + ab.desc(), NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        switch (a.length) {
            case 1 -> {
                out.add("power");
                if (s.hasPermission("powers.admin")) {
                    for (Power p : Power.values()) out.add(p.name().toLowerCase());
                    for (Legendary l : Legendary.values()) out.add(l.name().toLowerCase());
                }
            }
            case 2 -> out.add("ability");
            case 3 -> { out.add("1"); out.add("2"); }
            case 4 -> out.add("change");
            case 5 -> { out.add("cooldown"); out.add("damage"); out.add("length"); }
            default -> { }
        }
        String prefix = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
