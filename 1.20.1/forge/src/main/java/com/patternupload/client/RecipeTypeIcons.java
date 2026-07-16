package com.patternupload.client;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.core.definitions.AEItems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GTRecipeType → 代表機器 icon / 顯示名稱 解析與快取。 */
public final class RecipeTypeIcons {

    private static Map<GTRecipeType, ItemStack> iconCache;
    private static List<GTRecipeType> sortedTypes;

    private RecipeTypeIcons() {}

    private static void buildCache() {
        if (iconCache != null) {
            return;
        }
        iconCache = new HashMap<>();
        for (MachineDefinition def : GTRegistries.MACHINES.values()) {
            GTRecipeType[] types = def.getRecipeTypes();
            if (types == null) {
                continue;
            }
            for (GTRecipeType type : types) {
                if (type != null) {
                    // 註冊順序大致由低階到高階，保留最先出現的機器當代表
                    iconCache.putIfAbsent(type, def.asStack());
                }
            }
        }
    }

    /** 該配方類型的代表機器 icon；找不到機器時回傳樣板 icon。 */
    public static ItemStack icon(GTRecipeType type) {
        buildCache();
        ItemStack stack = type == null ? null : iconCache.get(type);
        return stack != null ? stack : patternIcon();
    }

    public static ItemStack patternIcon() {
        return AEItems.PROCESSING_PATTERN.stack();
    }

    /** 配方類型顯示名（沿用 GTOCore 慣例的 lang key）。 */
    public static Component name(GTRecipeType type) {
        return Component.translatable("gtceu." + type.registryName.getPath());
    }

    /** 所有已知配方類型，依本地化名稱排序（只含有代表機器的，選了才有意義）。 */
    public static List<GTRecipeType> allTypes() {
        buildCache();
        if (sortedTypes == null) {
            List<GTRecipeType> list = new ArrayList<>(iconCache.keySet());
            list.sort(Comparator.comparing(t -> name(t).getString().toLowerCase(Locale.ROOT)));
            sortedTypes = list;
        }
        return sortedTypes;
    }
}
