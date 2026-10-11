package dev.omnipets;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Đa ngôn ngữ: mỗi file plugins/OmniPets/lang/<mã>.yml là một ngôn ngữ. Thiếu key -> en -> vi. */
public class Lang {
    /** 20 mã ngôn ngữ dự kiến (chỉ vi, en có sẵn; thả thêm file <mã>.yml vào thư mục lang/ để bật). */
    public static final String[] CODES = {"vi", "en", "zh_CN", "ja", "ko", "es", "fr", "de", "pt_BR", "ru",
            "id", "th", "tr", "pl", "it", "nl", "ar", "hi", "ms", "fil"};

    private final OmniPets pl;
    private final Map<String, YamlConfiguration> map = new HashMap<>();
    private final Map<UUID, String> players = new HashMap<>();
    private File pfile;
    private YamlConfiguration py;

    public Lang(OmniPets pl) {
        this.pl = pl;
    }

    public void load() {
        map.clear();
        players.clear();
        File dir = new File(pl.getDataFolder(), "lang");
        dir.mkdirs();
        for (String c : new String[]{"vi", "en"}) {
            if (!new File(dir, c + ".yml").exists()) pl.saveResource("lang/" + c + ".yml", false);
        }
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (fs != null) for (File f : fs) map.put(f.getName().substring(0, f.getName().length() - 4), YamlConfiguration.loadConfiguration(f));
        pfile = new File(pl.getDataFolder(), "playerlang.yml");
        py = YamlConfiguration.loadConfiguration(pfile);
        for (String k : py.getKeys(false)) {
            try {
                players.put(UUID.fromString(k), py.getString(k));
            } catch (Exception ignored) {
            }
        }
    }

    public Set<String> available() {
        return new TreeSet<>(map.keySet());
    }

    public boolean has(String code) {
        return map.containsKey(code);
    }

    public String codeOf(CommandSender s) {
        if (s instanceof Player p) {
            String c = players.get(p.getUniqueId());
            if (c != null && map.containsKey(c)) return c;
        }
        return pl.getConfig().getString("settings.language", "vi");
    }

    public void set(Player p, String code) {
        players.put(p.getUniqueId(), code);
        py.set(p.getUniqueId().toString(), code);
        try {
            py.save(pfile);
        } catch (IOException ignored) {
        }
    }

    public String get(CommandSender s, String key, Object... args) {
        String v = null;
        YamlConfiguration y = map.get(codeOf(s));
        if (y != null) v = y.getString(key);
        if (v == null && map.get("en") != null) v = map.get("en").getString(key);
        if (v == null && map.get("vi") != null) v = map.get("vi").getString(key);
        if (v == null) return key;
        for (int i = 0; i < args.length; i++) v = v.replace("{" + i + "}", String.valueOf(args[i]));
        return v;
    }
}
