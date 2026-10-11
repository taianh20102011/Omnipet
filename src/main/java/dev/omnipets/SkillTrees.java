package dev.omnipets;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/** Cây kỹ năng đọc từ plugins/OmniPets/skilltrees/*.yml (thêm file = thêm cây). */
public class SkillTrees {
    public static class Node {
        public String key, id, tree, name, inherit;
        public int cost = 1, unlockLevel = 1;
        public List<String> requires = new ArrayList<>();
        public Map<String, Double> effects = new LinkedHashMap<>();
        public Material icon = Material.PAPER;
    }

    private final OmniPets pl;
    public final Map<String, List<Node>> trees = new LinkedHashMap<>();
    public final Map<String, String> names = new HashMap<>();
    public final Map<String, Material> icons = new HashMap<>();
    private final Map<String, Node> nodes = new HashMap<>();

    public SkillTrees(OmniPets pl) {
        this.pl = pl;
    }

    public void load() {
        trees.clear();
        names.clear();
        icons.clear();
        nodes.clear();
        File dir = new File(pl.getDataFolder(), "skilltrees");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            String tree = f.getName().substring(0, f.getName().length() - 4).toLowerCase();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            names.put(tree, y.getString("name", tree));
            Material ic = Material.matchMaterial(y.getString("icon", "BOOK"));
            icons.put(tree, ic == null ? Material.BOOK : ic);
            ConfigurationSection ns = y.getConfigurationSection("nodes");
            List<Node> list = new ArrayList<>();
            if (ns != null) {
                for (String id : ns.getKeys(false)) {
                    ConfigurationSection s = ns.getConfigurationSection(id);
                    if (s == null) continue;
                    Node n = new Node();
                    n.id = id;
                    n.tree = tree;
                    n.key = tree + "." + id;
                    n.name = s.getString("name", id);
                    n.cost = s.getInt("cost", 1);
                    n.unlockLevel = s.getInt("unlock-level", 1);
                    n.requires = s.getStringList("requires");
                    n.inherit = s.getString("inherit");
                    Material m = Material.matchMaterial(s.getString("icon", "PAPER"));
                    if (m != null) n.icon = m;
                    ConfigurationSection ef = s.getConfigurationSection("effects");
                    if (ef != null) for (String k : ef.getKeys(false)) n.effects.put(k, ef.getDouble(k));
                    list.add(n);
                    nodes.put(n.key, n);
                }
            }
            trees.put(tree, list);
        }
    }

    public Node get(String key) {
        return nodes.get(key);
    }

    /** Tổng hiệu ứng của các node đã mở. Node có "inherit" thay thế (che) node cha, không cộng dồn. */
    public Map<String, Double> total(PetData p) {
        Set<String> shadow = new HashSet<>();
        for (String k : p.unlocked) {
            Node n = nodes.get(k);
            if (n != null && n.inherit != null) shadow.add(n.tree + "." + n.inherit);
        }
        Map<String, Double> out = new HashMap<>();
        for (String k : p.unlocked) {
            if (shadow.contains(k)) continue;
            Node n = nodes.get(k);
            if (n == null) continue;
            n.effects.forEach((e, v) -> out.merge(e, v, Double::sum));
        }
        return out;
    }

    /** @return null nếu mở khóa thành công, ngược lại là lý do thất bại. */
    public String tryUnlock(PetData p, Node n) {
        if (p.unlocked.contains(n.key)) return "Đã mở khóa rồi.";
        if (p.level < n.unlockLevel) return "Cần pet cấp " + n.unlockLevel + ".";
        if (p.points < n.cost) return "Không đủ điểm kỹ năng (cần " + n.cost + ").";
        for (String r : n.requires) {
            if (!p.unlocked.contains(n.tree + "." + r)) return "Cần mở khóa node '" + r + "' trước.";
        }
        p.points -= n.cost;
        p.unlocked.add(n.key);
        return null;
    }
}
