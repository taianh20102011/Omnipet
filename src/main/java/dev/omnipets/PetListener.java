package dev.omnipets;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PetListener implements Listener {
    private final OmniPets pl;

    public PetListener(OmniPets pl) {
        this.pl = pl;
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (p.isOnline()) pl.pets.loadPlayer(p);
        }, pl.getConfig().getLong("settings.join-delay-ticks", 20L));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        pl.pets.unloadPlayer(e.getPlayer());
    }

    @EventHandler
    public void interact(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Entity t = e.getRightClicked();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (t.hasMetadata("NPC")) {   // Citizens: chuột phải NPC có tên citizens.npc-name -> mở /petstore
            String want = pl.getConfig().getString("citizens.npc-name", "");
            if (!want.isBlank() && t.getName().replaceAll("§[0-9a-fk-orA-FK-OR]", "").equalsIgnoreCase(want)) {
                e.setCancelled(true);
                pl.menus.openStore(p);
                return;
            }
        }
        PetData pet = pl.pets.byEntity.get(t.getUniqueId());
        if (pet != null) {
            e.setCancelled(true);
            if (!pet.owner.equals(p.getUniqueId())) {
                pl.tm(p, "not-owner");
                return;
            }
            if (hand.getType() != Material.AIR && pl.pets.feed(p, pet, hand)) return;
            if (p.isSneaking()) {
                pl.menus.openBehavior(p, pet);
                return;
            }
            if (!pl.pets.ride(p, pet)) pl.tm(p, "hint-ride");
            return;
        }
        if (hand.getType() != Material.AIR && pl.pets.tame(p, t, hand)) e.setCancelled(true);
    }

    @EventHandler
    public void damaged(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
        pl.pets.lastAttacker.put(victim.getUniqueId(), d.getUniqueId());
        pl.pets.lastAttackTime.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void petHit(EntityDamageByEntityEvent e) {
        PetData p = pl.pets.byEntity.get(e.getDamager().getUniqueId());
        if (p != null && e.isCancelled()) pl.pets.dbgPet(p, "&cSát thương của pet bị HỦY bởi plugin khác (EntityDamageByEntityEvent bị cancel)");
    }

    @EventHandler
    public void target(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() != null && pl.pets.byEntity.containsKey(e.getTarget().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void death(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        EntityDamageEvent dc = e.getEntity().getLastDamageCause();
        if (dc instanceof EntityDamageByEntityEvent d) {
            PetData p = pl.pets.byEntity.get(d.getDamager().getUniqueId());
            if (p != null) pl.pets.gainXp(p, pl.getConfig().getDouble("xp.per-kill", 6));
        }
    }
}
