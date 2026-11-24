package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/** /legendary — административные команды. У каждой подкоманды своё право legendary.command.*. */
public final class LegendaryCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "give", "reload", "info", "progress", "hearts",
            "altar", "banlog", "unban", "unique", "cooldown", "menu", "revive", "dungeon", "barrier", "doctor");
    private static final List<String> ALTAR_SUBS = List.of("create", "build", "spawn", "random", "tp", "remove", "list", "validate", "cancel");

    private final LegendaryPlugin plugin;

    public LegendaryCommand(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers

    private boolean has(CommandSender s, String perm) {
        if (s.hasPermission(perm) || s.hasPermission("legendary.admin")) {
            return true;
        }
        plugin.msg(s, "no_permission");
        return false;
    }

    private void line(CommandSender s, String text) {
        s.sendMessage(Util.color(text));
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer findPlayer(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore()) {
            return off;
        }
        line(s, "&cИгрок &f" + name + " &cне найден (не заходил на сервер).");
        return null;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String name(CommandSender s) {
        return s.getName();
    }

    private static String date(long ts) {
        return new SimpleDateFormat("dd.MM.yy HH:mm").format(new Date(ts));
    }

    // ------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(s, a);
            case "reload" -> {
                if (has(s, "legendary.command.reload")) {
                    plugin.reloadAll();
                    plugin.adminLog(name(s), "reload", "");
                    line(s, "&aКонфигурация перезагружена.");
                }
            }
            case "info" -> info(s, a);
            case "progress" -> progress(s, a);
            case "hearts" -> hearts(s, a);
            case "altar" -> altar(s, a);
            case "dungeon" -> dungeon(s, a);
            case "barrier" -> barrier(s, a);
            case "doctor" -> doctor(s);
            case "banlog" -> banlog(s, a);
            case "unban" -> unban(s, a);
            case "unique" -> unique(s, a);
            case "cooldown" -> cooldown(s, a);
            case "revive" -> {
                if (!has(s, "legendary.command.hearts")) {
                    return true;
                }
                OfflinePlayer rp = a.length > 1 ? findPlayer(s, a[1]) : null;
                if (rp == null) {
                    line(s, "&c/legendary revive <ник>");
                    return true;
                }
                plugin.hearts().revive(rp.getUniqueId());
                plugin.adminLog(name(s), "revive", a[1]);
                line(s, plugin.text("revived", "player", a[1]));
            }
            case "menu" -> {
                if (s instanceof Player pl && has(s, "legendary.command.menu")) {
                    plugin.adminMenu().open(pl);
                } else if (!(s instanceof Player)) {
                    line(s, "&cКоманда доступна только игроку.");
                }
            }
            default -> help(s);
        }
        return true;
    }

    /** Предстартовая проверка сервера: конфиг, рецепты, ресурспак, БД, нагрузка. */
    private void doctor(CommandSender s) {
        if (!has(s, "legendary.admin")) {
            return;
        }
        int bad = 0;
        line(s, "&6&lLegendaryWeapons — проверка перед открытием");
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / 1048576L;
        bad += check(s, maxMb >= 6000, "Память JVM (-Xmx): " + maxMb + " МБ", "для 50 игроков рекомендуется 6–8 ГБ (-Xmx6G…8G)");
        bad += check(s, Bukkit.getOnlinePlayers().size() <= Bukkit.getMaxPlayers(), "Слотов: " + Bukkit.getMaxPlayers(), "");
        bad += check(s, Bukkit.getMaxPlayers() >= 50, "max-players >= 50", "поставьте max-players=50 в server.properties");
        int badRecipes = 0;
        for (String id : plugin.items().ritualItems().keySet()) {
            ItemRegistry.Recipe r = plugin.items().recipe(id);
            if (r == null || !r.valid()) {
                badRecipes++;
                line(s, "&c  ✘ рецепт не валиден: &f" + id);
            }
        }
        bad += check(s, badRecipes == 0, "Рецепты всех " + plugin.items().ritualItems().size() + " предметов корректны", "см. config.yml → recipes");
        bad += check(s, plugin.getConfig().getBoolean("guard.enabled", true), "Защита от дюпа включена", "guard.enabled: true");
        bad += check(s, plugin.getConfig().getBoolean("admin_guard.enabled", true), "Защита админских предметов включена", "admin_guard.enabled: true");
        boolean rp = plugin.getConfig().getBoolean("resource_pack.enabled", false);
        String url = plugin.getConfig().getString("resource_pack.url", "");
        bad += check(s, !rp || (url != null && url.startsWith("http")), "Ресурспак: " + (rp ? "включён" : "выключен"), "включён, но url пуст");
        bad += check(s, !rp || !plugin.getConfig().getString("resource_pack.sha1", "").isEmpty(), "Ресурспак: sha1 указан", "без sha1 клиент перекачивает пак при каждом входе");
        bad += check(s, plugin.altars().all().size() > 0, "Алтарей на карте: " + plugin.altars().all().size(), "расставьте: /legendary altar random all");
        bad += check(s, plugin.dungeons().all().size() > 0, "Данжей на карте: " + plugin.dungeons().all().size(), "расставьте: /legendary dungeon random");
        boolean perm = true;
        for (String pn : new String[]{"give", "reload", "dungeon", "barrier", "info", "progress", "hearts", "altar",
                "banlog", "unban", "unique", "cooldown", "menu"}) {
            org.bukkit.permissions.Permission p = Bukkit.getPluginManager().getPermission("legendary.command." + pn);
            if (p == null || p.getDefault() != org.bukkit.permissions.PermissionDefault.FALSE) {
                perm = false;
            }
        }
        bad += check(s, perm, "Все /legendary-права по умолчанию выданы только админам", "");
        long over = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL && w.getWorldBorder().getSize() > 20000) {
                over++;
            }
        }
        bad += check(s, over == 0, "Граница мира задана (< 20 000)", "/worldborder set 6000 — иначе мир бесконечно растёт и генерация душит сервер");
        line(s, bad == 0 ? "&a&lВсё в порядке." : "&e&lЗамечаний: " + bad + " (см. выше). Подробности — SERVER_SETUP.md");
    }

    private int check(CommandSender s, boolean ok, String text, String hint) {
        line(s, (ok ? "&a  ✔ " : "&c  ✘ ") + "&f" + text + (ok || hint.isEmpty() ? "" : " &7— " + hint));
        return ok ? 0 : 1;
    }

    private void help(CommandSender s) {
        line(s, "&6&lLegendaryWeapons &7— команды:");
        line(s, "&e/legendary give <игрок> <id> [кол-во] &7— выдать оружие/броню/компонент");
        line(s, "&e/legendary reload &7— перезагрузить config.yml");
        line(s, "&e/legendary info <игрок> &7— данные игрока");
        line(s, "&e/legendary progress <игрок> <оружие> <get|set> [число]");
        line(s, "&e/legendary hearts <игрок> <set|add|remove> <число>");
        line(s, "&e/legendary revive <ник> &7— вернуть выбывшего игрока");
        line(s, "&e/legendary menu &7— меню администратора (то же, что предмет-«Звезда управления»)");
        line(s, "&e/legendary altar <create|build|remove|list|validate|cancel> [тип]");
        line(s, "&e/legendary altar spawn <тип> [x z] [мир] &7— построить алтарь (без координат — под вами)");
        line(s, "&e/legendary altar random [тип|all] [мир] &7— случайно расставить алтари по карте");
        line(s, "&e/legendary altar tp <id> &7— телепорт к алтарю");
        line(s, "&e/legendary barrier <wand|pos1|pos2|set x1 z1 x2 z2 [y1 y2]|wall|fill|clear|list|remove id> &7— барьеры (невидимые стены)");
        line(s, "&e/legendary dungeon <spawn [тип] [x z] [мир]|random [кол-во]|list|tp <id>|refill <id>|remove <id>> &7— данжи с компонентами");
        line(s, "&e/legendary banlog [игрок] &7— журнал Молота бана");
        line(s, "&e/legendary unban <ник> &7— разбан жертвы Молота");
        line(s, "&e/legendary unique <list|reset|limit> [оружие] [число] &7— лимит на карте / удалить с карты");
        line(s, "&e/legendary doctor &7— проверка готовности сервера (конфиг, рецепты, ресурспак, память)");
        line(s, "&e/legendary cooldown list &7— все перезарядки");
        line(s, "&e/legendary cooldown <id> <primary|secondary|both> <секунды> &7— сменить перезарядку");
        line(s, "&e/legendary cooldown reset <игрок> &7— сбросить активные перезарядки игрока");
    }

    // ------------------------------------------------------------ give

    private void give(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.give")) {
            return;
        }
        if (a.length < 3) {
            line(s, "&c/legendary give <игрок> <id> [кол-во]");
            return;
        }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) {
            line(s, "&cИгрок должен быть онлайн.");
            return;
        }
        String id = a[2].toLowerCase(Locale.ROOT);
        int amount = a.length > 3 && parseInt(a[3]) != null ? Math.max(1, Math.min(parseInt(a[3]), 2304)) : 1;
        ItemRegistry reg = plugin.items();

        if (reg.weapon(id) != null) {
            Weapon w = reg.weapon(id);
            if (w.unique() && plugin.rituals().uniqueTaken(id) && !s.hasPermission("legendary.command.unique")
                    && !s.hasPermission("legendary.admin")) {
                plugin.msg(s, "ritual_unique_taken");
                return;
            }
            ItemStack it = reg.createWeapon(id);
            plugin.registerInstance(id, reg.instanceId(it));
            RitualManager.give(target, it);
            plugin.adminLog(name(s), "give", id + " -> " + target.getName());
            line(s, "&aВыдано &f" + w.plainName() + " &aигроку &f" + target.getName());
            return;
        }
        if (id.equals(FoodBucket.ID)) {
            for (int i = 0; i < Math.min(amount, 64); i++) {
                RitualManager.give(target, plugin.food().create());
            }
            plugin.adminLog(name(s), "give", "food_bucket x" + Math.min(amount, 64) + " -> " + target.getName());
            line(s, "&aВыдано &dВедро еды &aигроку &f" + target.getName());
            return;
        }
        if (id.equals(AdminMenu.ID)) {
            RitualManager.give(target, plugin.adminMenu().create());
            plugin.adminLog(name(s), "give", "admin_menu -> " + target.getName());
            line(s, "&aВыдана &6Звезда управления &aигроку &f" + target.getName());
            return;
        }
        if (id.equals("copper_set")) {
            for (String aid : ItemRegistry.ARMOR_IDS) {
                giveArmor(target, aid);
            }
            plugin.adminLog(name(s), "give", "copper_set -> " + target.getName());
            line(s, "&aВыдан медный комплект игроку &f" + target.getName());
            return;
        }
        if (ItemRegistry.ARMOR_IDS.contains(id)) {
            if (!giveArmor(target, id)) {
                line(s, "&cНе удалось создать предмет (нет медной брони в этой версии?).");
                return;
            }
            plugin.adminLog(name(s), "give", id + " -> " + target.getName());
            line(s, "&aВыдано &f" + id + " &aигроку &f" + target.getName());
            return;
        }
        if (id.equals("pouch") || id.equals("recipe_book")) {
            RitualManager.give(target, id.equals("pouch") ? plugin.pouches().create() : reg.createRecipeBook());
            plugin.adminLog(name(s), "give", id + " -> " + target.getName());
            line(s, "&aВыдано: &f" + id + " &aигроку &f" + target.getName());
            return;
        }
        if (id.startsWith("blueprint_") && reg.components().containsKey(id.substring(10))) {
            RitualManager.give(target, reg.createBlueprint(id.substring(10)));
            plugin.adminLog(name(s), "give", id + " -> " + target.getName());
            line(s, "&aВыдан чертёж &f" + id + " &aигроку &f" + target.getName());
            return;
        }
        if (reg.components().containsKey(id)) {
            int left = amount;
            while (left > 0) {
                ItemStack it = reg.createComponent(id, left);
                left -= it.getAmount();
                RitualManager.give(target, it);
            }
            plugin.adminLog(name(s), "give", id + " x" + amount + " -> " + target.getName());
            line(s, "&aВыдан компонент &f" + id + " x" + amount + " &aигроку &f" + target.getName());
            return;
        }
        line(s, "&cНеизвестный id. Доступно: &f" + String.join(", ", allIds()));
    }

    private boolean giveArmor(Player target, String id) {
        ItemStack it = plugin.items().createArmor(id);
        if (it == null) {
            return false;
        }
        plugin.registerInstance(id, plugin.items().instanceId(it));
        RitualManager.give(target, it);
        return true;
    }

    private List<String> allIds() {
        List<String> ids = new ArrayList<>(plugin.items().weapons().keySet());
        ids.add("copper_set");
        ids.add(FoodBucket.ID);
        ids.add(AdminMenu.ID);
        ids.add("pouch");
        ids.add("recipe_book");
        ids.addAll(ItemRegistry.ARMOR_IDS);
        ids.addAll(plugin.items().components().keySet());
        for (String c : plugin.items().components().keySet()) {
            ids.add("blueprint_" + c);
        }
        return ids;
    }

    // ------------------------------------------------------------ info / progress / hearts

    private void info(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.info")) {
            return;
        }
        if (a.length < 2) {
            line(s, "&c/legendary info <игрок>");
            return;
        }
        OfflinePlayer p = findPlayer(s, a[1]);
        if (p == null) {
            return;
        }
        UUID u = p.getUniqueId();
        line(s, "&6&lДанные игрока &f" + a[1] + " &8(" + u + ")");
        line(s, "&7Постоянные сердца: &f" + plugin.hearts().get(u) + " &8(" + plugin.hearts().min() + "–" + plugin.hearts().maxFor() + ")");
        line(s, "&7Заряд медного комплекта: &f" + plugin.charges().get(u, ArmorSet.CHARGE_KEY));
        int need = plugin.progress().need();
        for (Weapon w : plugin.items().weapons().values()) {
            int c = plugin.progress().count(u, w.id);
            if (c > 0) {
                line(s, "&7 " + w.plainName() + ": &f" + Math.min(c, need) + "/" + need + (c >= need ? " &a(открыта)" : ""));
            }
        }
    }

    private void progress(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.progress")) {
            return;
        }
        if (a.length < 4) {
            line(s, "&c/legendary progress <игрок> <оружие> <get|set> [число]");
            return;
        }
        OfflinePlayer p = findPlayer(s, a[1]);
        if (p == null) {
            return;
        }
        Weapon w = plugin.items().weapon(a[2].toLowerCase(Locale.ROOT));
        if (w == null) {
            line(s, "&cНет такого оружия. Доступно: &f" + String.join(", ", plugin.items().weapons().keySet()));
            return;
        }
        if (a[3].equalsIgnoreCase("get")) {
            line(s, "&7" + w.plainName() + ": &f" + plugin.progress().count(p.getUniqueId(), w.id) + "/" + plugin.progress().need());
            return;
        }
        Integer n = a.length > 4 ? parseInt(a[4]) : null;
        if (!a[3].equalsIgnoreCase("set") || n == null) {
            line(s, "&c/legendary progress <игрок> <оружие> set <число>");
            return;
        }
        plugin.progress().set(p.getUniqueId(), w.id, n);
        plugin.adminLog(name(s), "progress_set", a[1] + " " + w.id + " = " + n);
        line(s, "&aПрогресс &f" + w.plainName() + " &aдля &f" + a[1] + "&a: &f" + plugin.progress().count(p.getUniqueId(), w.id));
    }

    private void hearts(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.hearts")) {
            return;
        }
        if (a.length < 4) {
            line(s, "&c/legendary hearts <игрок> <set|add|remove> <число>");
            return;
        }
        OfflinePlayer p = findPlayer(s, a[1]);
        Integer n = parseInt(a[3]);
        if (p == null || n == null) {
            if (n == null) {
                line(s, "&cЧисло указано неверно.");
            }
            return;
        }
        int cur = plugin.hearts().get(p.getUniqueId());
        int next = switch (a[2].toLowerCase(Locale.ROOT)) {
            case "set" -> n;
            case "add" -> cur + n;
            case "remove" -> cur - n;
            default -> Integer.MIN_VALUE;
        };
        if (next == Integer.MIN_VALUE) {
            line(s, "&c/legendary hearts <игрок> <set|add|remove> <число>");
            return;
        }
        plugin.hearts().set(p.getUniqueId(), next);
        int now = plugin.hearts().get(p.getUniqueId());
        plugin.adminLog(name(s), "hearts_" + a[2].toLowerCase(Locale.ROOT), a[1] + " " + cur + " -> " + now);
        line(s, "&aСердца &f" + a[1] + "&a: &f" + cur + " &7→ &f" + now
                + (now != next ? " &8(ограничено пределами " + plugin.hearts().min() + "–" + plugin.hearts().maxFor() + ")" : ""));
    }

    // ------------------------------------------------------------ altars

    private AltarManager.Altar targetAltar(CommandSender s) {
        if (!(s instanceof Player p)) {
            line(s, "&cКоманда доступна только игроку.");
            return null;
        }
        Block b = p.getTargetBlockExact(8);
        AltarManager.Altar a = b == null ? null : plugin.altars().at(b);
        if (a == null) {
            line(s, "&cНаведите курсор на центральный блок алтаря (до 8 блоков).");
        }
        return a;
    }

    private void barrier(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.barrier")) {
            return;
        }
        if (!(s instanceof Player p)) {
            line(s, "&cКоманда доступна только игроку.");
            return;
        }
        BarrierManager bm = plugin.barriers();
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "wand" -> {
                RitualManager.give(p, bm.wand());
                line(s, "&aТопор привата выдан: ЛКМ — точка A, ПКМ — точка B.");
            }
            case "pos1", "pos2" -> {
                BarrierManager.Sel sl = bm.sel(p);
                sl.world = p.getWorld().getName();
                org.bukkit.Location l = p.getLocation();
                if (sub.equals("pos1")) {
                    sl.hasA = true;
                    sl.ax = l.getBlockX();
                    sl.ay = l.getBlockY();
                    sl.az = l.getBlockZ();
                } else {
                    sl.hasB = true;
                    sl.bx = l.getBlockX();
                    sl.by = l.getBlockY();
                    sl.bz = l.getBlockZ();
                }
                line(s, "&aТочка задана: &f" + bm.describe(sl));
            }
            case "set" -> {
                if (!bm.setCoords(p, a, 2)) {
                    line(s, "&c/legendary barrier set <x1> <z1> <x2> <z2> [высота_от высота_до]");
                }
            }
            case "wall" -> bm.run(p, BarrierManager.Op.WALL);
            case "fill" -> bm.run(p, BarrierManager.Op.FILL);
            case "clear" -> bm.run(p, BarrierManager.Op.CLEAR_FILL);
            case "list" -> {
                for (BarrierManager.Record r : bm.records()) {
                    line(s, "&6#" + r.id + " &f" + r.mode + " " + r.world + " " + r.x1 + "," + r.z1 + " .. " + r.x2 + "," + r.z2 + " y" + r.y1 + ".." + r.y2);
                }
            }
            case "remove" -> {
                BarrierManager.Record r = a.length > 2 && parseInt(a[2]) != null ? bm.record(parseInt(a[2])) : null;
                if (r == null) {
                    line(s, "&c/legendary barrier remove <id> (список: /legendary barrier list)");
                    return;
                }
                bm.removeRecord(p, r);
            }
            default -> line(s, "&c/legendary barrier <wand|pos1|pos2|set|wall|fill|clear|list|remove>");
        }
    }

    private void dungeon(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.dungeon")) {
            return;
        }
        DungeonManager dm = plugin.dungeons();
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "spawn" -> {
                org.bukkit.World w = s instanceof Player pl ? pl.getWorld() : Bukkit.getWorlds().get(0);
                int x, z;
                String type = null;
                String[] a2 = a;
                if (a.length > 2 && DungeonBuilder.THEMES.containsKey(a[2].toLowerCase(Locale.ROOT))) {
                    type = a[2].toLowerCase(Locale.ROOT);
                    a2 = new String[a.length - 1];
                    a2[0] = a[0];
                    a2[1] = a[1];
                    System.arraycopy(a, 3, a2, 2, a.length - 3);
                }
                if (a2.length > 3 && parseInt(a2[2]) != null && parseInt(a2[3]) != null) {
                    x = parseInt(a2[2]);
                    z = parseInt(a2[3]);
                    if (a2.length > 4 && Bukkit.getWorld(a2[4]) != null) {
                        w = Bukkit.getWorld(a2[4]);
                    }
                } else if (s instanceof Player pl) {
                    x = pl.getLocation().getBlockX();
                    z = pl.getLocation().getBlockZ();
                } else {
                    line(s, "&c/legendary dungeon spawn [" + String.join("|", DungeonBuilder.THEMES.keySet()) + "] <x> <z> [мир]");
                    return;
                }
                dm.spawnAt(s, w, x, z, type);
            }
            case "random" -> {
                Integer n = a.length > 2 ? parseInt(a[2]) : Integer.valueOf(plugin.getConfig().getInt("dungeon.random_count", 3));
                org.bukkit.World w = s instanceof Player pl ? pl.getWorld() : Bukkit.getWorlds().get(0);
                dm.random(s, Math.max(1, Math.min(20, n == null ? 3 : n)), w);
            }
            case "list" -> {
                if (dm.all().isEmpty()) {
                    line(s, "&7Данжей нет. &f/legendary dungeon random");
                }
                for (DungeonManager.Dungeon d : dm.all()) {
                    line(s, "&6#" + d.id + " &e«" + d.title() + "» &7(" + d.itemName() + ") &f" + d.world + " " + d.x + " " + d.y + " " + d.z);
                }
            }
            case "tp" -> {
                DungeonManager.Dungeon d = a.length > 2 && parseInt(a[2]) != null ? dm.byId(parseInt(a[2])) : null;
                if (!(s instanceof Player pl) || d == null || Bukkit.getWorld(d.world) == null) {
                    line(s, "&c/legendary dungeon tp <id> (только игрок)");
                    return;
                }
                pl.teleport(new org.bukkit.Location(Bukkit.getWorld(d.world), d.x + 0.5, d.y + 20, d.z + 22.5));
                line(s, "&aТелепорт к данжу #" + d.id + " (вы над воротами, падайте мягко).");
                Util.addEffect(pl, "slow_falling", 400, 0);
            }
            case "refill" -> {
                DungeonManager.Dungeon d = a.length > 2 && parseInt(a[2]) != null ? dm.byId(parseInt(a[2])) : null;
                if (d == null) {
                    line(s, "&c/legendary dungeon refill <id>");
                    return;
                }
                dm.refill(d);
                line(s, "&aСундуки данжа #" + d.id + " обновлены, босс возвращён.");
            }
            case "remove" -> {
                Integer id = a.length > 2 ? parseInt(a[2]) : null;
                if (id == null || !dm.remove(id)) {
                    line(s, "&c/legendary dungeon remove <id>");
                    return;
                }
                plugin.adminLog(name(s), "dungeon_remove", "#" + id);
                line(s, "&aДанж #" + id + " убран из списка (постройка в мире остаётся — снесите её WorldEdit-ом).");
            }
            default -> line(s, "&c/legendary dungeon <spawn|random|list|tp|refill|remove>");
        }
    }

    private void altar(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.altar")) {
            return;
        }
        if (a.length < 2) {
            line(s, "&c/legendary altar <create|build|remove|list|validate|cancel>");
            return;
        }
        AltarManager am = plugin.altars();
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "create", "build" -> {
                if (!(s instanceof Player p)) {
                    line(s, "&cКоманда доступна только игроку.");
                    return;
                }
                String type = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                AltarManager.Theme t = am.theme(type);
                if (t == null || plugin.items().ritualItem(type) == null) {
                    line(s, "&cУкажите тип алтаря: &f" + String.join(", ", plugin.items().ritualItems().keySet()));
                    return;
                }
                Block target = p.getTargetBlockExact(8);
                if (target == null) {
                    line(s, "&cНаведите курсор на блок.");
                    return;
                }
                Block center;
                if (a[1].equalsIgnoreCase("build")) {
                    // курсор на земле: строится большой алтарь, центральный блок появляется на возвышении
                    if (am.at(target) != null) {
                        line(s, "&cЗдесь уже есть алтарь.");
                        return;
                    }
                    center = AltarBuilder.build(plugin, target, t, type);
                } else {
                    center = target;
                }
                AltarManager.Altar created = am.create(type, center);
                if (created == null) {
                    line(s, "&cНе удалось зарегистрировать алтарь (уже существует?).");
                    return;
                }
                plugin.adminLog(name(s), "altar_" + a[1].toLowerCase(Locale.ROOT), type + " #" + created.id);
                line(s, "&aАлтарь &f#" + created.id + " &a(" + type + ") зарегистрирован.");
                List<String> problems = am.validate(created);
                for (String pr : problems) {
                    line(s, "&e - " + pr);
                }
                if (problems.isEmpty()) {
                    line(s, "&aКонструкция в порядке.");
                }
            }
            case "spawn" -> {
                String type = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                if (am.theme(type) == null || plugin.items().ritualItem(type) == null) {
                    line(s, "&cУкажите тип алтаря: &f" + String.join(", ", plugin.items().ritualItems().keySet()));
                    return;
                }
                org.bukkit.World w = s instanceof Player pl ? pl.getWorld() : Bukkit.getWorlds().get(0);
                int x, z;
                if (a.length > 4 && parseInt(a[3]) != null && parseInt(a[4]) != null) {
                    x = parseInt(a[3]);
                    z = parseInt(a[4]);
                    if (a.length > 5 && Bukkit.getWorld(a[5]) != null) {
                        w = Bukkit.getWorld(a[5]);
                    }
                } else if (s instanceof Player pl) {
                    x = pl.getLocation().getBlockX();
                    z = pl.getLocation().getBlockZ();
                } else {
                    line(s, "&c/legendary altar spawn <тип> <x> <z> [мир]");
                    return;
                }
                AltarManager.Altar created = plugin.spawner().spawnAt(s, type, w, x, z);
                if (created == null) {
                    line(s, "&cНе удалось построить алтарь.");
                } else {
                    line(s, "&aАлтарь &f#" + created.id + " &a(" + type + ") построен: &f" + created.x + " " + created.y + " " + created.z);
                }
            }
            case "random" -> {
                List<String> types = AltarSpawner.typeList(plugin, a.length > 2 ? a[2] : null);
                if (types.isEmpty()) {
                    line(s, "&cУкажите тип или all. Типы: &f" + String.join(", ", plugin.items().ritualItems().keySet()));
                    return;
                }
                // тип, у которого уже есть алтарь, пропускаем
                types.removeIf(tp -> am.all().stream().anyMatch(x -> x.type.equals(tp)));
                if (types.isEmpty()) {
                    line(s, "&eАлтари этих типов уже стоят. Удалите старый (&f/legendary altar remove&e) или используйте spawn.");
                    return;
                }
                org.bukkit.World w = a.length > 3 && Bukkit.getWorld(a[3]) != null ? Bukkit.getWorld(a[3])
                        : s instanceof Player pl ? pl.getWorld() : Bukkit.getWorlds().get(0);
                plugin.spawner().random(s, types, w);
            }
            case "tp" -> {
                if (!(s instanceof Player pl)) {
                    line(s, "&cКоманда доступна только игроку.");
                    return;
                }
                Integer id = a.length > 2 ? parseInt(a[2]) : null;
                AltarManager.Altar al = id == null ? null : am.byId(id);
                if (al == null || al.location() == null) {
                    line(s, "&cАлтарь не найден. &f/legendary altar list");
                    return;
                }
                pl.teleport(al.location().add(0.5, 3, 0.5 + AltarBuilder.radius(plugin) - 2));
                line(s, "&aТелепорт к алтарю #" + al.id + " (" + al.type + ").");
            }
            case "remove" -> {
                AltarManager.Altar al = targetAltar(s);
                if (al != null) {
                    am.remove(al);
                    plugin.adminLog(name(s), "altar_remove", al.type + " #" + al.id);
                    line(s, "&aАлтарь #" + al.id + " удалён из реестра (блоки остались).");
                }
            }
            case "cancel" -> {
                AltarManager.Altar al = targetAltar(s);
                if (al != null) {
                    boolean ok = plugin.rituals().cancel(al, name(s));
                    line(s, ok ? "&aРитуал отменён, материалы возвращены." : "&7На этом алтаре нет активного ритуала.");
                }
            }
            case "validate" -> {
                AltarManager.Altar al = targetAltar(s);
                if (al != null) {
                    List<String> pr = am.validate(al);
                    line(s, pr.isEmpty() ? "&aАлтарь #" + al.id + " в порядке." : "&cПроблемы алтаря #" + al.id + ":");
                    for (String x : pr) {
                        line(s, "&e - " + x);
                    }
                }
            }
            case "list" -> {
                if (am.all().isEmpty()) {
                    line(s, "&7Алтарей нет.");
                    return;
                }
                for (AltarManager.Altar al : am.all()) {
                    int problems = am.validate(al).size();
                    boolean busy = plugin.rituals().active(al.id) != null;
                    line(s, "&e#" + al.id + " &f" + al.type + " &7" + al.world + " " + al.x + " " + al.y + " " + al.z
                            + (problems == 0 ? " &aOK" : " &c(проблем: " + problems + ")") + (busy ? " &6[ритуал]" : ""));
                }
            }
            default -> line(s, "&c/legendary altar <create|build|remove|list|validate|cancel>");
        }
    }

    // ------------------------------------------------------------ ban log / unban / unique

    private void banlog(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.banlog")) {
            return;
        }
        String sql = "SELECT id, ts, killer_name, victim_name, world, x, y, z, unbanned FROM ban_log"
                + (a.length > 1 ? " WHERE lower(victim_name)=lower(?) OR lower(killer_name)=lower(?)" : "")
                + " ORDER BY id DESC LIMIT 15";
        Object[] params = a.length > 1 ? new Object[]{a[1], a[1]} : new Object[0];
        List<String> rows = plugin.db().query(sql, rs -> String.format(Locale.ROOT,
                "&e#%d &7%s &f%s &7убит игроком &f%s &8(%s %.0f %.0f %.0f)%s",
                rs.getInt(1), date(rs.getLong(2)), rs.getString(4), rs.getString(3),
                rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                rs.getInt(9) == 1 ? " &a[разбанен]" : ""), params);
        if (rows.isEmpty()) {
            line(s, "&7Записей нет.");
            return;
        }
        line(s, "&6Журнал Молота бана (последние 15):");
        rows.forEach(r -> line(s, r));
    }

    private void unban(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.unban")) {
            return;
        }
        if (a.length < 2) {
            line(s, "&c/legendary unban <ник>");
            return;
        }
        String cmd = plugin.getConfig().getString("items.ban_hammer.unban_command", "pardon {player}")
                .replace("{player}", a[1]);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        plugin.db().update("UPDATE ban_log SET unbanned=1 WHERE lower(victim_name)=lower(?)", a[1]);
        plugin.adminLog(name(s), "unban", a[1]);
        line(s, "&aВыполнено: &f/" + cmd);
    }

    private void unique(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.unique")) {
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("reset") && a.length > 2) {
            int n = plugin.rituals().removeFromMap(a[2].toLowerCase(Locale.ROOT), name(s));
            line(s, "&aОружие &f" + a[2] + " &aудалено с карты (экземпляров: " + n + "). Его можно создать снова.");
            return;
        }
        if (sub.equals("limit") && a.length > 3 && parseInt(a[3]) != null) {
            plugin.rituals().setLimit(a[2].toLowerCase(Locale.ROOT), parseInt(a[3]));
            plugin.adminLog(name(s), "limit_set", a[2] + " = " + a[3]);
            line(s, "&aЛимит &f" + a[2] + " &aна карте: &f" + parseInt(a[3]));
            return;
        }
        for (Weapon w : plugin.items().weapons().values()) {
            line(s, "&e" + w.id + " &7на карте: &f" + plugin.rituals().copies(w.id) + " &7лимит: &f" + plugin.rituals().limit(w.id));
        }
    }

    // ------------------------------------------------------------ cooldown

    private List<String> cooldownIds() {
        List<String> ids = new ArrayList<>(plugin.items().weapons().keySet());
        ids.add("copper_set");
        return ids;
    }

    private void cooldown(CommandSender s, String[] a) {
        if (!has(s, "legendary.command.cooldown")) {
            return;
        }
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) {
            line(s, "&6Перезарядки (секунды):");
            for (String id : cooldownIds()) {
                int p1 = plugin.getConfig().getInt("abilities.cooldowns." + id + ".primary", 30);
                int p2 = plugin.getConfig().getInt("abilities.cooldowns." + id + ".secondary", 30);
                line(s, "&e" + id + " &7первая: &f" + p1 + " &7вторая: &f" + (id.equals("copper_set") ? "-" : String.valueOf(p2)));
            }
            return;
        }
        if (a[1].equalsIgnoreCase("reset")) {
            if (a.length < 3) {
                line(s, "&c/legendary cooldown reset <игрок>");
                return;
            }
            OfflinePlayer p = findPlayer(s, a[2]);
            if (p != null) {
                plugin.cooldowns().clearPlayer(p.getUniqueId());
                plugin.adminLog(name(s), "cooldown_reset", a[2]);
                line(s, "&aПерезарядки игрока &f" + a[2] + " &aсброшены.");
            }
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        if (!cooldownIds().contains(id)) {
            line(s, "&cНеизвестный id. Доступно: &f" + String.join(", ", cooldownIds()));
            return;
        }
        if (a.length < 4) {
            line(s, "&c/legendary cooldown <id> <primary|secondary|both> <секунды>");
            return;
        }
        String slot = a[2].toLowerCase(Locale.ROOT);
        Integer secs = parseInt(a[3]);
        if (secs == null || secs < 0 || secs > 86400 || !List.of("primary", "secondary", "both").contains(slot)) {
            line(s, "&c/legendary cooldown <id> <primary|secondary|both> <секунды 0-86400>");
            return;
        }
        if (id.equals("copper_set") && slot.equals("secondary")) {
            line(s, "&cУ медного комплекта одна способность — используйте primary.");
            return;
        }
        if (!slot.equals("secondary")) {
            plugin.getConfig().set("abilities.cooldowns." + id + ".primary", secs);
        }
        if (!slot.equals("primary") && !id.equals("copper_set")) {
            plugin.getConfig().set("abilities.cooldowns." + id + ".secondary", secs);
        }
        plugin.saveConfig();
        plugin.adminLog(name(s), "cooldown_set", id + " " + slot + " = " + secs + "s");
        line(s, "&aПерезарядка &f" + id + " &7(" + slot + ") &aтеперь &f" + secs + " с&a. Действует со следующего применения.");
    }

    // ------------------------------------------------------------ tab completion

    private static List<String> filter(List<String> all, String prefix) {
        return all.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private static List<String> players() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            return filter(SUBS, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (a.length == 2) {
                    return filter(players(), a[1]);
                }
                if (a.length == 3) {
                    return filter(allIds(), a[2]);
                }
            }
            case "info", "banlog", "unban", "revive" -> {
                if (a.length == 2) {
                    return filter(players(), a[1]);
                }
            }
            case "progress" -> {
                if (a.length == 2) {
                    return filter(players(), a[1]);
                }
                if (a.length == 3) {
                    return filter(new ArrayList<>(plugin.items().weapons().keySet()), a[2]);
                }
                if (a.length == 4) {
                    return filter(List.of("get", "set"), a[3]);
                }
            }
            case "hearts" -> {
                if (a.length == 2) {
                    return filter(players(), a[1]);
                }
                if (a.length == 3) {
                    return filter(List.of("set", "add", "remove"), a[2]);
                }
            }
            case "altar" -> {
                if (a.length == 2) {
                    return filter(ALTAR_SUBS, a[1]);
                }
                if (a.length == 3 && (a[1].equalsIgnoreCase("create") || a[1].equalsIgnoreCase("build")
                        || a[1].equalsIgnoreCase("spawn") || a[1].equalsIgnoreCase("random"))) {
                    List<String> ids = new ArrayList<>(plugin.items().ritualItems().keySet());
                    if (a[1].equalsIgnoreCase("random")) {
                        ids.add("all");
                    }
                    return filter(ids, a[2]);
                }
                if (a.length == 3 && a[1].equalsIgnoreCase("tp")) {
                    return filter(plugin.altars().all().stream().map(x -> String.valueOf(x.id)).collect(Collectors.toList()), a[2]);
                }
            }
            case "barrier" -> {
                if (a.length == 2) {
                    return filter(List.of("wand", "pos1", "pos2", "set", "wall", "fill", "clear", "list", "remove"), a[1]);
                }
            }
            case "dungeon" -> {
                if (a.length == 2) {
                    return filter(List.of("spawn", "random", "list", "tp", "refill", "remove"), a[1]);
                }
                if (a.length == 3 && a[1].equalsIgnoreCase("spawn")) {
                    return filter(new ArrayList<>(DungeonBuilder.THEMES.keySet()), a[2]);
                }
                if (a.length == 3 && List.of("tp", "refill", "remove").contains(a[1].toLowerCase(Locale.ROOT))) {
                    return filter(plugin.dungeons().all().stream().map(x -> String.valueOf(x.id)).collect(Collectors.toList()), a[2]);
                }
            }
            case "cooldown" -> {
                if (a.length == 2) {
                    List<String> opts = new ArrayList<>(cooldownIds());
                    opts.add("list");
                    opts.add("reset");
                    return filter(opts, a[1]);
                }
                if (a.length == 3 && a[1].equalsIgnoreCase("reset")) {
                    return filter(players(), a[2]);
                }
                if (a.length == 3) {
                    return filter(List.of("primary", "secondary", "both"), a[2]);
                }
            }
            case "unique" -> {
                if (a.length == 2) {
                    return filter(List.of("list", "reset", "limit"), a[1]);
                }
                if (a.length == 3) {
                    return filter(new ArrayList<>(plugin.items().weapons().keySet()), a[2]);
                }
            }
            default -> {
            }
        }
        return List.of();
    }
}
