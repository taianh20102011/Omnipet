package dev.omnipets;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;

/** Giao dịch / tặng pet. Giá > 0 cần Vault (gọi bằng reflection, không bắt buộc). */
public class TradeManager {
    private static class Offer {
        UUID seller;
        PetData pet;
        double price;
        long expires;
    }

    private final OmniPets pl;
    private final Map<UUID, Offer> byBuyer = new HashMap<>();

    public TradeManager(OmniPets pl) {
        this.pl = pl;
    }

    private String price(double v, CommandSender s) {
        return v > 0 ? String.format("%.0f", v) : pl.t(s, "trade.free");
    }

    public void offer(Player s, Player b, double price) {
        if (!pl.getConfig().getBoolean("trade.enabled", true)) return;
        if (s.equals(b)) {
            pl.tm(s, "trade.self");
            return;
        }
        PetData pet = pl.pets.active(s.getUniqueId());
        if (pet == null) {
            pl.tm(s, "trade.nopet");
            return;
        }
        Offer o = new Offer();
        o.seller = s.getUniqueId();
        o.pet = pet;
        o.price = Math.max(0, price);
        o.expires = System.currentTimeMillis() + 60_000L;
        byBuyer.put(b.getUniqueId(), o);
        pl.tm(s, "trade.offered", pet.name, b.getName(), price(o.price, s));
        pl.tm(b, "trade.received", s.getName(), pet.name, pet.level, price(o.price, b));
    }

    public void deny(Player b) {
        Offer o = byBuyer.remove(b.getUniqueId());
        if (o == null) {
            pl.tm(b, "trade.none");
            return;
        }
        pl.tm(b, "trade.denied");
        Player s = Bukkit.getPlayer(o.seller);
        if (s != null) pl.tm(s, "trade.denied-seller", b.getName());
    }

    public void accept(Player b) {
        Offer o = byBuyer.remove(b.getUniqueId());
        if (o == null || System.currentTimeMillis() > o.expires) {
            pl.tm(b, "trade.none");
            return;
        }
        Player s = Bukkit.getPlayer(o.seller);
        List<PetData> sl = pl.pets.owned.get(o.seller);
        if (s == null || sl == null || !sl.contains(o.pet)) {
            pl.tm(b, "trade.gone");
            return;
        }
        if (pl.pets.list(b.getUniqueId()).size() >= pl.getConfig().getInt("settings.max-pets", 3) && !b.hasPermission("omnipets.unlimited")) {
            pl.tm(b, "trade.full");
            return;
        }
        if (o.price > 0) {
            Object eco = eco();
            if (eco == null) {
                pl.tm(b, "trade.noeco");
                return;
            }
            try {
                Class<?> ec = Class.forName("net.milkbowl.vault.economy.Economy");
                boolean has = (boolean) ec.getMethod("has", OfflinePlayer.class, double.class).invoke(eco, b, o.price);
                if (!has) {
                    pl.tm(b, "trade.nomoney");
                    return;
                }
                Object r = ec.getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(eco, b, o.price);
                Class<?> rc = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
                if (!(boolean) rc.getMethod("transactionSuccess").invoke(r)) {
                    pl.tm(b, "trade.nomoney");
                    return;
                }
                ec.getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(eco, s, o.price);
            } catch (Throwable ex) {
                pl.getLogger().warning("Vault lỗi: " + ex);
                pl.tm(b, "trade.noeco");
                return;
            }
        }
        pl.pets.despawn(o.pet);
        sl.remove(o.pet);
        o.pet.owner = b.getUniqueId();
        o.pet.active = false;
        pl.db.save(o.pet);
        pl.pets.owned.computeIfAbsent(b.getUniqueId(), k -> new ArrayList<>()).add(o.pet);
        pl.tm(b, "trade.done-buyer", o.pet.name);
        pl.tm(s, "trade.done-seller", o.pet.name, b.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object eco() {
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            return rsp == null ? null : rsp.getProvider();
        } catch (Throwable t) {
            return null;
        }
    }
}
