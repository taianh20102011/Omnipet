package dev.omnipets;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

/** Animation hạt + âm thanh, định nghĩa trong animations.yml, gắn vào sự kiện hoặc vào effect kỹ năng. */
public class Animator {
    public static final List<String> SHAPES = List.of("RING", "SPIRAL", "HELIX", "BURST", "SPHERE", "PILLAR", "BEAM");
    public static final List<String> EVENTS = List.of("summon", "store", "level-up", "evolve", "tame", "unlock", "hit",
            "fire-hit", "slow-hit", "heal-owner", "aura-pulse", "harvest", "mount");
    /** {khóa, min, max, mặc định} */
    private static final Object[][] NUMS = {{"count", 1.0, 200.0, 20.0}, {"duration", 0.0, 200.0, 20.0}, {"radius", 0.0, 32.0, 1.5},
            {"height", 0.0, 32.0, 2.0}, {"speed", 0.0, 5.0, 0.0}, {"volume", 0.0, 4.0, 1.0}, {"pitch", 0.1, 2.0, 1.0}, {"size", 0.1, 10.0, 1.0}};

    public static class Anim {
        public String id, shape, particle, sound = "", color = "";
        public int count, duration;
        public double radius, height, speed, volume, pitch, size;
        Particle p;
        Color rgb;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shape", shape);
            m.put("particle", particle);
            m.put("count", count);
            m.put("duration", duration);
            m.put("radius", radius);
            m.put("height", height);
            m.put("speed", speed);
            m.put("volume", volume);
            m.put("pitch", pitch);
            m.put("size", size);
            m.put("sound", sound);
            m.put("color", color);
            return m;
        }
    }

    private final OmniPets pl;
    public volatile Map<String, Anim> anims = new LinkedHashMap<>();
    public volatile Map<String, String> events = new LinkedHashMap<>();
    private int active;

    public Animator(OmniPets pl) {
        this.pl = pl;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @SuppressWarnings("deprecation")
    public static String particleError(String name) {
        Particle p = SkillEffects.particle(name);
        if (p == null) return "không hợp lệ: " + name;
        try {
            if (p.getDataType() != Void.class && p != Particle.DUST) return name + " cần dữ liệu đặc biệt (chưa hỗ trợ, trừ DUST)";
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void load() {
        File f = new File(pl.getDataFolder(), "animations.yml");
        if (!f.exists()) pl.saveResource("animations.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<String, Anim> m = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection("animations");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(id);
                if (e == null) continue;
                Anim a = new Anim();
                a.id = id.toLowerCase();
                a.shape = e.getString("shape", "RING").toUpperCase();
                a.particle = e.getString("particle", "END_ROD").toUpperCase();
                String pe = particleError(a.particle);
                if (!SHAPES.contains(a.shape) || pe != null) {
                    pl.getLogger().warning("animations.yml: bỏ qua '" + id + "' (" + (pe != null ? "particle " + pe : "shape không hợp lệ") + ")");
                    continue;
                }
                a.p = SkillEffects.particle(a.particle);
                a.count = (int) clamp(e.getDouble("count", 20), 1, 200);
                a.duration = (int) clamp(e.getDouble("duration", 20), 0, 200);
                a.radius = clamp(e.getDouble("radius", 1.5), 0, 32);
                a.height = clamp(e.getDouble("height", 2), 0, 32);
                a.speed = clamp(e.getDouble("speed", 0), 0, 5);
                a.volume = clamp(e.getDouble("volume", 1), 0, 4);
                a.pitch = clamp(e.getDouble("pitch", 1), 0.1, 2);
                a.size = clamp(e.getDouble("size", 1), 0.1, 10);
                a.sound = e.getString("sound", "");
                a.color = e.getString("color", "");
                if (a.color.matches("#[0-9a-fA-F]{6}")) a.rgb = Color.fromRGB(Integer.parseInt(a.color.substring(1), 16));
                m.put(a.id, a);
            }
        }
        Map<String, String> ev = new LinkedHashMap<>();
        ConfigurationSection es = y.getConfigurationSection("events");
        if (es != null) for (String k : es.getKeys(false)) {
            String v = es.getString(k, "");
            if (!v.isBlank()) ev.put(k, v);
        }
        anims = m;
        events = ev;
    }

    /** Dùng cho validate từ web: kiểm tra và ghi một animation vào y. @return lỗi hoặc null */
    public static String validate(Map<?, ?> v, YamlConfiguration y, String base) {
        String shape = s(v.get("shape")).toUpperCase();
        if (!SHAPES.contains(shape)) return "shape không hợp lệ";
        String part = s(v.get("particle")).toUpperCase();
        String pe = particleError(part);
        if (pe != null) return "particle " + pe;
        y.set(base + "shape", shape);
        y.set(base + "particle", part);
        for (Object[] nf : NUMS) {
            String k = (String) nf[0];
            double lo = (double) nf[1], hi = (double) nf[2], d = (double) nf[3];
            Object raw = v.get(k);
            double val = raw == null || s(raw).isBlank() ? d : num(raw);
            if (!Double.isFinite(val) || val < lo || val > hi) return k + " phải trong khoảng " + lo + " - " + hi;
            y.set(base + k, val);
        }
        String snd = s(v.get("sound"));
        if (!snd.isBlank()) {
            if (!snd.matches("[a-z0-9_./:-]{1,100}")) return "sound không hợp lệ (dạng entity.player.levelup)";
            y.set(base + "sound", snd);
        }
        String col = s(v.get("color"));
        if (!col.isBlank()) {
            if (!col.matches("#[0-9a-fA-F]{6}")) return "color phải dạng #rrggbb";
            y.set(base + "color", col);
        }
        return null;
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(s(o));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---------- phát ----------
    /** Phát animation của sự kiện (ưu tiên pets.<loại>.animations.<sự kiện>). @return true nếu có animation được phát */
    public boolean event(PetData p, String event, Location at, Location from) {
        String id = null;
        if (p != null) {
            ConfigurationSection t = pl.pets.type(p.type);
            if (t != null) id = t.getString("animations." + event);
        }
        if (id == null || id.isBlank()) id = events.get(event);
        if (id == null || id.isBlank()) return false;
        return play(id, at, from);
    }

    /** at = nơi phát; from (tùy chọn) = điểm bắt đầu của BEAM. */
    public boolean play(String id, Location at, Location from) {
        Anim a = anims.get(id);
        if (a == null || at == null || at.getWorld() == null) return false;
        if (!pl.getConfig().getBoolean("animation.enabled", true)) return false;
        if (active >= pl.getConfig().getInt("animation.max-active", 60)) return false;   // chống lag
        if (a.sound != null && !a.sound.isBlank()) {
            try {
                at.getWorld().playSound(at, a.sound, (float) a.volume, (float) a.pitch);
            } catch (Throwable ignored) {
            }
        }
        Location origin = at.clone();
        Location src = from == null ? null : from.clone();
        if (a.duration <= 0) {
            draw(a, origin, src, 1.0);
            return true;
        }
        active++;
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                try {
                    draw(a, origin, src, (double) tick / a.duration);
                } catch (Throwable ex) {
                    cancel();
                    active--;
                    return;
                }
                if (++tick >= a.duration) {
                    cancel();
                    active--;
                }
            }
        }.runTaskTimer(pl, 0L, 1L);
        return true;
    }

    private void draw(Anim a, Location o, Location src, double t) {
        World w = o.getWorld();
        double cx = o.getX(), cy = o.getY() + 0.1, cz = o.getZ();
        int n = a.count;
        switch (a.shape) {
            case "RING" -> {
                double r = a.radius * (0.25 + 0.75 * t);
                for (int i = 0; i < n; i++) {
                    double g = 2 * Math.PI * i / n + t * Math.PI;
                    spawn(a, w, cx + Math.cos(g) * r, cy + a.height * t, cz + Math.sin(g) * r);
                }
            }
            case "SPIRAL", "HELIX" -> {
                int arms = a.shape.equals("HELIX") ? 2 : 1;
                for (int k = 0; k < arms; k++) {
                    for (int i = 0; i < n; i++) {
                        double u = Math.min(1, t + (double) i / n * 0.2);
                        double g = u * Math.PI * 6 + k * Math.PI;
                        spawn(a, w, cx + Math.cos(g) * a.radius, cy + a.height * u, cz + Math.sin(g) * a.radius);
                    }
                }
            }
            case "BURST", "SPHERE" -> {
                double r = a.shape.equals("BURST") ? a.radius * t : a.radius;
                for (int i = 0; i < n; i++) {
                    double y = 1 - 2 * (i + 0.5) / n, rr = Math.sqrt(1 - y * y), ph = i * 2.399963;
                    spawn(a, w, cx + Math.cos(ph) * rr * r, cy + 0.8 + y * r, cz + Math.sin(ph) * rr * r);
                }
            }
            case "PILLAR" -> {
                for (int i = 0; i < n; i++) {
                    double g = 2 * Math.PI * i / n;
                    spawn(a, w, cx + Math.cos(g) * a.radius, cy + a.height * t, cz + Math.sin(g) * a.radius);
                }
            }
            case "BEAM" -> {
                if (src == null || !w.equals(src.getWorld())) return;
                double sx = src.getX(), sy = src.getY() + 1, sz = src.getZ(), ex = o.getX(), ey = o.getY() + 1, ez = o.getZ();
                for (int i = 0; i < n; i++) {
                    double f = n > 1 ? (double) i / (n - 1) : 0.5;
                    spawn(a, w, sx + (ex - sx) * f, sy + (ey - sy) * f, sz + (ez - sz) * f);
                }
            }
            default -> {
            }
        }
    }

    private void spawn(Anim a, World w, double x, double y, double z) {
        if (a.rgb != null && a.p == Particle.DUST) w.spawnParticle(a.p, x, y, z, 1, 0, 0, 0, 0, new Particle.DustOptions(a.rgb, (float) a.size));
        else w.spawnParticle(a.p, x, y, z, 1, 0, 0, 0, a.speed);
    }
}
