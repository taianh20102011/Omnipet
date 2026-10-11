package dev.omnipets;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;

/** Hiệu ứng kỹ năng tùy chỉnh theo từng server, định nghĩa trong plugins/OmniPets/effects.yml. */
public class SkillEffects {
    /** Hiệu ứng có sẵn trong code (không sửa được trong effects.yml). */
    public static final List<String> BUILTIN = List.of("health", "damage", "speed", "heal-owner", "fire-hit", "slow-hit", "auto-pickup", "owner-resist");

    /** type -> danh sách tham số {key, nhãn, mặc định, kiểu(num|str)} */
    public static final Map<String, List<String[]>> SCHEMA = new LinkedHashMap<>();

    private static String[] f(String k, String label, String def, String kind) {
        return new String[]{k, label, def, kind};
    }

    static {
        SCHEMA.put("OWNER_ATTRIBUTE", List.of(f("attribute", "Attribute của chủ (armor, luck, max_health...)", "armor", "str"), f("per-value", "Cộng mỗi 1 điểm giá trị", "1", "num")));
        SCHEMA.put("PET_ATTRIBUTE", List.of(f("attribute", "Attribute của pet", "knockback_resistance", "str"), f("per-value", "Cộng mỗi 1 điểm giá trị", "0.1", "num")));
        SCHEMA.put("OWNER_POTION", List.of(f("potion", "Hiệu ứng thuốc cho chủ", "night_vision", "str"), f("amplifier", "Bậc (0 = I)", "0", "num"),
                f("seconds", "Thời lượng (giây)", "8", "num"), f("interval", "Chu kỳ áp dụng (giây)", "5", "num")));
        SCHEMA.put("HIT_POTION", List.of(f("potion", "Hiệu ứng lên mục tiêu", "poison", "str"), f("amplifier", "Bậc (0 = I)", "0", "num"), f("per-value", "Giây mỗi 1 điểm giá trị", "1", "num")));
        SCHEMA.put("AURA_DAMAGE", List.of(f("radius", "Bán kính", "4", "num"), f("interval", "Chu kỳ (giây)", "2", "num"),
                f("per-value", "Sát thương mỗi 1 điểm giá trị", "1", "num"), f("particle", "Particle (tùy chọn)", "CRIT", "str")));
        SCHEMA.put("HEAL_PET_ON_HIT", List.of(f("per-value", "Máu hồi cho pet mỗi đòn / 1 điểm", "1", "num")));
        SCHEMA.put("XP_MULT", List.of(f("per-value", "% XP thêm mỗi 1 điểm", "1", "num")));
        for (String t : List.of("OWNER_POTION", "HIT_POTION", "AURA_DAMAGE", "HEAL_PET_ON_HIT")) {   // loại có thời điểm kích hoạt
            List<String[]> l = new ArrayList<>(SCHEMA.get(t));
            l.add(f("animation", "Animation khi kích hoạt (id trong tab Animations, tùy chọn)", "", "str"));
            SCHEMA.put(t, l);
        }
    }

    public static class Def {
        public String id, label, type;
        public Map<String, Object> params = new LinkedHashMap<>();

        public double d(String k, double def) {
            Object o = params.get(k);
            return o instanceof Number n ? n.doubleValue() : def;
        }
    }

    private final OmniPets pl;
    /** Thay thế nguyên khối khi reload để luồng web đọc an toàn. */
    public volatile Map<String, Def> defs = new LinkedHashMap<>();

    public SkillEffects(OmniPets pl) {
        this.pl = pl;
    }

    public void load() {
        File f = new File(pl.getDataFolder(), "effects.yml");
        if (!f.exists()) pl.saveResource("effects.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<String, Def> m = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection("effects");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(id);
                if (e == null) continue;
                String type = e.getString("type", "").toUpperCase();
                if (!SCHEMA.containsKey(type) || BUILTIN.contains(id.toLowerCase())) {
                    pl.getLogger().warning("effects.yml: bỏ qua effect '" + id + "' (type/id không hợp lệ)");
                    continue;
                }
                Def d = new Def();
                d.id = id.toLowerCase();
                d.label = e.getString("label", id);
                d.type = type;
                for (String k : e.getKeys(false)) if (!k.equals("label") && !k.equals("type")) d.params.put(k, e.get(k));
                m.put(d.id, d);
            }
        }
        defs = m;
    }

    public boolean known(String id) {
        return BUILTIN.contains(id) || defs.containsKey(id);
    }

    public static Attribute attr(String n) {
        if (n == null || n.isBlank() || n.equals("null")) return null;
        String k = n.toLowerCase().replace("generic_", "").replace("player_", "");
        try {
            return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static PotionEffectType potion(String n) {
        if (n == null || n.isBlank() || n.equals("null")) return null;
        try {
            PotionEffectType t = PotionEffectType.getByKey(NamespacedKey.minecraft(n.toLowerCase()));
            return t != null ? t : PotionEffectType.getByName(n.toUpperCase());
        } catch (Throwable t) {
            return null;
        }
    }

    public static Particle particle(String n) {
        if (n == null || n.isBlank() || n.equals("null")) return null;
        try {
            return Particle.valueOf(n.toUpperCase());
        } catch (Throwable t) {
            return null;
        }
    }
}
