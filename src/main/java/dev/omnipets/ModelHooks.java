package dev.omnipets;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Hook mô hình 3D bằng reflection (không cần jar của các plugin khi compile).
 * Đã viết theo API công khai của ModelEngine R4, MythicMobs 5 và ItemsAdder; BetterModel là best-effort.
 */
public final class ModelHooks {
    private ModelHooks() {
    }

    private static String provider(ConfigurationSection t) {
        return t == null ? "none" : t.getString("model.provider", "none").toLowerCase();
    }

    public static String modelFor(ConfigurationSection t, int level) {
        String id = t.getString("model.id");
        ConfigurationSection st = t.getConfigurationSection("model.stages");
        if (st != null) {
            int best = -1;
            for (String k : st.getKeys(false)) {
                try {
                    int lv = Integer.parseInt(k);
                    if (lv <= level && lv > best) {
                        best = lv;
                        id = st.getString(k);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return id;
    }

    /** MythicMobs / ItemsAdder: spawn thực thể custom làm pet. Trả null nếu không dùng provider này. */
    public static Entity spawnBase(OmniPets pl, ConfigurationSection t, Location loc) {
        String prov = provider(t);
        String id = t.getString("model.id");
        if (id == null) return null;
        try {
            if (prov.equals("mythicmobs")) {
                Object inst = Class.forName("io.lumine.mythic.bukkit.MythicBukkit").getMethod("inst").invoke(null);
                Object helper = inst.getClass().getMethod("getAPIHelper").invoke(inst);
                return (Entity) helper.getClass().getMethod("spawnMythicMob", String.class, Location.class).invoke(helper, id, loc);
            }
            if (prov.equals("itemsadder")) {
                Object ce = Class.forName("dev.lone.itemsadder.api.CustomEntity").getMethod("spawn", String.class, Location.class).invoke(null, id, loc);
                return ce == null ? null : (Entity) ce.getClass().getMethod("getEntity").invoke(ce);
            }
        } catch (Throwable ex) {
            pl.getLogger().warning("Hook " + prov + " lỗi khi spawn '" + id + "': " + ex);
        }
        return null;
    }

    /** ModelEngine / BetterModel: gắn model lên mob vanilla, đổi model khi tiến hóa. */
    public static void apply(OmniPets pl, PetData p) {
        Mob m = p.entity;
        ConfigurationSection t = pl.pets.type(p.type);
        if (m == null || t == null) return;
        String prov = provider(t);
        if (!prov.equals("modelengine") && !prov.equals("bettermodel")) return;
        String id = modelFor(t, p.level);
        if (id == null || id.equals(p.modelId)) return;
        try {
            boolean ok = prov.equals("modelengine") ? modelEngine(m, t, p, id) : betterModel(m, id);
            if (ok) p.modelId = id;
            else pl.getLogger().warning("Không gắn được model '" + id + "' bằng " + prov);
        } catch (Throwable ex) {
            pl.getLogger().warning("Hook " + prov + " lỗi: " + ex);
        }
    }

    private static boolean modelEngine(Mob m, ConfigurationSection t, PetData p, String id) throws Exception {
        Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
        Class<?> mec = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
        Object me = api.getMethod("getOrCreateModeledEntity", Entity.class).invoke(null, m);
        if (p.modelId != null) {
            try {
                mec.getMethod("removeModel", String.class).invoke(me, p.modelId);
            } catch (NoSuchMethodException ignored) {
            }
        }
        Object active = api.getMethod("createActiveModel", String.class).invoke(null, id);
        if (active == null) return false;
        boolean added = false;
        for (Method mm : mec.getMethods()) {
            if (mm.getName().equals("addModel") && mm.getParameterCount() == 2) {
                mm.invoke(me, active, true);
                added = true;
                break;
            }
        }
        if (added && t.getBoolean("model.hide-base", true)) {
            try {
                mec.getMethod("setBaseEntityVisible", boolean.class).invoke(me, false);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return added;
    }

    private static boolean betterModel(Mob m, String id) throws Exception {
        Class<?> bm = Class.forName("kr.toxicity.model.api.BetterModel");
        Object opt = bm.getMethod("model", String.class).invoke(null, id);
        if (!(opt instanceof Optional<?> o) || o.isEmpty()) return false;
        Object renderer = o.get();
        for (Method mm : renderer.getClass().getMethods()) {
            if ((mm.getName().equals("getOrCreate") || mm.getName().equals("create")) && mm.getParameterCount() == 1
                    && mm.getParameterTypes()[0].isAssignableFrom(m.getClass())) {
                mm.invoke(renderer, m);
                m.setInvisible(true);
                return true;
            }
        }
        return false;
    }
}
