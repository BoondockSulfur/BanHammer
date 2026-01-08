package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.banhammer.plugin.util.Constants.HAMMER_PDC_MARKER;

public final class ItemFactory {

    private ItemFactory() {}

    public static ItemStack createHammer(BanHammerPlugin plugin) {
        var s = plugin.settings();
        Material mat = Material.matchMaterial(s.itemMaterial());
        if (mat == null) mat = Material.CARROT_ON_A_STICK;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        var mm = MiniMessage.miniMessage();

        String name = s.itemName();
        if (name != null && !name.isBlank()) {
            meta.displayName(mm.deserialize(name));
        }

        List<String> loreCfg = s.itemLore();
        if (loreCfg != null && !loreCfg.isEmpty()) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : loreCfg) lore.add(mm.deserialize(line));
            meta.lore(lore);
        }

        if (s.itemCustomModelData() > 0) {
            meta.setCustomModelData(s.itemCustomModelData());
        }
        meta.getPersistentDataContainer().set(plugin.pdcKey(), PersistentDataType.BYTE, HAMMER_PDC_MARKER);

        if (s.itemUnbreakable()) meta.setUnbreakable(true);
        if (s.itemHideFlags()) {
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        }

        item.setItemMeta(meta);
        return item;
    }

    public static boolean isHammer(BanHammerPlugin plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = Objects.requireNonNull(item.getItemMeta()).getPersistentDataContainer();
        Byte mark = pdc.get(plugin.pdcKey(), PersistentDataType.BYTE);
        return mark != null && mark == HAMMER_PDC_MARKER;
    }
}
