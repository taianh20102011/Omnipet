package dev.omnipets;

import org.bukkit.Material;

public enum Behavior {
    AGGRESSIVE("&c⚔ Aggressive", "Tự động tấn công quái hostile gần chủ nhân.", Material.IRON_SWORD),
    FARM("&aFarm", "Thu hoạch, trồng lại và thúc cây trồng quanh chủ nhân.", Material.IRON_HOE),
    PROTECT("&9Protect", "Chỉ tấn công kẻ đánh / đang nhắm vào chủ nhân.", Material.SHIELD),
    FRIENDLY("&e❤ Friendly", "Đi theo làm cảnh, không chiến đấu.", Material.POPPY),
    AURA_FARMING("&d✦ Aura Farming", "Hào quang buff chủ nhân, thiêu quái xung quanh và tích XP.", Material.BEACON);

    public final String label, desc;
    public final Material icon;

    Behavior(String label, String desc, Material icon) {
        this.label = label;
        this.desc = desc;
        this.icon = icon;
    }

    public static Behavior parse(String s) {
        if (s == null) return null;
        String n = s.toUpperCase().replace("-", "").replace("_", "").replace(" ", "");
        for (Behavior b : values()) if (b.name().replace("_", "").equals(n)) return b;
        return null;
    }
}
