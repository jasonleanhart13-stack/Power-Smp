POWERS PLUGIN (Paper 26.3, Java 25)
===================================

WHAT YOU HAVE
This folder is the SOURCE of the plugin. A server needs the compiled Powers.jar,
so you build it once (easiest options below), then upload Powers.jar to your
server's /plugins folder and restart.

EASIEST BUILD OPTION: GitHub (no installs)
1. Make a free GitHub account and create a new repository.
2. Upload everything in this folder (keep the .github folder!).
3. Open the "Actions" tab -> "Build plugin" -> wait for the green check.
4. Open the finished run and download the "Powers" artifact. Unzip it: Powers.jar.

BUILD ON YOUR PC
1. Install JDK 25 (Temurin) and Maven.
2. In this folder run:   mvn package
3. Your plugin is at:    target/Powers.jar

COMMANDS
/ability 1          use ability 1
/ability 2          use ability 2
/ability menu       open the power menu (click a power to switch to it)   [op by default]
/ability cooldown   reset your cooldowns                                  [op by default]
/ability set <power> [player]   give someone a power                     [op by default]

Permissions: powers.use (everyone), powers.menu, powers.cooldown, powers.set (op).
A config.yml is created on first start where you can change cooldowns and jump strength.
