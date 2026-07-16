package com.patternupload.client;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import appeng.core.definitions.AEItems;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** GTRecipeType ↔ 代表機器 icon / 顯示名稱 解析與快取。 */
public final class RecipeTypeIcons {

    private static Map<GTRecipeType, ItemStack> iconCache;
    private static Map<Item, Set<GTRecipeType>> typesByItem;
    private static List<GTRecipeType> sortedTypes;

    private RecipeTypeIcons() {}

    private static void buildCache() {
        if (iconCache != null) {
            return;
        }
        iconCache = new HashMap<>();
        typesByItem = new HashMap<>();
        for (MachineDefinition def : GTRegistries.MACHINES.values()) {
            GTRecipeType[] types = def.getRecipeTypes();
            if (types == null) {
                continue;
            }
            ItemStack stack = def.asStack();
            for (GTRecipeType type : types) {
                if (type != null) {
                    // 註冊順序大致由低階到高階，保留最先出現的機器當代表
                    iconCache.putIfAbsent(type, stack);
                    // 反向表：機器物品 → 支援的配方類型（本地排序用）
                    typesByItem.computeIfAbsent(stack.getItem(), i -> new HashSet<>()).add(type);
                }
            }
        }
    }

    /**
     * 目的地 icon（GTOCore 給的群組圖示＝所貼機器的物品）反查該機器支援的配方類型；
     * 非機器物品（如 ME 接口）回 null。
     */
    @Nullable
    public static Set<GTRecipeType> typesForIcon(@Nullable AEKey icon) {
        buildCache();
        if (icon instanceof AEItemKey itemKey) {
            return typesByItem.get(itemKey.getItem());
        }
        return null;
    }

    /** type 是否算支援 target（含 gtceu 小配方表對映，跟 GTOCore 伺服端邏輯一致）。 */
    public static boolean matchesType(@Nullable GTRecipeType type, GTRecipeType target) {
        if (type == null) {
            return false;
        }
        return type == target || type.getSmallRecipeMap() == target;
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
