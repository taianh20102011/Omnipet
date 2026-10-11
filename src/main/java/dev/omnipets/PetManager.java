package dev.omnipets;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class PetManager {
    private final OmniPets pl;
    public final Map<UUID, List<PetData>> owned = new HashMap<>();
    public final Map<UUID, PetData> byEntity = new HashMap<>();
    public final Map<UUID, UUID> lastAttacker = new HashMap<>();
    public final Map<UUID, Long> lastAttackTime = new HashMap<>();
    private final Set<PetData> riding = new HashSet<>();
    private long tick = 0;

    public PetManager(OmniPets pl) {
        this.pl = pl;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(pl, this::run, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(pl, this::rideTick, 20L, 1L);
    }

    // ---------- truy cập ----------
    public ConfigurationSection type(String key) {
        return pl.getConfig().getConfigurationSection("pets." + key);
    }

    public Set<String> typeKeys() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("pets");
        return s == null ? Set.of() : s.getKeys(false);
    }

    public List<PetData> list(UUID u) {
        return owned.getOrDefault(u, List.of());
    }

    public PetData active(UUID u) {
        for (PetData p : list(u)) if (p.active) return p;
        return null;
    }

    public List<PetData> actives(UUID u) {
        List<PetData> r = new ArrayList<>();
        for (PetData p : list(u)) if (p.active) r.add(p);
        return r;
    }

    // ---------- vòng đời ----------
    public void loadPlayer(Player p) {
        List<PetData> l = new ArrayList<>(pl.db.load(p.getUniqueId()));
        owned.put(p.getUniqueId(), l);
        for (PetData d : l) if (d.active) spawn(p, d);
    }

    public void unloadPlayer(Player p) {
        List<PetData> l = owned.remove(p.getUniqueId());
        if (l == null) return;
        for (PetData d : l) {
            despawn(d);
            pl.db.save(d);
        }
        lastAttacker.remove(p.getUniqueId());
        lastAttackTime.remove(p.getUniqueId());
    }

    public void shutdown() {
        for (List<PetData> l : owned.values()) for (PetData d : l) {
            despawn(d);
            pl.db.save(d);
        }
    }

    public void saveAll() {
        for (List<PetData> l : owned.values()) for (PetData d : l) pl.db.save(d);
    }

    public void refreshAll() {
        for (List<PetData> l : owned.values()) for (PetData d : l) if (d.entity != null) {
            applyStats(d);
            updateName(d);
        }
    }

    public PetData create(UUID owner, String typeKey, String name) {
        PetData p = new PetData();
        p.owner = owner;
        p.type = typeKey;
        p.name = name;
        ConfigurationSection t = type(typeKey);
        p.items = new ItemStack[(t == null ? 0 : t.getInt("backpack-rows", 0)) * 9];
        pl.db.insert(p);
        owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(p);
        return p;
    }

    public void removePet(PetData p) {
        despawn(p);
        pl.db.delete(p.id);
        List<PetData> l = owned.get(p.owner);
        if (l != null) l.remove(p);
    }

    public void spawn(Player o, PetData p) {
        despawn(p);
        ConfigurationSection t = type(p.type);
        if (t == null) return;
        EntityType et;
        try {
            et = EntityType.valueOf(t.getString("entity", "PIG").toUpperCase());
        } catch (Exception ex) {
            return;
        }
        if (et == EntityType.ENDER_DRAGON && !pl.getConfig().getBoolean("dragon.enabled", false)) {
            pl.tm(o, "dragon-disabled");
            return;
        }
        Entity e = ModelHooks.spawnBase(pl, t, o.getLocation());
        if (e == null) e = o.getWorld().spawnEntity(o.getLocation(), et);
        if (!(e instanceof Mob m)) {
            e.remove();
            return;
        }
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.setInvulnerable(true);
        m.setCanPickupItems(false);
        if (m instanceof org.bukkit.entity.Ageable a) {
            a.setAdult();
            a.setAgeLock(true);
        }
        Bukkit.getMobGoals().removeAllGoals(m);   // chỉ để OmniPets điều khiển
        if (m.getType() == EntityType.ENDER_DRAGON) {
            m.setAI(false);
            m.setGravity(false);
        }
        p.entity = m;
        p.active = true;
        p.flying = false;
        p.farmTarget = null;
        byEntity.put(m.getUniqueId(), p);
        applyStats(p);
        updateName(p);
        p.modelId = null;
        ModelHooks.apply(pl, p);
        pl.anim.event(p, "summon", m.getLocation(), null);
    }

    public void despawn(PetData p) {
        clearOwnerMods(p);
        riding.remove(p);
        if (p.entity != null) {
            byEntity.remove(p.entity.getUniqueId());
            p.entity.remove();
            p.entity = null;
        }
    }

    public void activate(Player o, PetData p) {
        p.active = true;
        spawn(o, p);
        pl.db.save(p);
    }

    public void deactivate(PetData p) {
        p.active = false;
        if (p.entity != null) pl.anim.event(p, "store", p.entity.getLocation(), null);
        despawn(p);
        pl.db.save(p);
    }

    public void cycle(Player o) {
        List<PetData> l = list(o.getUniqueId());
        if (l.isEmpty()) {
            pl.tm(o, "no-pets");
            return;
        }
        PetData cur = active(o.getUniqueId());
        int i = cur == null ? -1 : l.indexOf(cur);
        PetData next = l.get((i + 1) % l.size());
        for (PetData a : actives(o.getUniqueId())) deactivate(a);
        activate(o, next);
        pl.tm(o, "switched", next.name);
    }

    // ---------- chỉ số / cấp độ ----------
    private void setAttr(Mob m, Attribute a, double v) {
        AttributeInstance i = m.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    public void applyStats(PetData p) {
        Mob m = p.entity;
        ConfigurationSection t = type(p.type);
        if (m == null || t == null) return;
        Map<String, Double> sk = pl.trees.total(p);
        double hp = t.getDouble("base.health", 20) + t.getDouble("per-level.health", 1) * (p.level - 1) + sk.getOrDefault("health", 0.0);
        setAttr(m, Attribute.MAX_HEALTH, hp);
        m.setHealth(Math.min(hp, m.getAttribute(Attribute.MAX_HEALTH).getValue()));
        double sp = t.getDouble("base.speed", 0.25) + t.getDouble("per-level.speed", 0.001) * (p.level - 1) + sk.getOrDefault("speed", 0.0);
        setAttr(m, Attribute.MOVEMENT_SPEED, sp);
        double scale = 1;
        int best = -1;
        ConfigurationSection st = t.getConfigurationSection("stages");
        if (st != null) for (String k : st.getKeys(false)) {
            try {
                int lv = Integer.parseInt(k);
                if (lv <= p.level && lv > best) {
                    best = lv;
                    scale = st.getDouble(k, 1);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        setAttr(m, Attribute.SCALE, scale);
        for (SkillEffects.Def d : pl.fx.defs.values()) {   // effect tùy chỉnh lên chính pet
            if (!d.type.equals("PET_ATTRIBUTE")) continue;
            Attribute a = SkillEffects.attr(String.valueOf(d.params.get("attribute")));
            AttributeInstance ai = a == null ? null : m.getAttribute(a);
            if (ai != null) setMod(ai, modKey(p, d), sk.getOrDefault(d.id, 0.0) * d.d("per-value", 1));
        }
    }

    public double damageOf(PetData p) {
        ConfigurationSection t = type(p.type);
        return t.getDouble("base.damage", 3) + t.getDouble("per-level.damage", 0.4) * (p.level - 1)
                + pl.trees.total(p).getOrDefault("damage", 0.0);
    }

    public void updateName(PetData p) {
        if (p.entity == null) return;
        p.entity.customName(pl.c("&7[Lv." + p.level + "] &f" + p.name));
        p.entity.setCustomNameVisible(true);
    }

    public void gainXp(PetData p, double amt) {
        amt *= xpMult(p);
        int max = pl.getConfig().getInt("xp.max-level", 50);
        if (p.level >= max) return;
        p.xp += amt;
        boolean up = false;
        int stage0 = stageKey(p.type, p.level);
        while (p.level < max && p.xp >= p.xpNeeded(pl)) {
            p.xp -= p.xpNeeded(pl);
            p.level++;
            p.points++;
            up = true;
        }
        if (up) {
            applyStats(p);
            updateName(p);
            ModelHooks.apply(pl, p);
            Player o = Bukkit.getPlayer(p.owner);
            if (o != null) {
                pl.tm(o, "levelup", p.name, p.level);
                o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            Location lu = p.entity != null ? p.entity.getLocation() : (o != null ? o.getLocation() : null);
            if (lu != null && !pl.anim.event(p, "level-up", lu, null) && p.entity != null)
                p.entity.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, lu.clone().add(0, 1, 0), 25, .4, .5, .4, .1);
            if (lu != null && stageKey(p.type, p.level) != stage0) pl.anim.event(p, "evolve", lu, null);
        }
    }

    // ---------- kho đồ ----------
    /** @return phần dư không nhét vừa (null nếu vừa hết) */
    public ItemStack giveItem(PetData p, ItemStack it) {
        if (p.items.length == 0) return it;
        Inventory inv = Bukkit.createInventory(null, p.items.length);
        inv.setContents(p.items);
        Map<Integer, ItemStack> left = inv.addItem(it);
        p.items = inv.getContents();
        return left.isEmpty() ? null : left.values().iterator().next();
    }

    // ---------- ăn / thuần hóa ----------
    public boolean feed(Player o, PetData p, ItemStack hand) {
        ConfigurationSection f = pl.getConfig().getConfigurationSection("hunger.feed-items");
        if (f == null || !f.contains(hand.getType().name())) return false;
        p.hunger = Math.min(100, p.hunger + f.getDouble(hand.getType().name()));
        hand.setAmount(hand.getAmount() - 1);
        o.getInventory().setItemInMainHand(hand);
        if (p.entity != null) p.entity.getWorld().spawnParticle(Particle.HEART, p.entity.getLocation().add(0, 1, 0), 5, .3, .3, .3);
        pl.tm(o, "fed", p.name, (int) p.hunger);
        return true;
    }

    public boolean tame(Player o, Entity target, ItemStack hand) {
        if (!(target instanceof Mob) || byEntity.containsKey(target.getUniqueId())) return false;
        for (String k : typeKeys()) {
            ConfigurationSection t = type(k);
            if (!target.getType().name().equalsIgnoreCase(t.getString("entity", ""))) continue;
            if (!hand.getType().name().equalsIgnoreCase(t.getString("tame-item", ""))) continue;
            if (!o.hasPermission("omnipets.tame")) return false;
            if (target.getType() == EntityType.ENDER_DRAGON) {
                if (!pl.getConfig().getBoolean("dragon.enabled", false)) return false;
                LivingEntity dr = (LivingEntity) target;
                double pct = dr.getHealth() / dr.getAttribute(Attribute.MAX_HEALTH).getValue() * 100;
                if (pct > pl.getConfig().getDouble("dragon.tame-max-health-percent", 30)) {
                    pl.tm(o, "dragon-too-strong");
                    return true;
                }
            }
            if (list(o.getUniqueId()).size() >= pl.getConfig().getInt("settings.max-pets", 3) && !o.hasPermission("omnipets.unlimited")) {
                pl.tm(o, "tame-max");
                return true;
            }
            hand.setAmount(hand.getAmount() - 1);
            o.getInventory().setItemInMainHand(hand);
            if (ThreadLocalRandom.current().nextDouble() < t.getDouble("tame-chance", 0.35)) {
                Location tl = target.getLocation();
                target.remove();
                String nm = t.getString("display", k).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
                PetData p = create(o.getUniqueId(), k, nm);
                if (active(o.getUniqueId()) == null) activate(o, p);
                pl.anim.event(p, "tame", tl, null);
                pl.tm(o, "tame-ok", nm);
            } else {
                pl.tm(o, "tame-fail");
            }
            return true;
        }
        return false;
    }

    // ---------- cưỡi / bay ----------
    public boolean ride(Player o, PetData p) {
        ConfigurationSection t = type(p.type);
        if (p.entity == null || t == null || !t.getBoolean("rideable", false)) return false;
        p.entity.getPathfinder().stopPathfinding();
        p.entity.addPassenger(o);
        riding.add(p);
        if (p.entity.getType() == EntityType.ENDER_DRAGON) p.flying = true;
        pl.anim.event(p, "mount", p.entity.getLocation(), null);
        return true;
    }

    public boolean toggleFly(PetData p) {
        ConfigurationSection t = type(p.type);
        if (p.entity == null || !riding.contains(p) || t == null || !t.getBoolean("flyable", false)) return false;
        p.flying = !p.flying;
        p.entity.setGravity(!p.flying);
        return true;
    }

    private void rideTick() {
        if (riding.isEmpty()) return;
        for (Iterator<PetData> it = riding.iterator(); it.hasNext(); ) {
            PetData p = it.next();
            Player o = Bukkit.getPlayer(p.owner);
            Mob m = p.entity;
            if (o == null || m == null || !m.isValid() || !m.getPassengers().contains(o)) {
                if (m != null && m.isValid()) m.setGravity(m.getType() != EntityType.ENDER_DRAGON);
                p.flying = false;
                it.remove();
                continue;
            }
            Input in = o.getCurrentInput();
            double yaw = Math.toRadians(o.getLocation().getYaw());
            double fwd = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0);
            double side = (in.isLeft() ? 1 : 0) - (in.isRight() ? 1 : 0);
            double mx = -Math.sin(yaw) * fwd + Math.cos(yaw) * side;
            double mz = Math.cos(yaw) * fwd + Math.sin(yaw) * side;
            double len = Math.hypot(mx, mz);
            if (len > 0) {
                mx /= len;
                mz /= len;
            }
            double sp = p.flying ? pl.getConfig().getDouble("ride.fly-speed", 0.6) : pl.getConfig().getDouble("ride.ground-speed", 0.3);
            if (in.isSprint()) sp *= 1.4;
            Vector v = m.getVelocity();
            if (p.flying) {
                double pitch = Math.toRadians(o.getLocation().getPitch());
                double vy = in.isJump() ? 0.45 : (fwd > 0 ? -Math.sin(pitch) * sp : 0);
                v = new Vector(mx * sp, vy, mz * sp);
            } else {
                v.setX(mx * sp);
                v.setZ(mz * sp);
                if (in.isJump() && m.isOnGround()) v.setY(0.5);
            }
            if (m.getType() == EntityType.ENDER_DRAGON) {
                Location n = m.getLocation().add(v);
                n.setYaw(o.getLocation().getYaw() + 180);
                n.setPitch(0);
                m.teleport(n, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            } else {
                m.setVelocity(v);
                m.setRotation(o.getLocation().getYaw(), 0);
            }
        }
    }

    // ---------- vòng lặp chính (5 tick) ----------
    /** Di chuyển: pathfinder cho mob thường, dịch chuyển từng bước cho Rồng Ender (AI tắt). */
    private void go(Mob m, Location to, double speed) {
        if (m.getType() == EntityType.ENDER_DRAGON) {
            Location l = m.getLocation();
            Vector d = to.toVector().subtract(l.toVector());
            double dist = d.length();
            if (dist < 0.5) return;
            d.normalize().multiply(Math.min(dist, 0.9 * speed));
            Location n = l.clone().add(d);
            n.setDirection(d);
            n.setYaw(n.getYaw() + 180);
            m.teleport(n);
        } else {
            m.getPathfinder().moveTo(to, speed);
        }
    }

    private boolean hungry(PetData p) {
        return pl.getConfig().getBoolean("hunger.enabled", true) && p.hunger <= 0;
    }

    // ---------- debug (/omnipets debug) ----------
    public final Set<UUID> debug = new HashSet<>();
    private final Map<UUID, Long> dbgAt = new HashMap<>();

    public void dbg(Player o, String msg) {
        if (o == null || !debug.contains(o.getUniqueId())) return;
        if (tick - dbgAt.getOrDefault(o.getUniqueId(), -100L) < 10) return;   // tối đa ~2 dòng/giây
        dbgAt.put(o.getUniqueId(), tick);
        o.sendMessage(pl.c("&8[debug] &7" + msg));
    }

    public void dbgPet(PetData p, String msg) {
        dbg(Bukkit.getPlayer(p.owner), msg);
    }

    private void run() {
        tick += 5;
        for (Map.Entry<UUID, List<PetData>> e : new ArrayList<>(owned.entrySet())) {
            Player o = Bukkit.getPlayer(e.getKey());
            if (o == null || !o.isOnline()) continue;
            for (PetData p : new ArrayList<>(e.getValue())) {
                try {
                    tickPet(o, p);
                } catch (Exception ex) {   // một pet lỗi không được làm dừng các pet khác
                    if (tick % 200 == 0) pl.getLogger().warning("Lỗi khi xử lý pet #" + p.id + ": " + ex);
                    dbg(o, "&cLỗi: " + ex);
                }
            }
        }
        if (tick % 6000 == 0) saveAll();
    }

    private void tickPet(Player o, PetData p) {
        if (!p.active) return;
        if (p.entity == null || !p.entity.isValid()) {
            if (tick % 100 == 0) spawn(o, p);   // hồi sinh nếu bị mất
            return;
        }
        if (tick % 1200 == 0 && pl.getConfig().getBoolean("hunger.enabled", true))
            p.hunger = Math.max(0, p.hunger - pl.getConfig().getDouble("hunger.decay-per-minute", 2));
        if (riding.contains(p)) return;
        Mob m = p.entity;
        double tp = pl.getConfig().getDouble("settings.teleport-distance", 24);
        if (!m.getWorld().equals(o.getWorld()) || m.getLocation().distanceSquared(o.getLocation()) > tp * tp) {
            m.teleport(o.getLocation());   // xử lý trước khi tính khoảng cách tới quái (tránh lỗi khác thế giới)
            return;
        }
        boolean busy = false;
        if (!hungry(p)) {
            busy = behave(o, p);
            periodic(o, p);
        } else {
            dbg(o, "Pet đang đói (độ no 0) nên không chiến đấu/farm. Hãy cho ăn.");
        }
        follow(o, p, busy);
    }

    private void follow(Player o, PetData p, boolean busy) {
        Mob m = p.entity;
        Location ol = o.getLocation();
        double tp = pl.getConfig().getDouble("settings.teleport-distance", 24);
        if (!m.getWorld().equals(o.getWorld()) || m.getLocation().distanceSquared(ol) > tp * tp) {
            m.teleport(ol);
            return;
        }
        if (busy) return;
        double fd = pl.getConfig().getDouble("settings.follow-distance", 4);
        if (m.getLocation().distanceSquared(ol) > fd * fd) go(m, ol, 1.2);
    }

    private boolean behave(Player o, PetData p) {
        return switch (p.behavior) {
            case FRIENDLY -> false;
            case AGGRESSIVE -> combat(o, p, x -> x instanceof Enemy, pl.getConfig().getDouble("behaviors.aggressive.radius", 12));
            case PROTECT -> combat(o, p, x -> threatens(o, x), pl.getConfig().getDouble("behaviors.protect.radius", 16));
            case FARM -> farm(o, p);
            case AURA_FARMING -> {
                aura(o, p);
                yield false;
            }
        };
    }

    private boolean threatens(Player o, LivingEntity x) {
        if (x instanceof Player && !pl.getConfig().getBoolean("behaviors.protect-pvp", false)) return false;
        if (x instanceof Mob mm && o.equals(mm.getTarget())) return true;
        UUID a = lastAttacker.get(o.getUniqueId());
        Long t = lastAttackTime.get(o.getUniqueId());
        return a != null && a.equals(x.getUniqueId()) && t != null && System.currentTimeMillis() - t < 10_000;
    }

    private boolean combat(Player o, PetData p, Predicate<LivingEntity> f, double r) {
        Mob m = p.entity;
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        int seen = 0;
        for (Entity e : o.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || le.equals(m) || byEntity.containsKey(le.getUniqueId())) continue;
            if (!le.getWorld().equals(m.getWorld())) continue;
            seen++;
            if (!f.test(le)) continue;
            double d = le.getLocation().distanceSquared(m.getLocation());
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        if (best == null) {
            p.stuck = 0;
            dbg(o, p.behavior + ": không có mục tiêu hợp lệ (thấy " + seen + " sinh vật trong " + (int) r + " block quanh bạn)");
            return false;
        }
        // "chạm tới" tính theo hộp va chạm để đúng với cả mob nhỏ lẫn mob to
        boolean inReach = m.getBoundingBox().expand(1.2).overlaps(best.getBoundingBox());
        if (!inReach) {
            go(m, best.getLocation(), 1.4);
            if (++p.stuck >= 8) {   // ~2 giây không tới được -> lao thẳng tới mục tiêu
                Location tpl = best.getLocation().clone().add(best.getLocation().getDirection().multiply(-1.2));
                tpl.setY(best.getLocation().getY());
                m.teleport(tpl);
                p.stuck = 0;
                dbg(o, "Pet bị kẹt, lao tới " + best.getType());
            } else {
                dbg(o, "Đang chạy tới " + best.getType() + " (cách " + String.format("%.1f", Math.sqrt(bd)) + " block)");
            }
            return true;
        }
        p.stuck = 0;
        if (tick - p.lastAttack >= pl.getConfig().getLong("behaviors.combat-cooldown-ticks", 20)) {
            p.lastAttack = tick;
            strike(p, best);
        }
        return true;
    }

    private void strike(PetData p, LivingEntity t) {
        Map<String, Double> sk = pl.trees.total(p);
        p.entity.swingMainHand();
        double hpBefore = t.getHealth();
        t.damage(damageOf(p), p.entity);
        dbgPet(p, "đánh " + t.getType() + " " + String.format("%.1f", hpBefore) + " -> " + String.format("%.1f", t.getHealth())
                + (t.getHealth() >= hpBefore ? " (KHÔNG mất máu: có plugin khác hủy/giảm sát thương?)" : ""));
        pl.anim.event(p, "hit", t.getLocation(), p.entity.getLocation());
        double fire = sk.getOrDefault("fire-hit", 0.0);
        if (fire > 0) {
            t.setFireTicks((int) (fire * 20));
            pl.anim.event(p, "fire-hit", t.getLocation(), p.entity.getLocation());
        }
        double slow = sk.getOrDefault("slow-hit", 0.0);
        if (slow > 0) {
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (slow * 20), 1));
            pl.anim.event(p, "slow-hit", t.getLocation(), p.entity.getLocation());
        }
        onHitEffects(p, t, sk);
    }

    // ---------- hiệu ứng kỹ năng tùy chỉnh (effects.yml) ----------
    private NamespacedKey modKey(PetData p, SkillEffects.Def d) {
        return new NamespacedKey(pl, "p" + p.id + "_" + d.id);
    }

    /** Đặt modifier tạm thời (không lưu vào dữ liệu người chơi); amt = 0 thì gỡ. */
    private void setMod(AttributeInstance ai, NamespacedKey key, double amt) {
        for (AttributeModifier m : new ArrayList<>(ai.getModifiers())) {
            if (key.equals(m.getKey())) {
                if (m.getAmount() == amt) return;
                ai.removeModifier(m);
            }
        }
        if (amt != 0) ai.addTransientModifier(new AttributeModifier(key, amt, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    private void clearOwnerMods(PetData p) {
        Player o = Bukkit.getPlayer(p.owner);
        if (o == null) return;
        for (SkillEffects.Def d : pl.fx.defs.values()) {
            if (!d.type.equals("OWNER_ATTRIBUTE")) continue;
            Attribute a = SkillEffects.attr(String.valueOf(d.params.get("attribute")));
            AttributeInstance ai = a == null ? null : o.getAttribute(a);
            if (ai != null) setMod(ai, modKey(p, d), 0);
        }
    }

    private void customTick(Player o, PetData p, Map<String, Double> sk) {
        for (SkillEffects.Def d : pl.fx.defs.values()) {
            double v = sk.getOrDefault(d.id, 0.0);
            long every = (long) (Math.max(1, d.d("interval", 5)) * 20);
            switch (d.type) {
                case "OWNER_ATTRIBUTE" -> {
                    Attribute a = SkillEffects.attr(String.valueOf(d.params.get("attribute")));
                    AttributeInstance ai = a == null ? null : o.getAttribute(a);
                    if (ai != null) setMod(ai, modKey(p, d), v * d.d("per-value", 1));
                }
                case "OWNER_POTION" -> {
                    PotionEffectType t = SkillEffects.potion(String.valueOf(d.params.get("potion")));
                    if (v > 0 && t != null && tick % every == 0) fxAnim(d, o.getLocation(), null);
                    if (v > 0 && t != null && tick % every == 0)
                        o.addPotionEffect(new PotionEffect(t, Math.max(1, (int) (d.d("seconds", 8) * 20)), Math.max(0, (int) d.d("amplifier", 0)), true, false, true));
                }
                case "AURA_DAMAGE" -> {
                    if (v <= 0 || tick % every != 0) break;
                    double r = d.d("radius", 4);
                    Particle part = SkillEffects.particle(String.valueOf(d.params.get("particle")));
                    fxAnim(d, p.entity.getLocation(), null);
                    for (Entity e : p.entity.getNearbyEntities(r, r, r)) {
                        if (e instanceof Enemy && e instanceof LivingEntity le && !le.isDead()) {
                            le.damage(v * d.d("per-value", 1), p.entity);
                            if (part != null) le.getWorld().spawnParticle(part, le.getLocation().add(0, 1, 0), 6, .3, .4, .3, 0);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void onHitEffects(PetData p, LivingEntity t, Map<String, Double> sk) {
        for (SkillEffects.Def d : pl.fx.defs.values()) {
            double v = sk.getOrDefault(d.id, 0.0);
            if (v <= 0) continue;
            switch (d.type) {
                case "HIT_POTION" -> {
                    fxAnim(d, t.getLocation(), p.entity.getLocation());
                    PotionEffectType pt = SkillEffects.potion(String.valueOf(d.params.get("potion")));
                    if (pt != null) t.addPotionEffect(new PotionEffect(pt, Math.max(1, (int) (v * d.d("per-value", 1) * 20)), Math.max(0, (int) d.d("amplifier", 0))));
                }
                case "HEAL_PET_ON_HIT" -> {
                    fxAnim(d, p.entity.getLocation(), null);
                    Mob m = p.entity;
                    double max = m.getAttribute(Attribute.MAX_HEALTH).getValue();
                    m.setHealth(Math.min(max, m.getHealth() + v * d.d("per-value", 1)));
                }
                default -> {
                }
            }
        }
    }

    /** Phát animation gắn riêng vào effect (tham số "animation" trong effects.yml). */
    private void fxAnim(SkillEffects.Def d, Location at, Location from) {
        Object a = d.params.get("animation");
        if (a != null && !String.valueOf(a).isBlank()) pl.anim.play(String.valueOf(a), at, from);
    }

    private int stageKey(String type, int level) {
        ConfigurationSection t = type(type);
        ConfigurationSection st = t == null ? null : t.getConfigurationSection("stages");
        int best = -1;
        if (st != null) for (String k : st.getKeys(false)) {
            try {
                int lv = Integer.parseInt(k);
                if (lv <= level && lv > best) best = lv;
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private double xpMult(PetData p) {
        double m = 1;
        Map<String, Double> sk = pl.trees.total(p);
        for (SkillEffects.Def d : pl.fx.defs.values())
            if (d.type.equals("XP_MULT")) m += sk.getOrDefault(d.id, 0.0) * d.d("per-value", 1) / 100.0;
        return Math.max(0, m);
    }

    // ---------- FARM ----------
    private boolean isCrop(Material t) {
        return Tag.CROPS.isTagged(t) || t == Material.NETHER_WART;
    }

    private boolean mature(Block b) {
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    private boolean farm(Player o, PetData p) {
        Mob m = p.entity;
        ConfigurationSection c = pl.getConfig().getConfigurationSection("behaviors.farm");
        if (p.farmTarget != null) {
            Block b = p.farmTarget;
            Location bl = b.getLocation().add(.5, 0, .5);
            if (!mature(b)) {
                p.farmTarget = null;
            } else {
                if (m.getLocation().distanceSquared(bl) <= 5.0) {
                    harvest(o, p, b);
                    p.farmTarget = null;
                } else {
                    go(m, bl, 1.3);
                }
                return true;
            }
        }
        if (tick % c.getInt("interval-ticks", 40) != 0) return false;
        int r = c.getInt("radius", 6);
        double chance = c.getDouble("bonemeal-chance", 0.05);
        Block best = null;
        double bd = Double.MAX_VALUE;
        Block center = o.getLocation().getBlock();
        for (int x = -r; x <= r; x++) for (int y = -1; y <= 2; y++) for (int z = -r; z <= r; z++) {
            Block b = center.getRelative(x, y, z);
            if (!isCrop(b.getType()) || !(b.getBlockData() instanceof Ageable ag)) continue;
            if (ag.getAge() >= ag.getMaximumAge()) {
                double d = b.getLocation().distanceSquared(m.getLocation());
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            } else if (ThreadLocalRandom.current().nextDouble() < chance) {
                ag.setAge(ag.getAge() + 1);
                b.setBlockData(ag);
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(.5, .5, .5), 3, .2, .2, .2);
            }
        }
        p.farmTarget = best;
        return best != null;
    }

    private void harvest(Player o, PetData p, Block b) {
        BlockBreakEvent ev = new BlockBreakEvent(b, o);   // tương thích plugin claim/bảo vệ
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        Material seed = switch (b.getType()) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
        List<ItemStack> drops = new ArrayList<>(b.getDrops());
        boolean took = false;
        for (ItemStack d : drops) {
            if (!took && seed != null && d.getType() == seed && d.getAmount() > 0) {
                d.setAmount(d.getAmount() - 1);   // trừ 1 hạt để trồng lại
                took = true;
            }
        }
        Ageable ag = (Ageable) b.getBlockData();
        ag.setAge(0);
        b.setBlockData(ag);
        for (ItemStack d : drops) {
            if (d.getAmount() <= 0) continue;
            ItemStack left = giveItem(p, d);
            if (left != null) b.getWorld().dropItemNaturally(b.getLocation().add(.5, .5, .5), left);
        }
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(.5, .5, .5), 8, .3, .3, .3);
        pl.anim.event(p, "harvest", b.getLocation().add(.5, .5, .5), null);
        gainXp(p, 0.5);
    }

    // ---------- AURA FARMING ----------
    private void aura(Player o, PetData p) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("behaviors.aura-farming");
        Mob m = p.entity;
        double r = c.getDouble("radius", 6);
        Location l = m.getLocation().add(0, 0.6, 0);
        double vr = Math.min(r, 3);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + tick * 0.1;
            l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.getX() + Math.cos(a) * vr, l.getY(), l.getZ() + Math.sin(a) * vr, 1, 0, 0, 0, 0);
        }
        if (tick % c.getInt("interval-ticks", 40) != 0) return;
        pl.anim.event(p, "aura-pulse", m.getLocation(), null);
        if (o.getWorld().equals(m.getWorld()) && o.getLocation().distanceSquared(m.getLocation()) <= r * r) {
            ConfigurationSection ef = c.getConfigurationSection("effects");
            if (ef != null) for (String k : ef.getKeys(false)) {
                @SuppressWarnings("deprecation")
                PotionEffectType t = PotionEffectType.getByName(k.toUpperCase());
                if (t != null) o.addPotionEffect(new PotionEffect(t, c.getInt("effect-seconds", 6) * 20, ef.getInt(k, 0), true, false, true));
            }
        }
        double dmg = c.getDouble("damage", 3) + p.level * 0.15;
        for (Entity e : m.getNearbyEntities(r, r, r)) {
            if (e instanceof Enemy && e instanceof LivingEntity le && !le.isDead()) le.damage(dmg, m);
        }
        gainXp(p, pl.getConfig().getDouble("xp.aura-per-pulse", 1));
    }

    // ---------- kỹ năng bị động ----------
    private void periodic(Player o, PetData p) {
        if (tick % 20 != 0) return;
        Map<String, Double> sk = pl.trees.total(p);
        customTick(o, p, sk);
        double pick = sk.getOrDefault("auto-pickup", 0.0);
        if (pick > 0) {
            for (Entity e : p.entity.getNearbyEntities(pick, pick, pick)) {
                if (e instanceof Item it && it.getPickupDelay() <= 0) {
                    ItemStack left = giveItem(p, it.getItemStack());
                    if (left == null) it.remove();
                    else it.setItemStack(left);
                }
            }
        }
        if (tick % 200 == 0) {
            double heal = sk.getOrDefault("heal-owner", 0.0);
            double max = o.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (heal > 0 && o.getHealth() < max) {
                o.setHealth(Math.min(max, o.getHealth() + heal));
                pl.anim.event(p, "heal-owner", o.getLocation(), p.entity.getLocation());
            }
        }
        double res = sk.getOrDefault("owner-resist", 0.0);
        if (res >= 1 && tick % 100 == 0)
            o.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, (int) res - 1, true, false, true));
    }
}
