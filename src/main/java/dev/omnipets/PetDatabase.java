package dev.omnipets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.*;

/** SQLite (mặc định) hoặc MySQL/MariaDB (database.type: mysql). Tự kết nối lại khi mất kết nối. */
public class PetDatabase {
    private final OmniPets pl;
    private Connection c;
    private boolean mysql;

    public PetDatabase(OmniPets pl) {
        this.pl = pl;
    }

    private void connect() throws Exception {
        if (mysql) {
            ConfigurationSection d = pl.getConfig().getConfigurationSection("database.mysql");
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
            }
            String url = "jdbc:mysql://" + d.getString("host", "localhost") + ":" + d.getInt("port", 3306) + "/" + d.getString("name", "omnipets")
                    + "?useSSL=" + d.getBoolean("ssl", false) + "&characterEncoding=utf8&allowPublicKeyRetrieval=true";
            c = DriverManager.getConnection(url, d.getString("user", "root"), d.getString("password", ""));
        } else {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
            }
            pl.getDataFolder().mkdirs();
            c = DriverManager.getConnection("jdbc:sqlite:" + new File(pl.getDataFolder(), "pets.db").getAbsolutePath());
        }
    }

    private void ensure() throws SQLException {
        try {
            if (c != null && c.isValid(2)) return;
            connect();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    public void init() throws Exception {
        mysql = "mysql".equalsIgnoreCase(pl.getConfig().getString("database.type", "sqlite"));
        connect();
        try (Statement s = c.createStatement()) {
            if (mysql) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS pets(id INT AUTO_INCREMENT PRIMARY KEY, owner VARCHAR(36) NOT NULL,"
                        + " type VARCHAR(64) NOT NULL, name VARCHAR(64) NOT NULL, level INT, xp DOUBLE, hunger DOUBLE, behavior VARCHAR(32),"
                        + " points INT, unlocked TEXT, storage LONGTEXT, active TINYINT, INDEX idx_owner(owner)) DEFAULT CHARSET=utf8mb4");
            } else {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS pets(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL,"
                        + " type TEXT NOT NULL, name TEXT NOT NULL, level INTEGER, xp REAL, hunger REAL, behavior TEXT,"
                        + " points INTEGER, unlocked TEXT, storage TEXT, active INTEGER)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_owner ON pets(owner)");
            }
        }
        pl.getLogger().info("Database: " + (mysql ? "MySQL" : "SQLite"));
    }

    public synchronized void close() {
        try {
            if (c != null) c.close();
        } catch (SQLException ignored) {
        }
    }

    public synchronized List<PetData> load(UUID owner) {
        List<PetData> out = new ArrayList<>();
        try {
            ensure();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pets WHERE owner=? ORDER BY id")) {
                ps.setString(1, owner.toString());
                ResultSet r = ps.executeQuery();
                while (r.next()) {
                    PetData p = new PetData();
                    p.id = r.getInt("id");
                    p.owner = owner;
                    p.type = r.getString("type");
                    p.name = r.getString("name");
                    p.level = r.getInt("level");
                    p.xp = r.getDouble("xp");
                    p.hunger = r.getDouble("hunger");
                    Behavior b = Behavior.parse(r.getString("behavior"));
                    p.behavior = b == null ? Behavior.FRIENDLY : b;
                    p.points = r.getInt("points");
                    String u = r.getString("unlocked");
                    if (u != null && !u.isBlank()) p.unlocked.addAll(Arrays.asList(u.split(",")));
                    String st = r.getString("storage");
                    if (st != null && !st.isBlank()) {
                        try {
                            p.items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(st));
                        } catch (Exception e) {
                            pl.getLogger().warning("Không đọc được kho đồ pet #" + p.id);
                        }
                    }
                    p.active = r.getInt("active") == 1;
                    out.add(p);
                }
            }
        } catch (SQLException e) {
            pl.getLogger().severe("DB load: " + e.getMessage());
        }
        return out;
    }

    public synchronized void insert(PetData p) {
        try {
            ensure();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pets(owner,type,name,level,xp,hunger,behavior,points,unlocked,storage,active) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                fill(ps, p, 1);
                ps.executeUpdate();
                ResultSet k = ps.getGeneratedKeys();
                if (k.next()) p.id = k.getInt(1);
            }
        } catch (SQLException e) {
            pl.getLogger().severe("DB insert: " + e.getMessage());
        }
    }

    public synchronized void save(PetData p) {
        try {
            ensure();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pets SET owner=?,type=?,name=?,level=?,xp=?,hunger=?,behavior=?,points=?,unlocked=?,storage=?,active=? WHERE id=?")) {
                fill(ps, p, 1);
                ps.setInt(12, p.id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            pl.getLogger().severe("DB save: " + e.getMessage());
        }
    }

    public synchronized void delete(int id) {
        try {
            ensure();
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pets WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            pl.getLogger().severe("DB delete: " + e.getMessage());
        }
    }

    private void fill(PreparedStatement ps, PetData p, int i) throws SQLException {
        ps.setString(i++, p.owner.toString());
        ps.setString(i++, p.type);
        ps.setString(i++, p.name);
        ps.setInt(i++, p.level);
        ps.setDouble(i++, p.xp);
        ps.setDouble(i++, p.hunger);
        ps.setString(i++, p.behavior.name());
        ps.setInt(i++, p.points);
        ps.setString(i++, String.join(",", p.unlocked));
        String st = null;
        if (p.items != null && p.items.length > 0) st = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(p.items));
        ps.setString(i++, st);
        ps.setInt(i, p.active ? 1 : 0);
    }
}
