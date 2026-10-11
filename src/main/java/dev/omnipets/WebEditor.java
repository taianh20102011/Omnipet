package dev.omnipets;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Web editor: /omnipets editor -> link có token 30 phút.
 * Tab Skill trees / Effects (giao diện trực quan, kiểm tra dữ liệu phía server) + tab file thô.
 */
public class WebEditor {
    private static final Gson GSON = new Gson();
    private static final int MAX_BODY = 512 * 1024;

    private final OmniPets pl;
    private HttpServer server;
    private volatile String token = "";
    private volatile long expires;
    private String page;

    public WebEditor(OmniPets pl) {
        this.pl = pl;
    }

    public synchronized String open() throws IOException {
        String host = pl.getConfig().getString("web.host", "127.0.0.1");
        int port = pl.getConfig().getInt("web.port", 8765);
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
        }
        token = UUID.randomUUID().toString().replace("-", "");
        expires = System.currentTimeMillis() + 30 * 60_000L;
        String base = pl.getConfig().getString("web.public-url", "");
        if (base == null || base.isBlank()) base = "http://" + host + ":" + port;
        return base.replaceAll("/+$", "") + "/?t=" + token;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------- định tuyến ----------
    private void handle(HttpExchange x) throws IOException {
        try {
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            if (!token.equals(q.get("t")) || System.currentTimeMillis() > expires) {
                send(x, 403, "text/plain; charset=utf-8", "Token không hợp lệ hoặc đã hết hạn. Gõ /omnipets editor trong game để lấy link mới.");
                return;
            }
            String method = x.getRequestMethod();
            switch (x.getRequestURI().getPath()) {
                case "/" -> send(x, 200, "text/html; charset=utf-8", page());
                case "/api/meta" -> json(x, 200, meta());
                case "/api/trees" -> json(x, 200, listTrees());
                case "/api/tree" -> tree(x, q.get("id"), method);
                case "/api/effects" -> effects(x, method);
                case "/api/animations" -> animations(x, method);
                case "/list" -> json(x, 200, rawList());
                case "/file" -> {
                    Path f = resolve(q.get("f"));
                    if (f == null || !Files.exists(f)) send(x, 404, "text/plain", "Not found");
                    else send(x, 200, "text/plain; charset=utf-8", Files.readString(f));
                }
                case "/save" -> {
                    Path f = resolve(q.get("f"));
                    if (!method.equals("POST") || f == null) {
                        send(x, 400, "text/plain; charset=utf-8", "Yêu cầu không hợp lệ");
                        return;
                    }
                    String body = body(x);
                    try {
                        new YamlConfiguration().loadFromString(body);
                    } catch (Exception ex) {
                        send(x, 400, "text/plain; charset=utf-8", "YAML lỗi: " + ex.getMessage());
                        return;
                    }
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, body);
                    reload();
                    send(x, 200, "text/plain; charset=utf-8", "Đã lưu & reload");
                }
                default -> send(x, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            json(x, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void reload() {
        Bukkit.getScheduler().runTask(pl, pl::reloadAll);
    }

    private String page() throws IOException {
        if (page == null) {
            try (var in = pl.getResource("web/editor.html")) {
                page = in == null ? "editor.html missing" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return page;
    }

    // ---------- meta ----------
    @SuppressWarnings("deprecation")
    private Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(SkillEffects.BUILTIN);
        keys.addAll(pl.fx.defs.keySet());
        m.put("effectKeys", keys);
        Map<String, Object> schema = new LinkedHashMap<>();
        SkillEffects.SCHEMA.forEach((t, fs) -> {
            List<Map<String, String>> l = new ArrayList<>();
            for (String[] f : fs) l.add(Map.of("key", f[0], "label", f[1], "def", f[2], "kind", f[3]));
            schema.put(t, l);
        });
        m.put("schema", schema);
        List<String> mats = new ArrayList<>();
        for (Material mt : Material.values()) if (mt.isItem() && !mt.name().startsWith("LEGACY_")) mats.add(mt.name());
        m.put("materials", mats);
        List<String> at = new ArrayList<>();
        try {
            for (var a : Registry.ATTRIBUTE) at.add(a.getKey().getKey());
        } catch (Throwable ignored) {
        }
        m.put("attributes", at);
        List<String> po = new ArrayList<>();
        try {
            for (PotionEffectType t : PotionEffectType.values()) po.add(t.getKey().getKey());
        } catch (Throwable ignored) {
        }
        m.put("potions", po);
        List<String> pa = new ArrayList<>();
        try {
            for (Particle p : Particle.values()) pa.add(p.name());
        } catch (Throwable ignored) {
        }
        m.put("particles", pa);
        m.put("animations", new ArrayList<>(pl.anim.anims.keySet()));
        List<String> sn = new ArrayList<>();
        try {
            for (var s : Registry.SOUNDS) sn.add(s.getKey().getKey());
        } catch (Throwable ignored) {
        }
        m.put("sounds", sn);
        return m;
    }

    // ---------- skill trees ----------
    private List<Map<String, Object>> listTrees() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] fs = new File(pl.getDataFolder(), "skilltrees").listFiles((d, n) -> n.endsWith(".yml"));
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String id = f.getName().substring(0, f.getName().length() - 4);
            ConfigurationSection ns = y.getConfigurationSection("nodes");
            out.add(Map.of("id", id, "name", y.getString("name", id), "nodes", ns == null ? 0 : ns.getKeys(false).size()));
        }
        return out;
    }

    private Map<String, Object> readTree(String id, File f) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", y.getString("name", id));
        m.put("icon", y.getString("icon", "BOOK"));
        List<Map<String, Object>> nodes = new ArrayList<>();
        ConfigurationSection ns = y.getConfigurationSection("nodes");
        if (ns != null) {
            for (String nid : ns.getKeys(false)) {
                ConfigurationSection s = ns.getConfigurationSection(nid);
                if (s == null) continue;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", nid);
                n.put("name", s.getString("name", nid));
                n.put("icon", s.getString("icon", "PAPER"));
                n.put("cost", s.getInt("cost", 1));
                n.put("unlockLevel", s.getInt("unlock-level", 1));
                n.put("requires", s.getStringList("requires"));
                n.put("inherit", s.getString("inherit", ""));
                Map<String, Object> ef = new LinkedHashMap<>();
                ConfigurationSection e = s.getConfigurationSection("effects");
                if (e != null) for (String k : e.getKeys(false)) ef.put(k, e.getDouble(k));
                n.put("effects", ef);
                nodes.add(n);
            }
        }
        m.put("nodes", nodes);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void tree(HttpExchange x, String id, String method) throws Exception {
        if (id == null || !id.matches("[a-z0-9_]{1,40}")) {
            json(x, 400, Map.of("error", "ID cây không hợp lệ (a-z, 0-9, _)."));
            return;
        }
        File f = new File(pl.getDataFolder(), "skilltrees/" + id + ".yml");
        switch (method) {
            case "GET" -> {
                if (!f.exists()) json(x, 404, Map.of("error", "Không có cây này."));
                else json(x, 200, readTree(id, f));
            }
            case "POST" -> {
                Map<String, Object> m = GSON.fromJson(body(x), Map.class);
                String err = m == null ? "Body rỗng" : writeTree(f, m);
                if (err != null) {
                    json(x, 400, Map.of("error", err));
                } else {
                    reload();
                    json(x, 200, Map.of("ok", true));
                }
            }
            case "DELETE" -> {
                if (f.exists()) Files.move(f.toPath(), new File(f.getParentFile(), id + ".yml.deleted").toPath(), StandardCopyOption.REPLACE_EXISTING);
                reload();
                json(x, 200, Map.of("ok", true));
            }
            default -> json(x, 405, Map.of("error", "Method not allowed"));
        }
    }

    @SuppressWarnings("unchecked")
    private String writeTree(File f, Map<String, Object> m) throws IOException {
        String name = str(m.get("name"));
        if (name.isBlank() || name.length() > 64) return "Tên cây không hợp lệ (1-64 ký tự).";
        String icon = str(m.get("icon")).toUpperCase();
        if (Material.matchMaterial(icon) == null) return "Icon cây không hợp lệ: " + icon;
        if (!(m.get("nodes") instanceof List<?> nl) || nl.isEmpty()) return "Cây cần ít nhất 1 node.";
        if (nl.size() > 45) return "Tối đa 45 node mỗi cây (giới hạn menu).";
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Object o : nl) {
            if (!(o instanceof Map<?, ?> nm)) return "Node không hợp lệ.";
            Map<String, Object> n = (Map<String, Object>) nm;
            String nid = str(n.get("id"));
            if (!nid.matches("[a-z0-9_]{1,32}")) return "ID node không hợp lệ: '" + nid + "' (a-z, 0-9, _).";
            if (nodes.put(nid, n) != null) return "Trùng ID node: " + nid;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", name);
        y.set("icon", icon);
        Map<String, List<String>> reqs = new HashMap<>();
        for (var e : nodes.entrySet()) {
            String nid = e.getKey();
            Map<String, Object> n = e.getValue();
            String p = "nodes." + nid + ".";
            String nn = str(n.get("name"));
            if (nn.isBlank() || nn.length() > 64) return "Tên node '" + nid + "' không hợp lệ (1-64 ký tự).";
            String ic = str(n.get("icon")).toUpperCase();
            if (Material.matchMaterial(ic) == null) return "Icon node '" + nid + "' không hợp lệ: " + ic;
            int cost = (int) toD(n.get("cost")), lvl = (int) toD(n.get("unlockLevel"));
            if (cost < 0 || cost > 99 || lvl < 1 || lvl > 1000) return "Giá (0-99) hoặc cấp (1-1000) của '" + nid + "' ngoài khoảng cho phép.";
            List<String> req = new ArrayList<>();
            if (n.get("requires") instanceof List<?> rl) {
                for (Object r : rl) {
                    String rs = str(r);
                    if (!nodes.containsKey(rs)) return "Node '" + nid + "' yêu cầu node không tồn tại: " + rs;
                    if (rs.equals(nid)) return "Node '" + nid + "' không thể yêu cầu chính nó.";
                    req.add(rs);
                }
            }
            String inh = str(n.get("inherit"));
            if (!inh.isBlank() && !nodes.containsKey(inh)) return "inherit của '" + nid + "' không tồn tại: " + inh;
            y.set(p + "name", nn);
            y.set(p + "icon", ic);
            y.set(p + "cost", cost);
            y.set(p + "unlock-level", lvl);
            if (!req.isEmpty()) y.set(p + "requires", req);
            if (!inh.isBlank()) y.set(p + "inherit", inh);
            if (n.get("effects") instanceof Map<?, ?> em) {
                for (var ee : em.entrySet()) {
                    String k = str(ee.getKey());
                    if (!pl.fx.known(k)) return "Effect không tồn tại: '" + k + "' (node " + nid + "). Tạo trong tab Effects trước.";
                    double v = toD(ee.getValue());
                    if (!Double.isFinite(v) || Math.abs(v) > 1000) return "Giá trị effect '" + k + "' không hợp lệ (|giá trị| ≤ 1000).";
                    y.set(p + "effects." + k, v);
                }
            }
            reqs.put(nid, req);
        }
        // phát hiện vòng lặp yêu cầu
        Map<String, Integer> state = new HashMap<>();
        for (String nid : reqs.keySet()) if (cycle(nid, reqs, state)) return "Có vòng lặp yêu cầu quanh node '" + nid + "'.";
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), y.saveToString());
        return null;
    }

    private boolean cycle(String n, Map<String, List<String>> reqs, Map<String, Integer> st) {
        Integer s = st.get(n);
        if (s != null) return s == 1;
        st.put(n, 1);
        for (String r : reqs.getOrDefault(n, List.of())) if (cycle(r, reqs, st)) return true;
        st.put(n, 2);
        return false;
    }

    // ---------- effects ----------
    @SuppressWarnings("unchecked")
    private void effects(HttpExchange x, String method) throws Exception {
        if (method.equals("GET")) {
            Map<String, Object> defs = new LinkedHashMap<>();
            for (SkillEffects.Def d : pl.fx.defs.values()) defs.put(d.id, Map.of("label", d.label, "type", d.type, "params", d.params));
            json(x, 200, Map.of("builtin", SkillEffects.BUILTIN, "defs", defs));
            return;
        }
        if (!method.equals("POST")) {
            json(x, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, Object> m = GSON.fromJson(body(x), Map.class);
        YamlConfiguration y = new YamlConfiguration();
        y.createSection("effects");
        if (m != null && m.get("defs") instanceof Map<?, ?> dm) {
            for (var e : dm.entrySet()) {
                String id = str(e.getKey());
                if (!id.matches("[a-z0-9_-]{1,32}") || SkillEffects.BUILTIN.contains(id)) {
                    json(x, 400, Map.of("error", "ID effect không hợp lệ hoặc trùng effect có sẵn: " + id));
                    return;
                }
                if (!(e.getValue() instanceof Map<?, ?> dv)) continue;
                String type = str(dv.get("type")).toUpperCase();
                List<String[]> schema = SkillEffects.SCHEMA.get(type);
                String label = str(dv.get("label"));
                if (schema == null || label.isBlank() || label.length() > 64) {
                    json(x, 400, Map.of("error", "Effect '" + id + "': type hoặc nhãn không hợp lệ."));
                    return;
                }
                String base = "effects." + id + ".";
                y.set(base + "label", label);
                y.set(base + "type", type);
                Map<?, ?> pm = dv.get("params") instanceof Map<?, ?> mm ? mm : Map.of();
                for (String[] f : schema) {
                    Object raw = pm.get(f[0]);
                    if (raw == null || (raw instanceof String s && s.isBlank())) {
                        if (f[0].equals("particle") || f[0].equals("animation")) continue;
                        raw = f[2];
                    }
                    if (f[3].equals("num")) {
                        double v = toD(raw);
                        if (!Double.isFinite(v) || Math.abs(v) > 1_000_000) {
                            json(x, 400, Map.of("error", "Effect '" + id + "': '" + f[0] + "' phải là số hợp lệ."));
                            return;
                        }
                        y.set(base + f[0], v);
                    } else {
                        String sv = str(raw);
                        boolean ok = switch (f[0]) {
                            case "attribute" -> SkillEffects.attr(sv) != null;
                            case "potion" -> SkillEffects.potion(sv) != null;
                            case "particle" -> SkillEffects.particle(sv) != null;
                            case "animation" -> sv.isBlank() || pl.anim.anims.containsKey(sv);
                            default -> true;
                        };
                        if (!ok) {
                            json(x, 400, Map.of("error", "Effect '" + id + "': " + f[0] + " không hợp lệ: " + sv));
                            return;
                        }
                        y.set(base + f[0], sv);
                    }
                }
            }
        }
        Files.writeString(new File(pl.getDataFolder(), "effects.yml").toPath(), y.saveToString());
        reload();
        json(x, 200, Map.of("ok", true));
    }

    // ---------- animations ----------
    @SuppressWarnings("unchecked")
    private void animations(HttpExchange x, String method) throws Exception {
        if (method.equals("GET")) {
            Map<String, Object> an = new LinkedHashMap<>();
            for (Animator.Anim a : pl.anim.anims.values()) an.put(a.id, a.toMap());
            json(x, 200, Map.of("anims", an, "events", pl.anim.events, "shapes", Animator.SHAPES, "eventList", Animator.EVENTS));
            return;
        }
        if (!method.equals("POST")) {
            json(x, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, Object> m = GSON.fromJson(body(x), Map.class);
        YamlConfiguration y = new YamlConfiguration();
        y.createSection("animations");
        Set<String> ids = new HashSet<>();
        if (m != null && m.get("anims") instanceof Map<?, ?> am) {
            for (var e : am.entrySet()) {
                String id = str(e.getKey());
                if (!id.matches("[a-z0-9_-]{1,32}")) {
                    json(x, 400, Map.of("error", "ID animation không hợp lệ: " + id));
                    return;
                }
                if (!(e.getValue() instanceof Map<?, ?> av)) continue;
                String err = Animator.validate(av, y, "animations." + id + ".");
                if (err != null) {
                    json(x, 400, Map.of("error", "Animation '" + id + "': " + err));
                    return;
                }
                ids.add(id);
            }
        }
        if (m != null && m.get("events") instanceof Map<?, ?> em) {
            for (var e : em.entrySet()) {
                String ev = str(e.getKey()), an = str(e.getValue());
                if (an.isBlank()) continue;
                if (!Animator.EVENTS.contains(ev) || !ids.contains(an)) {
                    json(x, 400, Map.of("error", "Sự kiện '" + ev + "' không hợp lệ hoặc dùng animation không tồn tại: " + an));
                    return;
                }
                y.set("events." + ev, an);
            }
        }
        Files.writeString(new File(pl.getDataFolder(), "animations.yml").toPath(), y.saveToString());
        reload();
        json(x, 200, Map.of("ok", true));
    }

    // ---------- file thô ----------
    private Path resolve(String name) {
        if (name == null || !name.matches("config\\.yml|effects\\.yml|animations\\.yml|skilltrees/[a-zA-Z0-9_\\-]+\\.yml|lang/[a-zA-Z_\\-]+\\.yml")) return null;
        return pl.getDataFolder().toPath().resolve(name);
    }

    private List<String> rawList() {
        List<String> l = new ArrayList<>(List.of("config.yml", "effects.yml", "animations.yml"));
        for (String dir : new String[]{"lang", "skilltrees"}) {
            File[] fs = new File(pl.getDataFolder(), dir).listFiles((d, n) -> n.endsWith(".yml"));
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) l.add(dir + "/" + f.getName());
        }
        return l;
    }

    // ---------- tiện ích ----------
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double toD(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(str(o));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String body(HttpExchange x) throws IOException {
        byte[] raw = x.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new IOException("Dữ liệu quá lớn");
        return new String(raw, StandardCharsets.UTF_8);
    }

    private Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void json(HttpExchange x, int code, Object o) throws IOException {
        send(x, code, "application/json; charset=utf-8", GSON.toJson(o));
    }

    private void send(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
        x.close();
    }
}
