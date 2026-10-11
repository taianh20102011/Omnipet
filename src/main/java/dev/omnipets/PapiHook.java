package dev.omnipets;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** %omnipets_name% %omnipets_level% %omnipets_xp% %omnipets_xp_needed% %omnipets_type% %omnipets_behavior% %omnipets_hunger% %omnipets_points% %omnipets_count% */
public class PapiHook extends PlaceholderExpansion {
    private final OmniPets pl;

    public PapiHook(OmniPets pl) {
        this.pl = pl;
    }

    @Override
    public String getIdentifier() {
        return "omnipets";
    }

    @Override
    public String getAuthor() {
        return "OmniPets";
    }

    @Override
    public String getVersion() {
        return pl.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String id) {
        if (player == null) return "";
        if (id.equals("count")) return String.valueOf(pl.pets.list(player.getUniqueId()).size());
        PetData d = pl.pets.active(player.getUniqueId());
        if (d == null) return "";
        return switch (id) {
            case "name" -> d.name;
            case "level" -> String.valueOf(d.level);
            case "xp" -> String.valueOf((int) d.xp);
            case "xp_needed" -> String.valueOf((int) d.xpNeeded(pl));
            case "type" -> d.type;
            case "behavior" -> d.behavior.name().toLowerCase();
            case "hunger" -> String.valueOf((int) d.hunger);
            case "points" -> String.valueOf(d.points);
            default -> null;
        };
    }
}
