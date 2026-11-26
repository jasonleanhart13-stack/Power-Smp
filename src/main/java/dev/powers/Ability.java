package dev.powers;

/** One ability: display name, description, default cooldown (seconds) and the sound key it plays. */
public record Ability(String name, String desc, int cooldown, String sound) {}
