package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Меню администратора. Открывается предметом «Звезда управления» (ПКМ) или командой /legendary menu.
 * Разделы: оружие, предметы, прогресс убийств, сердца, алтари, инструменты. Цель действий (кому выдавать,
 * чей прогресс менять) выбирается кнопкой «Цель» (по умолчанию — сам администратор).
 */
public final class AdminMenu implements Listener {

    public static final String ID = "admin_menu";
    private static final String PERM = "legendary.command.menu";

    private enum Page { MAIN, WEAPONS, ITEMS, PROGRESS, HEARTS, ALTARS, TOOLS, PLAYERS, COPIES, ARMOR, DUNGEONS, BARRIER, I_ARMOR, I_COMP, I_BOOKS, I_MISC }

    private static final class Holder implements InventoryHolder {
        final Page page;
        Inventory inv;

        Holder(Page page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LegendaryPlugin plugin;
    /** Выбранная цель действий для каждого администратора. */
    private final Map<UUID, UUID> targets = new HashMap<>();
    private String pendingDelete;
    private long pendingAt;

    public AdminMenu(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ предмет

    public boolean is(ItemStack s) {
        return ("f:" + ID).equals(plugin.items().typeId(s));
    }

    public ItemStack create() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color("&6&lЗвезда управления"));
        m.setLore(List.of(Util.color("&7ПКМ — открыть меню администратора:"),
                Util.color("&7оружие, предметы, прогресс, сердца, алтари."),
                Util.color("&8Только для администраторов")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(plugin.keyId, PersistentDataType.STRING, "f:" + ID);
        plugin.items().applyModel(m, ID);
        it.setItemMeta(m);
        return it;
    }

    private boolean allowed(Player p) {
        return p.hasPermission(PERM) || p.hasPermission("legendary.admin");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !is(e.getItem())) {
            return;
        }
        e.setCancelled(true);
        if (!allowed(e.getPlayer())) {
            e.getPlayer().sendMessage(Util.color("&cЭтот предмет работает только у администраторов."));
            return;
        }
        open(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        targets.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ построение страниц

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Util.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) {
            for (String part : wrap(s, 34)) {
                l.add(Util.color(part));
            }
        }
        meta.setLore(l);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** Иконка оружия с моделью из ресурспака (если пак включён), для списков вместо ванильного предмета. */
    private ItemStack wicon(Weapon w, String name, String... lore) {
        ItemStack it = icon(w.material, name, lore);
        if (!(w instanceof ArmorPiece)) {
            ItemMeta m = it.getItemMeta();
            plugin.items().applyModel(m, w.id);
            it.setItemMeta(m);
        }
        return it;
    }

    /** Кнопка главной страницы с подписью-табличкой под ней. */
    private void mainBtn(Holder h, int slot, Material m, String name, String caption, Material pane, String... lore) {
        String[] l = new String[lore.length + 1];
        System.arraycopy(lore, 0, l, 0, lore.length);
        l[lore.length] = "&aНажмите, чтобы открыть";
        h.inv.setItem(slot, icon(m, name, l));
        h.inv.setItem(slot + 9, icon(pane, "&f▲ " + caption, lore));
    }

    /** Кнопка «Как пользоваться» с описанием действий раздела. */
    private static ItemStack help(String... lines) {
        String[] all = new String[lines.length + 1];
        System.arraycopy(lines, 0, all, 0, lines.length);
        all[lines.length] = "&aНажмите — отправить описание в чат";
        return icon(Material.WRITABLE_BOOK, "&e&lКак пользоваться", all);
    }

    /** Перенос длинных строк, чтобы описание не вылезало за поля подсказки; цвет переносится на следующую строку. */
    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add(text);
            return out;
        }
        String color = "";
        java.util.regex.Matcher lead = java.util.regex.Pattern.compile("^((?:[&§][0-9a-fk-or])+)").matcher(text);
        if (lead.find()) {
            color = lead.group(1);
        }
        StringBuilder cur = new StringBuilder();
        int visible = 0;
        for (String word : text.split(" ")) {
            int len = word.replaceAll("[&§][0-9a-fk-or]", "").length();
            if (visible > 0 && visible + 1 + len > width) {
                out.add(cur.toString());
                cur = new StringBuilder(color);
                visible = 0;
            }
            if (visible > 0) {
                cur.append(' ');
                visible++;
            }
            cur.append(word);
            visible += len;
        }
        out.add(cur.toString());
        return out;
    }

    /** Копия предмета для показа в меню: без служебной метки, чтобы её нельзя было принять за настоящую. */
    private ItemStack display(ItemStack it, String... extraLore) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().remove(plugin.keyId);
        m.getPersistentDataContainer().remove(plugin.keyInstance);
        List<String> lore = new ArrayList<>();
        if (m.getLore() != null) {
            for (String x : m.getLore()) {
                lore.addAll(wrap(x, 34));
            }
        }
        for (String x : extraLore) {
            for (String part : wrap(x, 34)) {
                lore.add(Util.color(part));
            }
        }
        m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    private Player targetOf(Player admin) {
        UUID t = targets.get(admin.getUniqueId());
        Player p = t == null ? null : Bukkit.getPlayer(t);
        return p == null ? admin : p;
    }

    private ItemStack targetIcon(Player admin) {
        Player t = targetOf(admin);
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(t);
        sm.setDisplayName(Util.color("&eЦель: &f" + t.getName()));
        sm.setLore(List.of(Util.color("&7Кому выдаются предметы и чей"), Util.color("&7прогресс/сердца меняются."),
                Util.color("&aНажмите, чтобы выбрать другого игрока")));
        it.setItemMeta(sm);
        return it;
    }

    private Holder holder(Page page, int size, String title) {
        Holder h = new Holder(page);
        h.inv = Bukkit.createInventory(h, size, Util.color(title));
        return h;
    }

    private void fillFrame(Inventory inv) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
    }

    public void open(Player p) {
        show(p, Page.MAIN);
    }

    private void show(Player p, Page page) {
        Holder h;
        switch (page) {
            case MAIN -> {
                h = holder(page, 54, "&6&lУправление &8— главное меню");
                fillFrame(h.inv);
                // каждая кнопка подписана табличкой ниже (клик по табличке открывает тот же раздел)
                mainBtn(h, 1, Material.NETHERITE_SWORD, "&6Легендарное оружие", "Оружие", Material.ORANGE_STAINED_GLASS_PANE,
                        "&7Выдать любое из 12 видов цели");
                mainBtn(h, 3, Material.CHEST, "&bПредметы", "Предметы", Material.ORANGE_STAINED_GLASS_PANE,
                        "&7Медная броня, ведро еды, компоненты крафта");
                mainBtn(h, 5, Material.EXPERIENCE_BOTTLE, "&aПрогресс оружия", "Прогресс", Material.LIME_STAINED_GLASS_PANE,
                        "&7Убийства для открытия второй способности");
                mainBtn(h, 7, Material.COPPER_CHESTPLATE, "&6Прогресс брони", "Заряд брони", Material.LIME_STAINED_GLASS_PANE,
                        "&7Заряд медного комплекта цели");
                mainBtn(h, 19, Material.RED_DYE, "&cСердца", "Сердца", Material.LIME_STAINED_GLASS_PANE,
                        "&7Изменить число сердец цели, вернуть выбывшего");
                mainBtn(h, 21, Material.LODESTONE, "&dАлтари", "Алтари", Material.PURPLE_STAINED_GLASS_PANE,
                        "&7Построить, расставить по карте, телепорт");
                mainBtn(h, 23, Material.STONE_BRICKS, "&6Данжи", "Данжи", Material.PURPLE_STAINED_GLASS_PANE,
                        "&7Замки с боссом и компонентами крафта");
                mainBtn(h, 25, Material.BARRIER, "&5Поставить барьер", "Барьеры", Material.PURPLE_STAINED_GLASS_PANE,
                        "&7Невидимые стены: по координатам или топором привата");
                mainBtn(h, 37, Material.IRON_BARS, "&4Экземпляры на карте", "Лимит копий", Material.RED_STAINED_GLASS_PANE,
                        "&7Лимит копий каждого оружия и удаление с карты");
                mainBtn(h, 39, Material.COMMAND_BLOCK, "&eИнструменты", "Инструменты", Material.RED_STAINED_GLASS_PANE,
                        "&7Перезагрузка, сброс перезарядок, журнал");
                ItemStack tgt = targetIcon(p);
                h.inv.setItem(41, tgt);
                h.inv.setItem(50, icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b▲ Цель", "&7Нажмите — выбрать другого игрока"));
                h.inv.setItem(43, display(create(), "", "&aНажмите — получить ещё одну"));
                h.inv.setItem(52, icon(Material.YELLOW_STAINED_GLASS_PANE, "&e▲ Звезда", "&7Нажмите — выдать себе звезду управления"));
                h.inv.setItem(8, help("Каждая кнопка подписана табличкой под ней. Нажмите на кнопку или табличку, чтобы открыть раздел.",
                        "Оранжевые — выдача предметов, зелёные — прогресс и сердца игрока, фиолетовые — мир (алтари, данжи, барьеры), красные — сервисные.",
                        "Голова «Цель» — игрок, которому выдаются предметы и чей прогресс/сердца меняются (по умолчанию вы)."));
            }
            case BARRIER -> {
                h = holder(page, 54, "&5Барьеры");
                fillFrame(h.inv);
                BarrierManager.Sel sl = plugin.barriers().sel(p);
                h.inv.setItem(11, icon(Material.GOLDEN_AXE, "&6Топор привата", "&7Выбор области кликами по блокам:", "&7ЛКМ — точка A, ПКМ — точка B", "&aНажмите — получить топор"));
                h.inv.setItem(20, icon(Material.YELLOW_STAINED_GLASS_PANE, "&6▲ Способ Б: топором", "&7Возьмите топор и кликните по двум углам"));
                h.inv.setItem(13, icon(Material.COMPASS, "&bПо координатам", "&7Вводите в чат: x1 z1 x2 z2 [высота_от высота_до]", "&aНажмите — ввести координаты"));
                h.inv.setItem(22, icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b▲ Способ А: координаты", "&7Область задаётся числами из чата"));
                h.inv.setItem(15, icon(Material.FILLED_MAP, "&eВыделение", "&7" + plugin.barriers().describe(sl),
                        "&7Высота: &f" + (sl.fullHeight ? "вся высота мира" : "между высотами точек"),
                        "&aЛКМ — переключить высоту", "&cПКМ — сбросить выделение"));
                h.inv.setItem(24, icon(Material.ORANGE_STAINED_GLASS_PANE, "&e▲ Выделение", "&7Что выбрано сейчас"));
                h.inv.setItem(29, icon(Material.IRON_BARS, "&aСтена по периметру", "&7Барьеры только по границе области", "&7(ставятся лишь в воздух)", "&aНажмите — построить"));
                h.inv.setItem(38, icon(Material.LIME_STAINED_GLASS_PANE, "&a▲ Стена", "&7Обнести область невидимой стеной"));
                h.inv.setItem(31, icon(Material.BARRIER, "&aСплошной объём", "&7Заполнить всю область барьерами", "&7(ставятся лишь в воздух)", "&aНажмите — построить"));
                h.inv.setItem(40, icon(Material.LIME_STAINED_GLASS_PANE, "&a▲ Объём", "&7Закрыть область целиком"));
                h.inv.setItem(33, icon(Material.STRUCTURE_VOID, "&cУбрать барьеры", "&7Удалить все барьеры внутри области", "&cНажмите — убрать"));
                h.inv.setItem(42, icon(Material.RED_STAINED_GLASS_PANE, "&c▲ Убрать", "&7Стереть барьеры в выделении"));
                int[] rs = {45, 46, 47, 48, 50, 51, 52, 53};
                int ri = 0;
                for (BarrierManager.Record r : plugin.barriers().records()) {
                    if (ri >= rs.length) {
                        break;
                    }
                    h.inv.setItem(rs[ri++], icon(r.mode.equals("wall") ? Material.IRON_BARS : Material.BARRIER, "&e#" + r.id + " &f" + (r.mode.equals("wall") ? "стена" : "объём"),
                            "&7" + r.world + " " + r.x1 + "," + r.z1 + " .. " + r.x2 + "," + r.z2, "&7высота " + r.y1 + ".." + r.y2,
                            "&aЛКМ — телепорт", "&cShift+ПКМ — убрать эти барьеры"));
                }
                h.inv.setItem(49, icon(Material.ARROW, "&7Назад"));
                h.inv.setItem(8, help("Барьер — невидимый блок, через который нельзя пройти (виден, если держать его в руке).",
                        "Способ А: нажмите «По координатам» и введите в чат: x1 z1 x2 z2 (и при желании высоту от/до).",
                        "Способ Б: возьмите Топор привата — ЛКМ по блоку задаёт точку A, ПКМ — точку B.",
                        "Затем выберите: стена по периметру, сплошной объём или убрать барьеры. Ставятся только в воздух, ничего не ломая.",
                        "Нижний ряд — последние постройки: ЛКМ — телепорт, Shift+ПКМ — убрать."));
            }
            case COPIES -> {
                h = holder(page, 27, "&4Лимит копий");
                fillFrame(h.inv);
                int i = 9;
                for (Weapon w : plugin.items().weapons().values()) {
                    h.inv.setItem(i++, wicon(w, w.name,
                            "&7На карте сейчас: &f" + plugin.rituals().copies(w.id),
                            "&7Лимит (макс. на карте): &f" + plugin.rituals().limit(w.id),
                            "", "&aЛКМ — лимит +1 (добавить)", "&cПКМ — лимит -1 (убавить)",
                            "&4Shift+ПКМ дважды за 6 с — удалить ВСЕ копии с карты"));
                }
                h.inv.setItem(4, help("Лимит — сколько копий этого оружия может существовать на карте одновременно.",
                        "ЛКМ по оружию — лимит +1, ПКМ — лимит -1.",
                        "Shift+ПКМ, затем ещё раз Shift+ПКМ в течение 6 секунд — удалить все копии оружия с карты (у игроков, в сундуках, на земле).",
                        "Пример: для тестов поставьте лимит 2."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case WEAPONS -> {
                h = holder(page, 27, "&6Оружие");
                fillFrame(h.inv);
                int i = 0;
                for (Weapon w : plugin.items().weapons().values()) {
                    ItemStack it = display(plugin.items().createWeapon(w.id), "", "&aЛКМ — выдать: &f" + targetOf(p).getName(),
                            plugin.rituals().limit(w.id) > 1 ? "&8Лимит копий: " + plugin.rituals().limit(w.id) : "&8Одна копия на карте");
                    h.inv.setItem(i + 9, it);
                    i++;
                }
                h.inv.setItem(4, help("ЛКМ по оружию — выдать его цели (она указана в подсказке оружия).",
                        "Админская выдача обходит правило «одна копия на карте», запись попадает в журнал."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case ITEMS -> {
                h = holder(page, 27, "&bПредметы &8— категории");
                fillFrame(h.inv);
                h.inv.setItem(10, icon(Material.COPPER_CHESTPLATE, "&6Медная броня", "&7Комплект и отдельные части"));
                h.inv.setItem(12, icon(Material.IRON_INGOT, "&7Компоненты крафта", "&7Навершие, голова топора, основа кинжала, пластина"));
                h.inv.setItem(14, icon(Material.WRITTEN_BOOK, "&eКниги рецептов", "&7Полная книга и чертежи по компонентам"));
                h.inv.setItem(16, icon(Material.BUNDLE, "&dПрочее", "&7Ведро еды, мешочек, Звезда, Топор привата"));
                h.inv.setItem(4, help("Выберите категорию, затем ЛКМ по предмету — выдать его цели."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case I_ARMOR, I_COMP, I_BOOKS, I_MISC -> {
                String t0 = switch (page) {
                    case I_ARMOR -> "&6Медная броня";
                    case I_COMP -> "&7Компоненты крафта";
                    case I_BOOKS -> "&eКниги рецептов";
                    default -> "&dПрочее";
                };
                h = holder(page, 27, t0);
                fillFrame(h.inv);
                int[] pos = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25};
                List<ItemStack> es = entries(page);
                for (int i = 0; i < es.size() && i < pos.length; i++) {
                    h.inv.setItem(pos[i], display(es.get(i), "", "&aЛКМ — выдать: &f" + targetOf(p).getName()));
                }
                h.inv.setItem(4, help("ЛКМ по предмету — выдать его цели (указана в подсказке).",
                        "Запись о выдаче попадает в журнал."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад к категориям"));
            }
            case PROGRESS -> {
                h = holder(page, 27, "&aПрогресс оружия");
                fillFrame(h.inv);
                Player t = targetOf(p);
                int need = plugin.progress().need();
                int i = 9;
                for (Weapon w : plugin.items().weapons().values()) {
                    int c = plugin.progress().count(t.getUniqueId(), w.id);
                    h.inv.setItem(i++, wicon(w, w.name, "&7Игрок: &f" + t.getName(),
                            "&7Прогресс: &f" + Math.min(c, need) + "/" + need + (c >= need ? " &a(открыта)" : ""),
                            "", "&aПКМ — +1 (добавить)", "&cЛКМ — -1 (убрать)",
                            "&eShift+ПКМ — открыть вторую способность (" + need + ")", "&7Shift+ЛКМ — сбросить в 0"));
                }
                h.inv.setItem(8, help("Прогресс убийств этим оружием открывает вторую способность (нужно " + need + ").",
                        "ПКМ по оружию — +1, ЛКМ — -1.", "Shift+ПКМ — сразу открыть, Shift+ЛКМ — сбросить в 0.",
                        "Голова — выбрать другого игрока. В чат приходит сообщение об изменении."));
                h.inv.setItem(4, targetIcon(p));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case HEARTS -> {
                h = holder(page, 27, "&cСердца");
                fillFrame(h.inv);
                Player t = targetOf(p);
                int cur = plugin.hearts().get(t.getUniqueId());
                h.inv.setItem(4, targetIcon(p));
                h.inv.setItem(11, icon(Material.RED_CONCRETE, "&c-1 сердце", "&7Сейчас: &f" + cur));
                h.inv.setItem(13, icon(Material.RED_DYE, "&fСейчас: " + cur, "&7Стандарт: " + plugin.hearts().defaultHearts(),
                        "&aНажмите — сбросить до стандарта"));
                h.inv.setItem(15, icon(Material.LIME_CONCRETE, "&a+1 сердце", "&7Сейчас: &f" + cur));
                if (plugin.hearts().isEliminated(t.getUniqueId())) {
                    h.inv.setItem(20, icon(Material.TOTEM_OF_UNDYING, "&aВернуть в игру", "&7Игрок выбыл (Похититель жизней)",
                            "&aНажмите, чтобы воскресить"));
                }
                h.inv.setItem(8, help("Красный блок — убрать 1 сердце цели, зелёный — добавить 1.",
                        "Центральная кнопка — вернуть стандартное число сердец.",
                        "Тотем (если игрок выбыл) — вернуть его в игру."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case ALTARS -> {
                h = holder(page, 54, "&dАлтари");
                fillFrame(h.inv);
                List<Weapon> rl = new ArrayList<>(plugin.items().ritualItems().values());
                h.inv.setItem(13, icon(Material.COPPER_INGOT, "&6Алтари брони ▶", "&7Справа — алтари медного комплекта"));
                for (int i = 0; i < rl.size(); i++) {
                    Weapon w = rl.get(i);
                    boolean exists = plugin.altars().all().stream().anyMatch(a -> a.type.equals(w.id));
                    h.inv.setItem(altarSlot(rl, i), wicon(w, w.altarName, "&7Тип: &f" + w.id,
                            exists ? "&eАлтарь этого типа уже есть" : "&7Алтаря пока нет",
                            "&aЛКМ — построить на вашем месте", "&bПКМ — случайное место на карте"));
                }
                h.inv.setItem(45, icon(Material.ENDER_EYE, "&bРасставить ВСЕ случайно", "&7Только типы, у которых ещё нет алтаря"));
                h.inv.setItem(53, help("Верхний ряд — типы алтарей: ЛКМ — построить алтарь на вашем месте, ПКМ — в случайном месте карты.",
                        "Кнопка с глазом — расставить все недостающие типы случайно.",
                        "Список ниже — существующие алтари: ЛКМ — телепорт к алтарю."));
                h.inv.setItem(49, icon(Material.ARROW, "&7Назад"));
                int slot = 18;
                for (AltarManager.Altar a : plugin.altars().all()) {
                    if (slot > 44) {
                        break;
                    }
                    h.inv.setItem(slot++, icon(Material.LODESTONE, "&e#" + a.id + " &f" + a.type,
                            "&7" + a.world + " " + a.x + " " + a.y + " " + a.z, "&aЛКМ — телепорт"));
                }
            }
            case ARMOR -> {
                h = holder(page, 27, "&6Прогресс брони");
                fillFrame(h.inv);
                Player t = targetOf(p);
                int max = (int) plugin.getConfig().getDouble("copper_armor.chestplate.max_charges", 10);
                int need = (int) plugin.getConfig().getDouble("copper_armor.set.min_charges", 5);
                int cur = plugin.charges().get(t.getUniqueId(), ArmorSet.CHARGE_KEY);
                h.inv.setItem(4, targetIcon(p));
                ItemStack ch = plugin.items().createArmor("copper_chestplate");
                if (ch != null) {
                    h.inv.setItem(13, display(ch, "", "&7Игрок: &f" + t.getName(),
                            "&7Заряд комплекта: &f" + cur + "/" + max + " &8(для способности нужно " + need + ")",
                            "", "&aПКМ — +1", "&cЛКМ — -1", "&eShift+ПКМ — максимум (" + max + ")", "&7Shift+ЛКМ — сбросить в 0"));
                }
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case TOOLS -> {
                h = holder(page, 27, "&eИнструменты");
                fillFrame(h.inv);
                h.inv.setItem(11, icon(Material.REPEATER, "&aПерезагрузить config.yml", "&aЛКМ — перечитать настройки без перезапуска"));
                h.inv.setItem(13, icon(Material.CLOCK, "&bСбросить перезарядки цели", "&7Цель: &f" + targetOf(p).getName(), "&aЛКМ — обнулить все перезарядки способностей"));
                h.inv.setItem(15, icon(Material.BOOK, "&7Журнал", "&aЛКМ — показать в чате последние действия админов и баны Молота"));
                h.inv.setItem(8, help("Здесь общие действия: перезагрузка настроек и сброс перезарядок игрока-цели.",
                        "Данжи вынесены в отдельный раздел главного меню."));
                h.inv.setItem(8, help("Заряд медного комплекта цели нужен для способности брони.",
                        "ПКМ по предмету — +1, ЛКМ — -1.", "Shift+ПКМ — максимум, Shift+ЛКМ — сбросить в 0."));
                h.inv.setItem(22, icon(Material.ARROW, "&7Назад"));
            }
            case DUNGEONS -> {
                h = holder(page, 54, "&6Данжи");
                fillFrame(h.inv);
                h.inv.setItem(2, icon(Material.STONE_BRICKS, "&6Построить под вами", "&aЛКМ — замок со случайным предметом", "&7(выбирается тот, которого меньше)"));
                int ti = 10;
                for (java.util.Map.Entry<String, String[]> th : DungeonBuilder.THEMES.entrySet()) {
                    long cnt = plugin.dungeons().all().stream().filter(x -> x.loot.equals(th.getKey())).count();
                    h.inv.setItem(ti++, icon(Material.valueOf(th.getKey().equals("mace_head") ? "HEAVY_CORE" : th.getKey().equals("axe_head") ? "IRON_AXE"
                                    : th.getKey().equals("dagger_base") ? "IRON_SWORD" : "COPPER_INGOT"),
                            "&6" + th.getValue()[0], "&7Предмет: &f" + th.getValue()[3], "&7Таких данжей на карте: &f" + cnt,
                            "&aЛКМ — построить такой под вами"));
                }
                h.inv.setItem(4, icon(Material.COMPASS, "&6Случайные данжи (" + plugin.getConfig().getInt("dungeon.random_count", 3) + " шт.)",
                        "&aЛКМ — расставить по карте случайно"));
                h.inv.setItem(6, icon(Material.CHEST, "&6Обновить все данжи", "&aЛКМ — новые сундуки и возвращение боссов"));
                h.inv.setItem(49, icon(Material.ARROW, "&7Назад"));
                h.inv.setItem(53, help("Данж — замок с башнями, тронным залом и склепом с боссом; в сундуках компоненты для крафта.",
                        "Каждый данж содержит ТОЛЬКО один вид ивентового предмета (по теме замка). Верхние кнопки: построить здесь, расставить случайно, обновить сундуки; ряд ниже — построить данж нужного предмета.",
                        "Список ниже: ЛКМ — телепорт к данжу, ПКМ — обновить его сундуки и босса, Shift+ПКМ — убрать из списка (постройка в мире остаётся)."));
                int ds = 18;
                for (DungeonManager.Dungeon d : plugin.dungeons().all()) {
                    if (ds > 44) {
                        break;
                    }
                    h.inv.setItem(ds++, icon(Material.STONE_BRICKS, "&e#" + d.id + " &f«" + d.title() + "»", "&7Предмет: &6" + d.itemName(),
                            "&7" + d.world + " " + d.x + " " + d.y + " " + d.z,
                            "&aЛКМ — телепорт", "&bПКМ — обновить сундуки и босса", "&cShift+ПКМ — убрать из списка"));
                }
            }
            default -> {
                h = holder(Page.PLAYERS, 54, "&eВыбор цели");
                fillFrame(h.inv);
                int i = 0;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (i > 44) {
                        break;
                    }
                    ItemStack it = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta sm = (SkullMeta) it.getItemMeta();
                    sm.setOwningPlayer(o);
                    sm.setDisplayName(Util.color("&f" + o.getName()));
                    sm.setLore(List.of(Util.color("&aНажмите, чтобы выбрать целью")));
                    it.setItemMeta(sm);
                    h.inv.setItem(i++, it);
                }
                h.inv.setItem(49, icon(Material.ARROW, "&7Назад"));
            }
        }
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------ клики

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        if (!allowed(p)) {
            p.closeInventory();
            return;
        }
        int slot = e.getSlot();
        ItemStack clicked = e.getCurrentItem();
        if (clicked != null && clicked.getType() == Material.WRITABLE_BOOK && clicked.getItemMeta() != null
                && clicked.getItemMeta().getLore() != null) {
            p.sendMessage(Util.color("&e&l— Как пользоваться —"));
            for (String l : clicked.getItemMeta().getLore()) {
                p.sendMessage(" " + l);
            }
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            return;
        }
        boolean left = e.isLeftClick();
        boolean right = e.isRightClick();
        boolean shift = e.isShiftClick();
        List<Weapon> ws = new ArrayList<>(plugin.items().weapons().values());
        Player t = targetOf(p);

        switch (h.page) {
            case MAIN -> {
                switch (slot) {
                    case 1, 10 -> show(p, Page.WEAPONS);
                    case 3, 12 -> show(p, Page.ITEMS);
                    case 5, 14 -> show(p, Page.PROGRESS);
                    case 7, 16 -> show(p, Page.ARMOR);
                    case 19, 28 -> show(p, Page.HEARTS);
                    case 21, 30 -> show(p, Page.ALTARS);
                    case 23, 32 -> show(p, Page.DUNGEONS);
                    case 25, 34 -> show(p, Page.BARRIER);
                    case 37, 46 -> show(p, Page.COPIES);
                    case 39, 48 -> show(p, Page.TOOLS);
                    case 41, 50 -> show(p, Page.PLAYERS);
                    case 43, 52 -> {
                        RitualManager.give(p, create());
                        p.sendMessage(Util.color("&aВыдана ещё одна Звезда управления."));
                    }
                    default -> {
                    }
                }
            }
            case BARRIER -> {
                BarrierManager bm = plugin.barriers();
                switch (slot) {
                    case 49 -> show(p, Page.MAIN);
                    case 11, 20 -> {
                        RitualManager.give(p, bm.wand());
                        p.sendMessage(Util.color("&aТопор привата выдан: ЛКМ — точка A, ПКМ — точка B."));
                    }
                    case 13, 22 -> {
                        p.closeInventory();
                        bm.awaitCoords(p);
                    }
                    case 15, 24 -> {
                        BarrierManager.Sel sl = bm.sel(p);
                        if (right) {
                            bm.clearSel(p);
                        } else {
                            sl.fullHeight = !sl.fullHeight;
                        }
                        show(p, Page.BARRIER);
                    }
                    case 29, 38 -> {
                        p.closeInventory();
                        bm.run(p, BarrierManager.Op.WALL);
                    }
                    case 31, 40 -> {
                        p.closeInventory();
                        bm.run(p, BarrierManager.Op.FILL);
                    }
                    case 33, 42 -> {
                        p.closeInventory();
                        bm.run(p, BarrierManager.Op.CLEAR_FILL);
                    }
                    default -> {
                        int[] rs = {45, 46, 47, 48, 50, 51, 52, 53};
                        List<BarrierManager.Record> recs = bm.records();
                        for (int k = 0; k < rs.length && k < recs.size(); k++) {
                            if (rs[k] != slot) {
                                continue;
                            }
                            BarrierManager.Record r = recs.get(k);
                            if (shift && right) {
                                p.closeInventory();
                                bm.removeRecord(p, r);
                            } else if (left) {
                                org.bukkit.Location c = bm.center(r);
                                if (c != null) {
                                    p.closeInventory();
                                    p.teleport(c);
                                }
                            }
                        }
                    }
                }
            }
            case COPIES -> {
                if (slot == 22) {
                    show(p, Page.MAIN);
                } else if (slot >= 9 && slot < 9 + ws.size()) {
                    Weapon w = ws.get(slot - 9);
                    if (shift && right) {
                        long now = System.currentTimeMillis();
                        String want = p.getUniqueId() + ":" + w.id;
                        if (want.equals(pendingDelete) && now - pendingAt < 6000) {
                            pendingDelete = null;
                            int n = plugin.rituals().removeFromMap(w.id, p.getName());
                            p.sendMessage(Util.color("&aУдалено с карты: &f" + w.plainName() + " &a(экземпляров: " + n + ")"));
                        } else {
                            pendingDelete = want;
                            pendingAt = now;
                            p.sendMessage(Util.color("&cПовторите Shift+ПКМ в течение 6 с, чтобы удалить все копии &f" + w.plainName()));
                        }
                    } else if (right) {
                        plugin.rituals().setLimit(w.id, plugin.rituals().limit(w.id) - 1);
                        plugin.adminLog(p.getName(), "limit_set", w.id + " = " + plugin.rituals().limit(w.id) + " (menu)");
                    } else if (left) {
                        plugin.rituals().setLimit(w.id, plugin.rituals().limit(w.id) + 1);
                        plugin.adminLog(p.getName(), "limit_set", w.id + " = " + plugin.rituals().limit(w.id) + " (menu)");
                    }
                    show(p, Page.COPIES);
                }
            }
            case WEAPONS -> {
                if (slot == 22) {
                    show(p, Page.MAIN);
                } else if (slot >= 9 && slot < 9 + ws.size()) {
                    giveWeapon(p, t, ws.get(slot - 9));
                }
            }
            case ITEMS -> {
                switch (slot) {
                    case 10 -> show(p, Page.I_ARMOR);
                    case 12 -> show(p, Page.I_COMP);
                    case 14 -> show(p, Page.I_BOOKS);
                    case 16 -> show(p, Page.I_MISC);
                    case 22 -> show(p, Page.MAIN);
                    default -> {
                    }
                }
            }
            case I_ARMOR, I_COMP, I_BOOKS, I_MISC -> {
                if (slot == 22) {
                    show(p, Page.ITEMS);
                    return;
                }
                int[] pos = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25};
                for (int i = 0; i < pos.length; i++) {
                    if (pos[i] == slot) {
                        List<ItemStack> es = entries(h.page);
                        if (i < es.size()) {
                            giveEntry(p, t, h.page, i);
                        }
                        return;
                    }
                }
            }
            case PROGRESS -> {
                if (slot == 22) {
                    show(p, Page.MAIN);
                } else if (slot == 4) {
                    show(p, Page.PLAYERS);
                } else if (slot >= 9 && slot < 9 + ws.size()) {
                    Weapon w = ws.get(slot - 9);
                    int cur = plugin.progress().count(t.getUniqueId(), w.id);
                    int next = shift ? (right ? plugin.progress().need() : left ? 0 : cur)
                            : right ? cur + 1 : left ? cur - 1 : cur;
                    next = Math.max(0, next);
                    plugin.progress().set(t.getUniqueId(), w.id, next);
                    plugin.adminLog(p.getName(), "progress_set", t.getName() + " " + w.id + " = " + next + " (menu)");
                    p.sendMessage(Util.color("&aПрогресс &r" + w.name + " &aигрока &f" + t.getName() + "&a: &f"
                            + Math.min(next, plugin.progress().need()) + "/" + plugin.progress().need()
                            + (next >= plugin.progress().need() ? " &6(вторая способность открыта)" : "")));
                    show(p, Page.PROGRESS);
                }
            }
            case HEARTS -> {
                int cur = plugin.hearts().get(t.getUniqueId());
                int next = cur;
                switch (slot) {
                    case 11 -> next = cur - 1;
                    case 15 -> next = cur + 1;
                    case 13 -> next = plugin.hearts().defaultHearts();
                    case 4 -> {
                        show(p, Page.PLAYERS);
                        return;
                    }
                    case 20 -> {
                        if (plugin.hearts().isEliminated(t.getUniqueId())) {
                            plugin.hearts().revive(t.getUniqueId());
                            plugin.adminLog(p.getName(), "revive", t.getName() + " (menu)");
                            p.sendMessage(Util.color(plugin.text("revived", "player", t.getName())));
                        }
                        show(p, Page.HEARTS);
                        return;
                    }
                    case 22 -> {
                        show(p, Page.MAIN);
                        return;
                    }
                    default -> {
                        return;
                    }
                }
                next = Math.max(plugin.hearts().min(), Math.min(plugin.hearts().maxFor(), next));
                plugin.hearts().set(t.getUniqueId(), next);
                plugin.adminLog(p.getName(), "hearts_set", t.getName() + " = " + next + " (menu)");
                show(p, Page.HEARTS);
            }
            case ARMOR -> {
                if (slot == 22) {
                    show(p, Page.MAIN);
                } else if (slot == 4) {
                    show(p, Page.PLAYERS);
                } else if (slot == 13) {
                    int max = (int) plugin.getConfig().getDouble("copper_armor.chestplate.max_charges", 10);
                    int cur = plugin.charges().get(t.getUniqueId(), ArmorSet.CHARGE_KEY);
                    int next = shift ? (right ? max : left ? 0 : cur) : right ? cur + 1 : left ? cur - 1 : cur;
                    next = Math.max(0, Math.min(max, next));
                    plugin.charges().set(t.getUniqueId(), ArmorSet.CHARGE_KEY, next);
                    plugin.adminLog(p.getName(), "armor_charge_set", t.getName() + " = " + next + " (menu)");
                    p.sendMessage(Util.color("&aЗаряд брони игрока &f" + t.getName() + "&a: &f" + next + "/" + max));
                    show(p, Page.ARMOR);
                }
            }
            case DUNGEONS -> {
                if (slot == 49) {
                    show(p, Page.MAIN);
                } else if (slot == 2) {
                    p.closeInventory();
                    plugin.dungeons().spawnAt(p, p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ());
                } else if (slot >= 10 && slot <= 13) {
                    p.closeInventory();
                    plugin.dungeons().spawnAt(p, p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(),
                            new ArrayList<>(DungeonBuilder.THEMES.keySet()).get(slot - 10));
                } else if (slot == 4) {
                    p.closeInventory();
                    plugin.dungeons().random(p, plugin.getConfig().getInt("dungeon.random_count", 3), p.getWorld());
                } else if (slot == 6) {
                    for (DungeonManager.Dungeon dg : new ArrayList<>(plugin.dungeons().all())) {
                        plugin.dungeons().refill(dg);
                    }
                    p.sendMessage(Util.color("&aДанжи обновлены."));
                } else if (slot >= 18 && slot <= 44) {
                    List<DungeonManager.Dungeon> list = new ArrayList<>(plugin.dungeons().all());
                    int idx = slot - 18;
                    if (idx < list.size()) {
                        DungeonManager.Dungeon d = list.get(idx);
                        if (shift && right) {
                            plugin.dungeons().remove(d.id);
                            plugin.adminLog(p.getName(), "dungeon_remove", "#" + d.id + " (menu)");
                            p.sendMessage(Util.color("&aДанж #" + d.id + " убран из списка."));
                            show(p, Page.DUNGEONS);
                        } else if (right) {
                            plugin.dungeons().refill(d);
                            p.sendMessage(Util.color("&aСундуки данжа #" + d.id + " обновлены."));
                        } else if (left && Bukkit.getWorld(d.world) != null) {
                            p.closeInventory();
                            p.teleport(new org.bukkit.Location(Bukkit.getWorld(d.world), d.x + 0.5, d.y + 20, d.z + 22.5));
                            Util.addEffect(p, "slow_falling", 400, 0);
                            p.sendMessage(Util.color("&aТелепорт к данжу #" + d.id + " (вы над воротами)."));
                        }
                    }
                }
            }
            case ALTARS -> altarsClick(p, slot, left, right, new ArrayList<>(plugin.items().ritualItems().values()));
            case TOOLS -> {
                switch (slot) {
                    case 11 -> {
                        plugin.reloadAll();
                        p.sendMessage(Util.color("&aconfig.yml перезагружен."));
                    }
                    case 13 -> {
                        plugin.cooldowns().clearPlayer(t.getUniqueId());
                        p.sendMessage(Util.color("&aПерезарядки игрока &f" + t.getName() + " &aсброшены."));
                    }
                    case 15 -> {
                        p.closeInventory();
                        p.sendMessage(Util.color("&6&l— Журнал: последние действия админов —"));
                        List<String> acts = plugin.db().query("SELECT ts, actor, action, details FROM admin_log ORDER BY id DESC LIMIT 12",
                                r -> new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date(r.getLong(1)))
                                        + " &e" + r.getString(2) + " &7" + r.getString(3) + " &f" + (r.getString(4) == null ? "" : r.getString(4)));
                        if (acts.isEmpty()) {
                            p.sendMessage(Util.color("&7Записей пока нет."));
                        }
                        for (String l : acts) {
                            p.sendMessage(Util.color("&8• &7" + l));
                        }
                        p.sendMessage(Util.color("&6&l— Баны Молота бана (последние 5) —"));
                        List<String> bans = plugin.db().query("SELECT ts, killer_name, victim_name FROM ban_log ORDER BY id DESC LIMIT 5",
                                r -> new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date(r.getLong(1)))
                                        + " &c" + r.getString(2) + " &7убил &c" + r.getString(3));
                        if (bans.isEmpty()) {
                            p.sendMessage(Util.color("&7Банов нет."));
                        }
                        for (String l : bans) {
                            p.sendMessage(Util.color("&8• &7" + l));
                        }
                        p.sendMessage(Util.color("&7Полный список банов: &f/legendary banlog"));
                    }
                    case 22 -> show(p, Page.MAIN);
                    default -> {
                    }
                }
            }
            case PLAYERS -> {
                if (slot == 49) {
                    show(p, Page.MAIN);
                } else if (e.getCurrentItem() != null && e.getCurrentItem().getItemMeta() instanceof SkullMeta sm
                        && sm.getOwningPlayer() != null) {
                    OfflinePlayer op = sm.getOwningPlayer();
                    targets.put(p.getUniqueId(), op.getUniqueId());
                    show(p, Page.MAIN);
                }
            }
        }
    }

    /** Слот алтаря в меню: оружие в первых рядах, броня отдельным блоком справа (слоты 14-17). */
    private static int altarSlot(List<Weapon> ws, int index) {
        int weapons = 0, armor = 0;
        for (int i = 0; i <= index; i++) {
            if (ws.get(i) instanceof ArmorPiece) {
                armor++;
            } else {
                weapons++;
            }
        }
        return ws.get(index) instanceof ArmorPiece ? 13 + armor : weapons - 1;
    }

    private void altarsClick(Player p, int slot, boolean left, boolean right, List<Weapon> ws) {
        if (slot == 49) {
            show(p, Page.MAIN);
            return;
        }
        if (slot == 45) {
            List<String> types = new ArrayList<>(plugin.items().ritualItems().keySet());
            types.removeIf(tp -> plugin.altars().all().stream().anyMatch(a -> a.type.equals(tp)));
            p.closeInventory();
            if (types.isEmpty()) {
                p.sendMessage(Util.color("&eАлтари всех типов уже есть."));
            } else {
                plugin.spawner().random(p, types, p.getWorld());
            }
            return;
        }
        int found = -1;
        for (int k = 0; k < ws.size(); k++) {
            if (altarSlot(ws, k) == slot) {
                found = k;
                break;
            }
        }
        if (found >= 0) {
            Weapon w = ws.get(found);
            p.closeInventory();
            if (right) {
                plugin.spawner().random(p, new ArrayList<>(List.of(w.id)), p.getWorld());
            } else if (left) {
                AltarManager.Altar a = plugin.spawner().spawnAt(p, w.id, p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ());
                p.sendMessage(Util.color(a == null ? "&cНе удалось построить алтарь." : "&aАлтарь &f#" + a.id + " &aпостроен."));
            }
            return;
        }
        if (slot >= 18 && slot <= 44) {
            int idx = slot - 18;
            List<AltarManager.Altar> list = new ArrayList<>(plugin.altars().all());
            if (idx < list.size() && list.get(idx).location() != null) {
                AltarManager.Altar a = list.get(idx);
                p.closeInventory();
                p.teleport(a.location().add(0.5, 3, 0.5 + AltarBuilder.radius(plugin) - 2));
                p.sendMessage(Util.color("&aТелепорт к алтарю #" + a.id + " (" + a.type + ")."));
            }
        }
    }

    /** Предметы категории (для показа); первый элемент брони — весь комплект. */
    private List<ItemStack> entries(Page page) {
        ItemRegistry reg = plugin.items();
        List<ItemStack> out = new ArrayList<>();
        switch (page) {
            case I_ARMOR -> {
                ItemStack all = new ItemStack(Material.COPPER_CHESTPLATE);
                ItemMeta m = all.getItemMeta();
                m.setDisplayName(Util.color("&6Медный комплект"));
                m.setLore(List.of(Util.color("&7Все 4 части")));
                all.setItemMeta(m);
                out.add(all);
                for (String id : ItemRegistry.ARMOR_IDS) {
                    out.add(reg.createArmor(id));
                }
            }
            case I_COMP -> {
                for (String id : reg.components().keySet()) {
                    out.add(reg.createComponent(id, 1));
                }
            }
            case I_BOOKS -> {
                out.add(reg.createRecipeBook());
                for (String id : reg.components().keySet()) {
                    out.add(reg.createBlueprint(id));
                }
            }
            default -> {
                out.add(plugin.food().create());
                out.add(plugin.pouches().create());
                out.add(create());
                out.add(plugin.barriers().wand());
            }
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private void giveEntry(Player admin, Player t, Page page, int idx) {
        if (page == Page.I_ARMOR && idx == 0) {
            for (String id : ItemRegistry.ARMOR_IDS) {
                ItemStack a = plugin.items().createArmor(id);
                if (a != null) {
                    RitualManager.give(t, a);
                }
            }
            done(admin, t, "медный комплект");
            return;
        }
        List<ItemStack> es = entries(page);
        if (idx >= es.size()) {
            return;
        }
        ItemStack it = es.get(idx);
        RitualManager.give(t, it);
        String nm = it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : it.getType().name();
        done(admin, t, org.bukkit.ChatColor.stripColor(nm));
    }

    private void done(Player admin, Player target, String what) {
        plugin.adminLog(admin.getName(), "give", what + " -> " + target.getName() + " (menu)");
        admin.sendMessage(Util.color("&aВыдано: &f" + what + " &aигроку &f" + target.getName()));
    }

    /** Выдача оружия администратором: правило «одно на карте» админ обходит осознанно (запись в журнал). */
    private void giveWeapon(Player admin, Player target, Weapon w) {
        ItemStack it = plugin.items().createWeapon(w.id);
        plugin.registerInstance(w.id, plugin.items().instanceId(it));
        RitualManager.give(target, it);
        done(admin, target, w.plainName());
    }
}
