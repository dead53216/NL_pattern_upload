package com.patternupload.client;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

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
    /** 機器本地化名稱 → 其支援的配方類型（供應器名稱比對用；ProcessingPlantMachine 通用工廠靠這辨識子機器）。 */
    private static List<MachineName> machineNames;
    /** 原版 RecipeType（如 minecraft:smelting）→ 以它為 proxy 且有代表機器的 GTRecipeType。 */
    private static Map<RecipeType<?>, Set<GTRecipeType>> proxyOwners;

    private record MachineName(String name, Set<GTRecipeType> types) {}

    private RecipeTypeIcons() {}

    private static void buildCache() {
        if (iconCache != null) {
            return;
        }
        iconCache = new HashMap<>();
        typesByItem = new HashMap<>();
        Map<String, Set<GTRecipeType>> byName = new HashMap<>();
        for (MachineDefinition def : GTRegistries.MACHINES.values()) {
            GTRecipeType[] types = def.getRecipeTypes();
            if (types == null) {
                continue;
            }
            ItemStack stack = def.asStack();
            String hoverName = stack.getHoverName().getString();
            for (GTRecipeType type : types) {
                if (type != null) {
                    // 註冊順序大致由低階到高階，保留最先出現的機器當代表
                    iconCache.putIfAbsent(type, stack);
                    // 反向表：機器物品 → 支援的配方類型（本地排序用）
                    typesByItem.computeIfAbsent(stack.getItem(), i -> new HashSet<>()).add(type);
                    if (!hoverName.isBlank()) {
                        byName.computeIfAbsent(hoverName, n -> new HashSet<>()).add(type);
                    }
                }
            }
        }
        machineNames = new ArrayList<>();
        byName.forEach((n, t) -> machineNames.add(new MachineName(n, t)));
    }

    /**
     * 供應器顯示名稱裡「最長的機器名」是否支援 current 類型。
     * 用最長匹配避開子字串嵌套誤判：「電路組裝機」供應器不會誤中「組裝機」型；
     * 「通用工廠 - 進階流體固化器」會命中進階流體固化器（其支援流體固化器型）。
     */
    public static boolean nameMachineSupports(String destName, GTRecipeType current) {
        buildCache();
        MachineName best = null;
        for (MachineName m : machineNames) {
            if (destName.contains(m.name) && (best == null || m.name.length() > best.name.length())) {
                best = m;
            }
        }
        if (best == null) {
            return false;
        }
        for (GTRecipeType t : best.types) {
            if (matchesType(t, current)) {
                return true;
            }
        }
        return false;
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

    /**
     * 目的地是否為「合成容器」（分子裝配室／裝配矩陣——合成類樣板只有它們能做）。
     * 由 icon 物品 registry id 判定：ae2:molecular_assembler、expatternprovider:ex_molecular_assembler、
     * expatternprovider:assembler_matrix_*、gtocore:super_molecular_assembler。
     */
    public static boolean isCraftContainer(@Nullable AEKey icon) {
        if (!(icon instanceof AEItemKey itemKey)) {
            return false;
        }
        var rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(itemKey.getItem());
        if (rl == null) {
            return false;
        }
        String path = rl.getPath();
        return path.contains("molecular_assembler") || path.startsWith("assembler_matrix");
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

    /**
     * 原版 RecipeType → 把它列為 proxy 的 GTRecipeType（僅含有代表機器者）。
     * <p>
     * gtceu 電力熔爐（FURNACE_RECIPES）不把原版燒煉配方收進自己的 {@code recipes}，而是
     * proxyRecipes = {minecraft:smelting} 委派原版 RecipeManager。故要靠這張反查表，
     * 才認得出「燒煉樣板 → 熔爐機器」（GTOCore 對原版燒煉樣板不填 gtocore$recipe）。
     */
    static Map<RecipeType<?>, Set<GTRecipeType>> proxyOwners() {
        buildCache();
        if (proxyOwners == null) {
            proxyOwners = new HashMap<>();
            for (GTRecipeType type : iconCache.keySet()) {
                for (RecipeType<?> vanilla : type.getProxyRecipes()) {
                    proxyOwners.computeIfAbsent(vanilla, k -> new HashSet<>()).add(type);
                }
            }
        }
        return proxyOwners;
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
