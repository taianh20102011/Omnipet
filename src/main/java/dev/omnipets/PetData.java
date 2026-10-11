package dev.omnipets;

import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class PetData {
    public int id;
    public UUID owner;
    public String type, name;
    public int level = 1, points = 0;
    public double xp = 0, hunger = 100;
    public Behavior behavior = Behavior.FRIENDLY;
    public Set<String> unlocked = new LinkedHashSet<>();   // "tree.node"
    public ItemStack[] items = new ItemStack[0];
    public boolean active;

    // runtime
    public transient Mob entity;
    public transient boolean flying;
    public transient long lastAttack;
    public transient Block farmTarget;
    public transient String modelId;
    public transient int stuck;

    public double xpNeeded(OmniPets pl) {
        return pl.getConfig().getDouble("xp.base", 40) * Math.pow(level, 1.4);
    }
}
