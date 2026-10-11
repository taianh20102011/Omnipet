package dev.omnipets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class OmniPets extends JavaPlugin {
    public PetDatabase db;
    public SkillTrees trees;
    public PetManager pets;
    public Menus menus;
    public WebEditor web;
    public Lang lang;
    public SkillEffects fx;
    public Animator anim;
    public TradeManager trade;

    private static final String[] COMMANDS = {"petinfo", "petinventory", "petbehavior", "petswitch", "petstore",
            "petname", "petskills", "petride", "petfly", "petlang", "pettrade", "omnipets"};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copySkillTrees();
        lang = new Lang(this);
        lang.load();
        db = new PetDatabase(this);
        try {
            db.init();
        } catch (Exception e) {
            getLogger().severe("Không mở được database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        fx = new SkillEffects(this);
        fx.load();
        anim = new Animator(this);
        anim.load();
        trees = new SkillTrees(this);
        trees.load();
        pets = new PetManager(this);
        menus = new Menus(this);
        web = new WebEditor(this);
        trade = new TradeManager(this);
        getServer().getPluginManager().registerEvents(new PetListener(this), this);
        getServer().getPluginManager().registerEvents(menus, this);
        PetCommands cmds = new PetCommands(this);
        for (String c : COMMANDS) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(cmds);
                pc.setTabCompleter(cmds);
            }
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiHook(this).register();
            getLogger().info("Đã đăng ký PlaceholderAPI: %omnipets_<name|level|xp|xp_needed|type|behavior|hunger|points|count>%");
        }
        pets.start();
        getLogger().info("OmniPets v" + getPluginMeta().getVersion() + " đã bật - " + trees.trees.size() + " cây kỹ năng, "
                + fx.defs.size() + " effect tùy chỉnh, " + anim.anims.size() + " animation.");
        for (Player p : Bukkit.getOnlinePlayers()) pets.loadPlayer(p);
    }

    private void copySkillTrees() {
        try (InputStream in = getResource("skilltrees/index.txt")) {
            if (in == null) return;
            for (String n : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                n = n.trim();
                if (!n.isEmpty() && !new File(getDataFolder(), "skilltrees/" + n).exists()) saveResource("skilltrees/" + n, false);
            }
        } catch (IOException e) {
            getLogger().warning("Không chép được cây kỹ năng: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (pets != null) pets.shutdown();
        if (db != null) db.close();
    }

    public void reloadAll() {
        reloadConfig();
        lang.load();
        fx.load();
        anim.load();
        trees.load();
        pets.refreshAll();
    }

    public Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public void msg(CommandSender to, String m) {
        to.sendMessage(c(getConfig().getString("settings.prefix", "&d[OmniPets]&r ") + m));
    }

    /** Dịch theo ngôn ngữ của người nhận. */
    public String t(CommandSender s, String key, Object... a) {
        return lang.get(s, key, a);
    }

    public void tm(CommandSender s, String key, Object... a) {
        msg(s, t(s, key, a));
    }

    public String bl(CommandSender s, Behavior b) {
        return t(s, "behavior." + b.name().toLowerCase() + ".label");
    }

    public String bd(CommandSender s, Behavior b) {
        return t(s, "behavior." + b.name().toLowerCase() + ".desc");
    }
}
