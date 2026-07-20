package com.patternupload.client;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化設定（config/pattern_upload.json）：
 * - providerMachines：供應器鍵 → 指定的配方類型 registry id（接口類供應器手動指定用）
 *   鍵可為「座標」（{@code pos:<dim>#<packedLong>}，伺服端回傳世界座標 → 同名供應器獨立）
 *   或「顯示名稱」（無座標時的退路，相容舊設定）。
 * - panelX / panelY / panelW / panelRows：overlay 面板拖曳後的位置與縮放後的尺寸
 */
final class PatternUploadConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("pattern_upload.json");

    private static Map<String, String> providerMachines;
    @Nullable
    static Integer panelX;
    @Nullable
    static Integer panelY;
    @Nullable
    static Integer panelW;
    @Nullable
    static Integer panelRows;

    private PatternUploadConfig() {}

    private static void load() {
        if (providerMachines != null) {
            return;
        }
        providerMachines = new LinkedHashMap<>();
        if (!Files.exists(FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("providerMachines")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("providerMachines").entrySet()) {
                    providerMachines.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (root.has("panelX")) {
                panelX = root.get("panelX").getAsInt();
            }
            if (root.has("panelY")) {
                panelY = root.get("panelY").getAsInt();
            }
            if (root.has("panelW")) {
                panelW = root.get("panelW").getAsInt();
            }
            if (root.has("panelRows")) {
                panelRows = root.get("panelRows").getAsInt();
            }
        } catch (Throwable t) {
            LOGGER.error("[pattern_upload] 讀取 {} 失敗，改用空設定", FILE, t);
        }
    }

    static void save() {
        load();
        try {
            JsonObject root = new JsonObject();
            JsonObject machines = new JsonObject();
            providerMachines.forEach(machines::addProperty);
            root.add("providerMachines", machines);
            if (panelX != null && panelY != null) {
                root.addProperty("panelX", panelX);
                root.addProperty("panelY", panelY);
            }
            if (panelW != null) {
                root.addProperty("panelW", panelW);
            }
            if (panelRows != null) {
                root.addProperty("panelRows", panelRows);
            }
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Throwable t) {
            LOGGER.error("[pattern_upload] 寫入 {} 失敗", FILE, t);
        }
    }

    /**
     * 該供應器被指定的配方類型；未指定或該類型已不存在時回 null。
     * <p>
     * 鍵優先序：有座標鍵（{@code posKey}，來自伺服端回傳的世界座標）先查座標鍵 → 讓同名供應器獨立；
     * 查無座標指定時退回名稱鍵（相容舊設定與伺服端未回座標的情形）。
     */
    @Nullable
    static GTRecipeType machineFor(@Nullable String posKey, String providerName) {
        load();
        String id = posKey != null ? providerMachines.get(posKey) : null;
        if (id == null) {
            id = providerMachines.get(providerName);
        }
        if (id == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : GTRegistries.RECIPE_TYPES.get(rl);
    }

    /**
     * 指定（type != null）或清除（type == null）供應器的機器，立即落盤。
     * 有 {@code posKey}（座標）時以座標為鍵 → 同名供應器各自獨立；否則以名稱為鍵。
     * 清除時連同 legacy 名稱鍵一起移除，避免座標清了名稱鍵殘留。
     */
    static void assign(@Nullable String posKey, String providerName, @Nullable GTRecipeType type) {
        load();
        String key = posKey != null ? posKey : providerName;
        if (type == null) {
            providerMachines.remove(key);
            if (posKey != null) {
                providerMachines.remove(providerName);
            }
        } else {
            providerMachines.put(key, type.registryName.toString());
        }
        save();
    }

    /** 面板位置／尺寸變動後呼叫（拖曳或縮放結束時）。 */
    static void savePanel(int x, int y, int w, int rows) {
        load();
        panelX = x;
        panelY = y;
        panelW = w;
        panelRows = rows;
        save();
    }

    @Nullable
    static Integer panelX() {
        load();
        return panelX;
    }

    @Nullable
    static Integer panelY() {
        load();
        return panelY;
    }

    @Nullable
    static Integer panelW() {
        load();
        return panelW;
    }

    @Nullable
    static Integer panelRows() {
        load();
        return panelRows;
    }
}
