package dev.omnipets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class PetCommands implements CommandExecutor, TabCompleter {
    private final OmniPets pl;

    public PetCommands(OmniPets pl) {
        this.pl = pl;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        if (n.equals("omnipets")) return admin(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage(pl.t(s, "player-only"));
            return true;
        }
        switch (n) {
            case "petstore" -> {
                pl.menus.openStore(p);
                return true;
            }
            case "petswitch" -> {
                pl.pets.cycle(p);
                return true;
            }
            case "petlang" -> {
                lang(p, a);
                return true;
            }
            case "pettrade" -> {
                trade(p, a);
                return true;
            }
            default -> {
            }
        }
        PetData pet = pl.pets.active(p.getUniqueId());
        if (pet == null) {
            pl.tm(p, "no-active");
            return true;
        }
        switch (n) {
            case "petinfo" -> info(p, pet);
            case "petinventory" -> {
                if (!pl.menus.openBackpack(p, pet)) pl.tm(p, "no-backpack");
            }
            case "petbehavior" -> {
                if (a.length == 0) {
                    pl.menus.openBehavior(p, pet);
                } else {
                    Behavior b = Behavior.parse(String.join("", a));
                    if (b == null) {
                        pl.tm(p, "cmd.behavior-invalid");
                    } else {
                        pet.behavior = b;
                        pl.db.save(pet);
                        pl.tm(p, "cmd.behavior-set", pl.bl(p, b));
                    }
                }
            }
            case "petname" -> {
                if (a.length == 0) {
                    pl.tm(p, "cmd.name-usage");
                    return true;
                }
                String nm = String.join(" ", a);
                if (!p.hasPermission("omnipets.color")) nm = nm.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
                if (nm.length() > 24) nm = nm.substring(0, 24);
                pet.name = nm;
                pl.db.save(pet);
                pl.pets.updateName(pet);
                pl.tm(p, "cmd.name-set", nm);
            }
            case "petskills" -> pl.menus.openSkills(p, pet, a.length > 0 ? a[0].toLowerCase() : null);
            case "petride" -> {
                if (!pl.pets.ride(p, pet)) pl.tm(p, "cmd.ride-fail");
            }
            case "petfly" -> {
                if (!pl.pets.toggleFly(pet)) pl.tm(p, "cmd.fly-fail");
                else pl.tm(p, pet.flying ? "cmd.fly-on" : "cmd.fly-off");
            }
            default -> {
            }
        }
        return true;
    }

    private void lang(Player p, String[] a) {
        if (a.length == 0 || !pl.lang.has(a[0])) {
            pl.tm(p, "lang.invalid", String.join(", ", pl.lang.available()));
            return;
        }
        pl.lang.set(p, a[0]);
        pl.tm(p, "lang.set", a[0]);
    }

    private void trade(Player p, String[] a) {
        if (!p.hasPermission("omnipets.trade") || a.length == 0) {
            pl.tm(p, a.length == 0 ? "trade.usage" : "no-perm");
            return;
        }
        switch (a[0].toLowerCase()) {
            case "accept" -> pl.trade.accept(p);
            case "deny" -> pl.trade.deny(p);
            default -> {
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    pl.tm(p, "admin.not-online");
                    return;
                }
                double price = 0;
                if (a.length > 1) {
                    try {
                        price = Double.parseDouble(a[1]);
                    } catch (NumberFormatException ex) {
                        pl.tm(p, "trade.usage");
                        return;
                    }
                }
                pl.trade.offer(p, t, price);
            }
        }
    }

    private void info(Player p, PetData d) {
        pl.msg(p, pl.t(p, "info.title", d.name, d.type));
        p.sendMessage(pl.c(pl.t(p, "info.level", d.level, (int) d.xp, (int) d.xpNeeded(pl))));
        p.sendMessage(pl.c(pl.t(p, "info.behavior", pl.bl(p, d.behavior))));
        p.sendMessage(pl.c(pl.t(p, "info.hunger", (int) d.hunger, d.points)));
        if (d.entity != null) {
            p.sendMessage(pl.c(pl.t(p, "info.combat", (int) d.entity.getHealth(), String.format("%.1f", pl.pets.damageOf(d)))));
        }
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("omnipets.admin")) {
            pl.tm(s, "no-perm");
            return true;
        }
        if (a.length == 0) {
            pl.tm(s, "admin.help");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                pl.reloadAll();
                pl.tm(s, "admin.reloaded");
            }
            case "types" -> pl.tm(s, "admin.types", String.join(", ", pl.pets.typeKeys()));
            case "give" -> {
                if (a.length < 3) {
                    pl.tm(s, "admin.give-usage");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    pl.tm(s, "admin.not-online");
                    return true;
                }
                String key = a[2].toLowerCase();
                if (pl.pets.type(key) == null) {
                    pl.tm(s, "admin.type-missing");
                    return true;
                }
                String name = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : key;
                PetData p = pl.pets.create(t.getUniqueId(), key, name);
                if (pl.pets.active(t.getUniqueId()) == null) pl.pets.activate(t, p);
                pl.tm(s, "admin.given", name, t.getName());
                pl.tm(t, "admin.received", name);
            }
            case "setlevel" -> {
                if (a.length < 3) {
                    pl.tm(s, "admin.setlevel-usage");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                PetData p = t == null ? null : pl.pets.active(t.getUniqueId());
                if (p == null) {
                    pl.tm(s, "admin.setlevel-nopet");
                    return true;
                }
                try {
                    int lv = Math.max(1, Math.min(pl.getConfig().getInt("xp.max-level", 50), Integer.parseInt(a[2])));
                    p.points += Math.max(0, lv - p.level);
                    p.level = lv;
                    p.xp = 0;
                    pl.pets.applyStats(p);
                    pl.pets.updateName(p);
                    ModelHooks.apply(pl, p);
                    pl.db.save(p);
                    pl.tm(s, "admin.setlevel-ok", lv);
                } catch (NumberFormatException ex) {
                    pl.tm(s, "admin.nan");
                }
            }
            case "demo" -> {   // quay video: phát lần lượt mọi animation trước mặt người dùng
                if (!(s instanceof Player dp)) {
                    pl.tm(s, "admin.editor-console");
                    return true;
                }
                List<String> ids = new ArrayList<>(pl.anim.anims.keySet());
                pl.tm(dp, "admin.demo-start", ids.size());
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (!dp.isOnline()) return;
                        org.bukkit.Location base = dp.getLocation();
                        org.bukkit.util.Vector fwd = base.getDirection().setY(0);
                        if (fwd.lengthSquared() > 0) fwd.normalize();
                        org.bukkit.Location at = base.clone().add(fwd.clone().multiply(3));
                        pl.anim.play(id, at, base);
                        dp.sendActionBar(Component.text(id, NamedTextColor.LIGHT_PURPLE));
                    }, i * 50L);
                }
            }
            case "anim" -> {
                if (a.length < 2) {
                    pl.tm(s, "admin.anim-list", String.join(", ", pl.anim.anims.keySet()));
                } else if (!pl.anim.anims.containsKey(a[1].toLowerCase())) {
                    pl.tm(s, "admin.anim-missing", a[1]);
                } else if (s instanceof Player ap) {
                    pl.anim.play(a[1].toLowerCase(), ap.getLocation(), ap.getLocation().add(ap.getLocation().getDirection().multiply(4)));
                }
            }
            case "editor" -> {
                if (!(s instanceof Player pp)) {
                    pl.tm(s, "admin.editor-console");
                    return true;
                }
                if (!pl.getConfig().getBoolean("web.enabled", true)) {
                    pl.tm(pp, "admin.editor-off");
                    return true;
                }
                try {
                    String url = pl.web.open();
                    pp.sendMessage(pl.c(pl.getConfig().getString("settings.prefix", "")).append(pl.c(pl.t(pp, "admin.editor-link")))
                            .append(Component.text(url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))));
                } catch (Exception ex) {
                    pl.tm(pp, "admin.editor-fail", ex.getMessage());
                }
            }
            default -> pl.tm(s, "admin.unknown");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (n.equals("omnipets")) {
            if (a.length == 1) out.addAll(List.of("reload", "types", "give", "setlevel", "editor", "anim", "demo"));
            else if (a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("setlevel")))
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.addAll(pl.pets.typeKeys());
            else if (a.length == 2 && a[0].equalsIgnoreCase("anim")) out.addAll(pl.anim.anims.keySet());
        } else if (n.equals("petbehavior") && a.length == 1) {
            out.addAll(List.of("aggressive", "farm", "protect", "friendly", "aura-farming"));
        } else if (n.equals("petskills") && a.length == 1) {
            out.addAll(pl.trees.trees.keySet());
        } else if (n.equals("petlang") && a.length == 1) {
            out.addAll(pl.lang.available());
        } else if (n.equals("pettrade") && a.length == 1) {
            out.addAll(List.of("accept", "deny"));
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String pre = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(pre));
        return out;
    }
}
