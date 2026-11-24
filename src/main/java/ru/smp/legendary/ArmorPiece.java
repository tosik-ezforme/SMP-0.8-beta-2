package ru.smp.legendary;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Часть медного комплекта как «предмет ритуала»: у каждой части свой алтарь и свой рецепт.
 * Способностей у самой части нет — они работают у комплекта целиком (см. ArmorSet).
 */
public final class ArmorPiece extends Weapon {

    ArmorPiece(LegendaryPlugin p, String id) {
        super(p, id, nameOf(id), materialOf(id), altarOf(id), Cat.SPECIAL);
    }

    private static String nameOf(String id) {
        return switch (id) {
            case "copper_helmet" -> "&6Шлем «Искатель»";
            case "copper_chestplate" -> "&6Нагрудник «Проводник»";
            case "copper_leggings" -> "&6Поножи «Гальваника»";
            default -> "&6Ботинки «Импульс»";
        };
    }

    private static String altarOf(String id) {
        return switch (id) {
            case "copper_helmet" -> "Алтарь Искателя";
            case "copper_chestplate" -> "Алтарь Проводника";
            case "copper_leggings" -> "Алтарь Гальваники";
            default -> "Алтарь Импульса";
        };
    }

    private static Material materialOf(String id) {
        String n = id.toUpperCase();
        Material m = Material.matchMaterial(n);
        return m != null ? m : Material.matchMaterial(n.replace("COPPER", "IRON"));
    }

    @Override public String primaryName() { return "—"; }
    @Override public String secondaryName() { return "—"; }
    @Override public String blurb() { return "Часть медного комплекта."; }
    @Override public boolean primary(Player p) { return false; }
    @Override public boolean secondary(Player p) { return false; }
}
