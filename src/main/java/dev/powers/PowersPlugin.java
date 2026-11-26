package dev.powers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PowersPlugin extends JavaPlugin {
    private PowerManager manager;
    private Abilities abilities;

    @Override
    public void onEnable() {
        FileConfiguration c = getConfig();
        // Version 2: new default cooldowns (e.g. Speed's double jump is 5s). Reset the cooldown section once.
        if (c.getInt("config-version", 1) < 2) {
            for (Power pw : Power.values()) {
                c.set("cooldowns." + pw.name() + ".1", pw.ability(1).cooldown());
                c.set("cooldowns." + pw.name() + ".2", pw.ability(2).cooldown());
            }
            c.set("config-version", 2);
        }
        for (Power pw : Power.values()) {
            c.addDefault("cooldowns." + pw.name() + ".1", pw.ability(1).cooldown());
            c.addDefault("cooldowns." + pw.name() + ".2", pw.ability(2).cooldown());
        }
        for (Legendary l : Legendary.values()) {
            for (int n = 1; n <= 2; n++) {
                if (l.ability(n) != null) c.addDefault("legendary-cooldowns." + l.name() + "." + n, l.ability(n).cooldown());
            }
        }
        c.addDefault("tuning.double-jump-up", 0.85);
        c.addDefault("tuning.double-jump-forward", 0.45);
        c.addDefault("tuning.big-jump-horizontal", 4.6);
        c.addDefault("tuning.big-jump-vertical", 2.6);
        c.addDefault("tuning.wind-boost", 1.7);
        c.addDefault("tuning.domain-radius", 22);
        c.addDefault("drowning.radius", 8.0);
        c.addDefault("messy-ability1-targets-self", false);
        c.addDefault("hotkeys.enabled", false);
        c.options().copyDefaults(true);
        saveConfig();

        Legendary.init(this);
        RerollBook.init(this);
        manager = new PowerManager(this);
        abilities = new Abilities(this, manager);
        getServer().getPluginManager().registerEvents(new PowerListener(this, manager, abilities), this);

        LegendaryAbilities legendaryAbilities = new LegendaryAbilities(this, manager, abilities);
        getServer().getPluginManager().registerEvents(new LegendaryListener(this, manager, legendaryAbilities), this);

        getServer().getPluginManager().registerEvents(new RerollBook(manager), this);
        TrustCommand trust = new TrustCommand(manager);
        getCommand("trust").setExecutor(trust);
        getCommand("trust").setTabCompleter(trust);
        getCommand("untrust").setExecutor(trust);
        getCommand("untrust").setTabCompleter(trust);

        PowerCommand pcmd = new PowerCommand(this, manager);
        getCommand("power").setExecutor(pcmd);
        getCommand("power").setTabCompleter(pcmd);
        LegendaryCommand lcmd = new LegendaryCommand(manager, legendaryAbilities);
        getCommand("legendary").setExecutor(lcmd);
        getCommand("legendary").setTabCompleter(lcmd);

        AbilityCommand cmd = new AbilityCommand(manager, abilities);
        getCommand("ability").setExecutor(cmd);
        getCommand("ability").setTabCompleter(cmd);

        getServer().getScheduler().runTaskTimer(this, () -> {
            manager.tick();
            abilities.tick();
        }, 1L, 1L);

        for (Player p : getServer().getOnlinePlayers()) manager.ensurePower(p);
    }

    @Override
    public void onDisable() {
        if (abilities != null) abilities.shutdown();
        if (manager != null) manager.shutdown();
    }
}
