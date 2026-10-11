package dev.omnipets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class Menus implements Listener {
    public enum Kind {BEHAVIOR, STORE, SKILLS, TREES, BACKPACK}

    public static class Holder implements InventoryHolder {
        public final Kind kind;
        public final PetData pet;
        public Inventory inv;
        public String tree;
        public int page;
        public List<PetData> pets = new ArrayList<>();
        public List<SkillTrees.Node> nodes = new ArrayList<>();
        public List<String> treeKeys = new ArrayList<>();

        public Holder(Kind k, PetData p) {
            kind = k;
            pet = p;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final OmniPets pl;

    public Menus(OmniPets pl) {
        this.pl = pl;
    }

    private ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack i = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta im = i.getItemMeta();
        im.displayName(pl.c(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(pl.c(s).decoration(TextDecoration.ITALIC, false));
        im.lore(l);
        if (glow) im.setEnchantmentGlintOverride(true);
        i.setItemMeta(im);
        return i;
    }

    private static String fmt(double d) {
        return d == (long) d ? String.valueOf((long) d) : String.format("%.2f", d);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(pl, r);
    }

    public static boolean match(String pat, String s) {
        pat = pat.toLowerCase();
        if (pat.equals("*")) return true;
        if (pat.length() > 2 && pat.startsWith("*") && pat.endsWith("*")) return s.contains(pat.substring(1, pat.length() - 1));
        if (pat.startsWith("*")) return s.endsWith(pat.substring(1));
        if (pat.endsWith("*")) return s.startsWith(pat.substring(0, pat.length() - 1));
        return pat.equals(s);
    }

    public List<String> allowedTrees(PetData pet) {
        ConfigurationSection t = pl.pets.type(pet.type);
        List<String> pats = t == null ? List.of() : t.getStringList("skilltrees");
        List<String> out = new ArrayList<>();
        for (String tr : pl.trees.trees.keySet()) {
            for (String p : pats) {
                if (match(p, tr)) {
                    out.add(tr);
                    break;
                }
            }
        }
        return out;
    }

    // ---------- Hành vi ----------
    public void openBehavior(Player p, PetData pet) {
        Holder h = new Holder(Kind.BEHAVIOR, pet);
        h.inv = Bukkit.createInventory(h, 27, pl.c(pl.t(p, "menu.behavior.title", pet.name)));
        int[] slots = {11, 12, 13, 14, 15};
        Behavior[] bs = Behavior.values();
        for (int i = 0; i < bs.length; i++) {
            Behavior b = bs[i];
            boolean sel = b == pet.behavior;
            h.inv.setItem(slots[i], item(b.icon, pl.bl(p, b),
                    List.of("&7" + pl.bd(p, b), "", sel ? pl.t(p, "menu.selected") : pl.t(p, "menu.click-select")), sel));
        }
        p.openInventory(h.inv);
    }

    // ---------- Kho lưu trữ pet ----------
    public void openStore(Player p) {
        Holder h = new Holder(Kind.STORE, null);
        List<PetData> l = pl.pets.list(p.getUniqueId());
        h.pets = new ArrayList<>(l);
        int rows = Math.min(6, Math.max(1, (int) Math.ceil(l.size() / 9.0)));
        h.inv = Bukkit.createInventory(h, rows * 9, pl.c(pl.t(p, "menu.store.title")));
        for (int i = 0; i < l.size() && i < rows * 9; i++) {
            PetData d = l.get(i);
            ConfigurationSection t = pl.pets.type(d.type);
            Material egg = Material.matchMaterial((t == null ? "PIG" : t.getString("entity", "PIG")) + "_SPAWN_EGG");
            h.inv.setItem(i, item(egg == null ? Material.EGG : egg, "&f" + d.name, List.of(
                    pl.t(p, "menu.store.level", d.level, (int) d.xp, (int) d.xpNeeded(pl)),
                    pl.t(p, "menu.store.behavior", pl.bl(p, d.behavior)),
                    pl.t(p, "menu.store.hunger", (int) d.hunger),
                    pl.t(p, "menu.store.points", d.points), "",
                    d.active ? pl.t(p, "menu.store.active") : pl.t(p, "menu.store.summon")), d.active));
        }
        p.openInventory(h.inv);
    }

    // ---------- Kho đồ pet ----------
    public boolean openBackpack(Player p, PetData pet) {
        if (pet.items.length == 0 || pet.items.length % 9 != 0) return false;
        Holder h = new Holder(Kind.BACKPACK, pet);
        h.inv = Bukkit.createInventory(h, pet.items.length, pl.c(pl.t(p, "menu.backpack.title", pet.name)));
        h.inv.setContents(pet.items);
        p.openInventory(h.inv);
        return true;
    }

    // ---------- Danh sách cây kỹ năng (phân trang) ----------
    public void openTrees(Player p, PetData pet, int page) {
        List<String> all = allowedTrees(pet);
        if (all.isEmpty()) {
            pl.tm(p, "menu.no-trees");
            return;
        }
        int pages = (all.size() + 44) / 45;
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder(Kind.TREES, pet);
        h.page = page;
        h.treeKeys = all;
        h.inv = Bukkit.createInventory(h, 54, pl.c(pl.t(p, "menu.trees.title", page + 1, pages)));
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= all.size()) break;
            String k = all.get(idx);
            List<SkillTrees.Node> ns = pl.trees.trees.get(k);
            long got = ns.stream().filter(n -> pet.unlocked.contains(n.key)).count();
            h.inv.setItem(i, item(pl.trees.icons.get(k), pl.trees.names.get(k),
                    List.of(pl.t(p, "menu.trees.progress", got, ns.size()), pl.t(p, "menu.click-open")), got > 0));
        }
        if (page > 0) h.inv.setItem(45, item(Material.ARROW, pl.t(p, "menu.prev"), List.of(), false));
        if (page < pages - 1) h.inv.setItem(53, item(Material.ARROW, pl.t(p, "menu.next"), List.of(), false));
        h.inv.setItem(49, item(Material.BOOK, pl.t(p, "menu.skills.points", pet.points), List.of(pl.t(p, "menu.skills.points-lore", pet.level, pet.name)), false));
        p.openInventory(h.inv);
    }

    // ---------- Cây kỹ năng ----------
    public void openSkills(Player p, PetData pet, String tree) {
        List<String> allowed = allowedTrees(pet);
        if (allowed.isEmpty()) {
            pl.tm(p, "menu.no-trees");
            return;
        }
        if (tree == null) {
            if (allowed.size() == 1) tree = allowed.get(0);
            else {
                openTrees(p, pet, 0);
                return;
            }
        }
        if (!allowed.contains(tree)) {
            openTrees(p, pet, 0);
            return;
        }
        Holder h = new Holder(Kind.SKILLS, pet);
        h.tree = tree;
        h.treeKeys = allowed;
        h.inv = Bukkit.createInventory(h, 54, pl.c(pl.t(p, "menu.skills.title", pl.trees.names.get(tree))));
        List<SkillTrees.Node> ns = pl.trees.trees.get(tree);
        for (int i = 0; i < ns.size() && i < 45; i++) {
            SkillTrees.Node n = ns.get(i);
            h.nodes.add(n);
            boolean has = pet.unlocked.contains(n.key);
            List<String> lore = new ArrayList<>();
            lore.add(pl.t(p, "menu.skills.cost", n.cost, n.unlockLevel));
            if (!n.requires.isEmpty()) lore.add(pl.t(p, "menu.skills.requires", String.join(", ", n.requires)));
            if (n.inherit != null) lore.add(pl.t(p, "menu.skills.inherit", n.inherit));
            lore.add("");
            n.effects.forEach((k, v) -> {
                SkillEffects.Def fd = pl.fx.defs.get(k);
                String lab = fd != null ? fd.label : pl.t(p, "effect." + k);
                lore.add("&a+" + fmt(v) + " &7" + (lab.equals("effect." + k) ? k : lab));
            });
            lore.add("");
            lore.add(has ? pl.t(p, "menu.skills.unlocked") : pl.t(p, "menu.skills.click"));
            h.inv.setItem(i, item(n.icon, n.name, lore, has));
        }
        h.inv.setItem(45, item(Material.ARROW, pl.t(p, "menu.back"), List.of(), false));
        h.inv.setItem(53, item(Material.BOOK, pl.t(p, "menu.skills.points", pet.points), List.of(pl.t(p, "menu.skills.points-lore", pet.level, pet.name)), false));
        p.openInventory(h.inv);
    }

    private String err(Player p, PetData pet, SkillTrees.Node n) {
        if (pet.unlocked.contains(n.key)) return pl.t(p, "menu.skills.err.have");
        if (pet.level < n.unlockLevel) return pl.t(p, "menu.skills.err.level", n.unlockLevel);
        if (pet.points < n.cost) return pl.t(p, "menu.skills.err.points", n.cost);
        for (String r : n.requires) if (!pet.unlocked.contains(n.tree + "." + r)) return pl.t(p, "menu.skills.err.req", r);
        return null;
    }

    // ---------- sự kiện ----------
    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        if (h.kind == Kind.BACKPACK) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        int s = e.getSlot();
        switch (h.kind) {
            case BEHAVIOR -> {
                int[] slots = {11, 12, 13, 14, 15};
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == s) {
                        h.pet.behavior = Behavior.values()[i];
                        pl.db.save(h.pet);
                        pl.tm(p, "cmd.behavior-set", pl.bl(p, h.pet.behavior));
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                        later(() -> openBehavior(p, h.pet));
                    }
                }
            }
            case STORE -> {
                if (s >= h.pets.size()) return;
                PetData t = h.pets.get(s);
                if (t.active) {
                    pl.pets.deactivate(t);
                } else {
                    int max = pl.getConfig().getInt("settings.max-active", 1);
                    List<PetData> act = pl.pets.actives(p.getUniqueId());
                    if (act.size() >= max) {
                        if (max == 1) pl.pets.deactivate(act.get(0));
                        else {
                            pl.tm(p, "menu.store.limit");
                            return;
                        }
                    }
                    pl.pets.activate(p, t);
                }
                later(() -> openStore(p));
            }
            case TREES -> {
                if (s == 45 && h.page > 0) later(() -> openTrees(p, h.pet, h.page - 1));
                else if (s == 53) later(() -> openTrees(p, h.pet, h.page + 1));
                else if (s < 45) {
                    int idx = h.page * 45 + s;
                    if (idx < h.treeKeys.size()) {
                        String k = h.treeKeys.get(idx);
                        later(() -> openSkills(p, h.pet, k));
                    }
                }
            }
            case SKILLS -> {
                if (s == 45) {
                    int pg = Math.max(0, h.treeKeys.indexOf(h.tree)) / 45;
                    later(() -> openTrees(p, h.pet, pg));
                    return;
                }
                if (s >= h.nodes.size()) return;
                SkillTrees.Node n = h.nodes.get(s);
                String er = err(p, h.pet, n);
                if (er != null) {
                    pl.msg(p, "&c" + er);
                } else {
                    pl.trees.tryUnlock(h.pet, n);
                    pl.pets.applyStats(h.pet);
                    pl.db.save(h.pet);
                    pl.tm(p, "menu.skills.unlock-ok", n.name);
                    pl.anim.event(h.pet, "unlock", h.pet.entity != null ? h.pet.entity.getLocation() : p.getLocation(), null);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                }
                later(() -> openSkills(p, h.pet, h.tree));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void close(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h && h.kind == Kind.BACKPACK) {
            h.pet.items = e.getInventory().getContents();
            pl.db.save(h.pet);
        }
    }
}
