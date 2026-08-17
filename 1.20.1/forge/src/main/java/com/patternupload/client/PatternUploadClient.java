package com.patternupload.client;

import com.patternupload.PatternUploadMod;
import com.patternupload.net.Network;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 零 mixin 架構：
 * 每幀（Render.Pre）檢查 GTOCore 的目的地清單框是否剛被設為可見；
 * 一可見即「劫持」——反射抽出資料、把原清單藏起來、改開本 mod 的 overlay。
 * 送出/指定機器仍走 GTOCore 既有介面方法（純呼叫，不需類變換）。
 */
@Mod.EventBusSubscriber(modid = PatternUploadMod.MOD_ID, value = Dist.CLIENT)
public final class PatternUploadClient {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @Nullable
    private static UploadOverlay overlay;

    /**
     * 中鍵點上傳鈕時設 true：下一批目的地清單回來時強制開面板、跳過自動直傳（讓玩家自選）。
     * 由 onRenderPre 消費（開面板即清）；面板關閉時也清，避免殘留影響下次右鍵自動上傳。
     */
    private static boolean forcePanel = false;

    /**
     * 目的地座標同步（同名供應器獨立身分用）：每次劫持新清單 {@link #posGen}+1，
     * 過濾過期 S2C 回覆；{@link #posDims}/{@link #posPacked} 照 index 對齊，
     * null 或某格 dim==null = 該列無座標 → 退回名稱鍵。伺服端沒裝本 mod 時永遠收不到 → 全退名稱鍵。
     */
    private static int posGen = 0;
    @Nullable
    private static ResourceLocation[] posDims = null;
    @Nullable
    private static long[] posPacked = null;
    /** 伺服端建議機器（配方類型 registry id，可逗號串接多類型，照 index 對齊；空字串＝無建議）。接口→子網→存儲總線解析而來。 */
    @Nullable
    private static String[] posSuggest = null;
    /** 各目的地樣板槽剩餘空格數（照 index 對齊；-1＝未知——舊伺服端／取不到）。 */
    @Nullable
    private static int[] posFree = null;
    /** 各目的地是否已有「與本次編碼相同主產物」的樣板（GTO 上傳去重同款判定；照 index 對齊）。 */
    @Nullable
    private static boolean[] posHasRecipe = null;
    /** 已有該配方但被 GTO 從清單移除的供應器（伺服端整網枚舉補回；面板置頂當資訊列）。 */
    @Nullable
    private static java.util.List<Network.ReplyS2C.Extra> posExtras = null;
    /** 建議路徑解析到的實際機器物品 id（照 index 對齊；空＝未知，顯示退類型代表機器）。 */
    @Nullable
    private static String[] posSugMachine = null;
    /** extras 的實際機器物品 id（照 extras 順序）。 */
    @Nullable
    private static String[] posExtraMachine = null;
    /** 各目的地所服務機器的身分鍵（{@code <dim>#<packedLong>}，多方塊為控制器座標；空＝判不出）。 */
    @Nullable
    private static String[] posMachineKey = null;
    /** 各目的地**容器自身**的種類（樣板總成家族＝其機器物品 id；AE2 供應器＝""）。 */
    @Nullable
    private static String[] posContainerKind = null;
    /** 各目的地建議機器的電壓等級名（GTValues.VN 如 "LV"，照 index 對齊；空字串＝未知）。 */
    @Nullable
    private static String[] posTier = null;

    /**
     * 延遲決策：處理樣板的「自動直傳 vs 開面板」決策延到伺服端座標／建議回來再判。
     * 否則接口類（子網機器）第一幀還沒解析出機器 → 被漏算成「不匹配」，
     * 導致「同時有直接機器＋子網同型機器」時誤判成單一匹配直接直傳、不開面板讓玩家選。
     * 收到 S2C（gen 相符）或 {@link #DECIDE_WAIT_TICKS} 逾時（伺服端沒裝本 mod／沒回）即決策。
     */
    private static final int DECIDE_WAIT_TICKS = 10;
    @Nullable
    private static PatternEncodingTermScreen<?> pendingScreen;
    @Nullable
    private static List<ListBoxReflector.Dest> pendingDests;
    private static boolean pendingForce;
    private static int pendingWaitTicks = -1;

    static {
        LOGGER.info("[pattern_upload] PatternUploadClient loaded (hijack mode, no mixin)");
    }

    private PatternUploadClient() {}

    public static void removeOverlay() {
        overlay = null;
    }

    // ------------------------------------------------------ 目的地座標同步

    /** 開面板時呼叫：清掉上一批座標／建議、發請求要這批目的地的世界座標＋建議機器（伺服端有裝本 mod 才會回）。 */
    private static void requestPositionsFor(AbstractContainerMenu menu) {
        posDims = null;
        posPacked = null;
        posSuggest = null;
        posFree = null;
        posHasRecipe = null;
        posExtras = null;
        posTier = null;
        posSugMachine = null;
        posExtraMachine = null;
        posMachineKey = null;
        posContainerKind = null;
        int gen = ++posGen;
        try {
            Network.requestPositions(menu.containerId, gen);
        } catch (Throwable t) {
            LOGGER.error("[pattern_upload] 發送座標請求失敗", t);
        }
    }

    /** 伺服端回座標（由網路層在客戶端呼叫）。gen 不符即過期丟棄；符合則落地並刷新開啟中的面板。 */
    public static void receiveDestPositions(Network.ReplyS2C msg) {
        if (msg.gen() != posGen) {
            return; // 過期回覆（已換新清單），忽略
        }
        posDims = msg.dims();
        posPacked = msg.packed();
        posSuggest = msg.suggest();
        posFree = msg.free();
        posHasRecipe = msg.hasRecipe();
        posExtras = msg.extras();
        posTier = msg.tier();
        posSugMachine = msg.sugMachine();
        posExtraMachine = msg.extraMachine();
        posMachineKey = msg.machineKey();
        posContainerKind = msg.containerKind();
        LOGGER.info("[pattern_upload] received {} destination positions/suggestions (gen {})", msg.dims().length, msg.gen());
        if (pendingDests != null) {
            decidePending(); // 決策前的清單：座標／建議到齊 → 判自動直傳 vs 開面板
        } else if (overlay != null) {
            overlay.onPositionsUpdated(); // 面板已開 → 只以新座標／建議重排刷新
        }
    }

    /**
     * 第 index 個目的地的持久身分鍵：有世界座標回 {@code "pos:<dim>#<packedLong>"}（同名供應器各自獨立），
     * 無座標（尚未收到／伺服端未提供／該容器非方塊）回 null → 呼叫端退回名稱鍵。
     */
    @Nullable
    static String posKeyFor(int index) {
        ResourceLocation[] dims = posDims;
        long[] packed = posPacked;
        if (dims == null || packed == null || index < 0 || index >= dims.length || dims[index] == null) {
            return null;
        }
        return "pos:" + dims[index] + "#" + packed[index];
    }

    /**
     * 第 index 個目的地的伺服端建議機器（接口→子網→存儲總線解析）配方類型清單；
     * 1.18.0 起建議欄位可為逗號串接多類型（多類型機器如大型冶煉廠回可用類型全集）。無建議回空表。
     */
    static java.util.List<GTRecipeType> suggestionsFor(int index) {
        String[] sug = posSuggest;
        if (sug == null || index < 0 || index >= sug.length || sug[index] == null || sug[index].isEmpty()) {
            return java.util.List.of();
        }
        return parseSuggestions(sug[index]);
    }

    /** 機器物品 id → ItemStack；空／解析不到回 null（呼叫端退類型代表機器）。 */
    @Nullable
    static net.minecraft.world.item.ItemStack machineItemStack(String id) {
        try {
            if (id == null || id.isEmpty()) {
                return null;
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return null;
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            return item == net.minecraft.world.item.Items.AIR ? null : new net.minecraft.world.item.ItemStack(item);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 建議欄位字串（可含逗號多類型）→ 類型清單；解析不到者濾掉，空回空表。 */
    static java.util.List<GTRecipeType> parseSuggestions(@Nullable String ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<GTRecipeType> out = new java.util.ArrayList<>(2);
        for (String id : ids.split(",")) {
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            GTRecipeType t = rl == null ? null : GTRegistries.RECIPE_TYPES.get(rl);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 類型清單中挑「顯示／排序用」單一類型：優先挑吻合 current 者（多類型機器任一類型吻合即以該類型
     * 呈現——大型冶煉廠對合金樣板顯示合金冶煉），無吻合或無 current 取第一個；空清單回 null。
     */
    @Nullable
    static GTRecipeType pickSuggestion(java.util.List<GTRecipeType> list, @Nullable GTRecipeType current) {
        if (list.isEmpty()) {
            return null;
        }
        if (current != null) {
            for (GTRecipeType t : list) {
                if (RecipeTypeIcons.matchesType(t, current)) {
                    return t;
                }
            }
        }
        return list.get(0);
    }

    /** 第 index 個目的地建議機器的電壓等級名（"LV" 等）；未知（舊伺服端／沒回／判不出）回 ""。 */
    static String tierFor(int index) {
        String[] t = posTier;
        return (t == null || index < 0 || index >= t.length || t[index] == null) ? "" : t[index];
    }

    /** 第 index 個目的地的樣板槽剩餘空格數；未知（舊伺服端／沒回）回 -1。 */
    static int freeSlotsFor(int index) {
        int[] f = posFree;
        return (f == null || index < 0 || index >= f.length) ? -1 : f[index];
    }

    /** 第 index 個目的地是否已有本次編碼樣板（主產物相同）；未知（舊伺服端／沒回）回 false。 */
    static boolean hasRecipeFor(int index) {
        boolean[] h = posHasRecipe;
        return h != null && index >= 0 && index < h.length && h[index];
    }

    /** 已有該配方但被 GTO 藏掉的供應器（面板置頂資訊列）；沒回（舊伺服端）回空表。 */
    static java.util.List<Network.ReplyS2C.Extra> extraDests() {
        var e = posExtras;
        return e == null ? java.util.List.of() : e;
    }

    /** 第 index 個目的地建議路徑的實際機器物品 id；未知回 ""（顯示退類型代表機器）。 */
    static String machineItemFor(int index) {
        String[] m = posSugMachine;
        return (m == null || index < 0 || index >= m.length || m[index] == null) ? "" : m[index];
    }

    /**
     * 第 index 個目的地所服務機器的**身分鍵**（多方塊為控制器座標）；未知／舊伺服端回 ""。
     * 機器類型與物品只能證明「同款」，這個鍵才能證明「**同一台**」——供 {@link #sameMachineAndMode} 判合併。
     */
    static String machineKeyFor(int index) {
        String[] m = posMachineKey;
        return (m == null || index < 0 || index >= m.length || m[index] == null) ? "" : m[index];
    }

    /**
     * 第 index 個目的地**容器自身**的種類（樣板總成家族＝其機器物品 id；AE2 供應器／舊伺服端＝""）。
     * 同一台機器上的 ME 樣板總成與 ME 催化劑樣板總成機器身分相同但能力不等價，靠這個分辨。
     */
    static String containerKindFor(int index) {
        String[] k = posContainerKind;
        return (k == null || index < 0 || index >= k.length || k[index] == null) ? "" : k[index];
    }

    /** 第 i 個 extras 的實際機器物品 id；未知回 ""。 */
    static String extraMachineFor(int i) {
        String[] m = posExtraMachine;
        return (m == null || i < 0 || i >= m.length || m[i] == null) ? "" : m[i];
    }

    /**
     * 該目的地「可採用」的伺服端建議：GTOCore 標籤的 icon 已可反查機器（未改名的直接貼機器等）→ 回 null，
     * 沿用既有 icon/名稱判定路徑（顯示與排序零變動）；icon 反查不到（接口類、或供應器**被改名**後
     * GTOCore 退 AE2 原生群組、機器 icon 消失）才用建議補位。伺服端對直接貼機器也回報建議（見 Network），
     * 這道門檻確保它只在標籤判不出時生效。多類型建議以 {@link #pickSuggestion} 擇一（優先吻合 current）。
     */
    @Nullable
    static GTRecipeType usableSuggestionFor(ListBoxReflector.Dest d, @Nullable GTRecipeType current) {
        if (RecipeTypeIcons.typesForIcon(d.icon()) != null) {
            return null; // 標籤已可判 → 原路
        }
        return pickSuggestion(suggestionsFor(d.index()), current);
    }

    /**
     * 該目的地的「有效機器」＝手動指定（座標／名稱鍵）優先，無則用可採用的伺服端建議
     *（{@link #usableSuggestionFor}，多類型時優先挑吻合 current 者）。供 overlay 顯示、排序、tier 判定共用；
     * 手動指定永遠蓋過建議。
     */
    @Nullable
    static GTRecipeType effectiveMachineFor(ListBoxReflector.Dest d, @Nullable GTRecipeType current) {
        GTRecipeType manual = PatternUploadConfig.machineFor(posKeyFor(d.index()), d.name().getString());
        return manual != null ? manual : usableSuggestionFor(d, current);
    }

    /**
     * 聊天欄上傳回報的目標顯示：優先報**機器**——「機器名 (供應器標籤)」；機器判不出（或與標籤同名）退標籤。
     * machine 傳入已判定的機器類型（自動直傳＝有效機器 ?? 樣板類型）；null 時合成容器以 icon 物品名當機器名
     *（分子裝配室／裝配矩陣的 icon 即機器物品，供應器被改名時標籤是自訂名、icon 仍是機器）。
     */
    static net.minecraft.network.chat.Component sentDisplayName(ListBoxReflector.Dest d, @Nullable GTRecipeType machine) {
        String label = d.name().getString();
        String machineName = null;
        var actual = machineItemStack(machineItemFor(d.index()));
        if (actual != null) {
            machineName = actual.getHoverName().getString(); // 伺服端回報的實際機器（化工廠≠同類型代表機器）
        } else if (machine != null) {
            machineName = RecipeTypeIcons.name(machine).getString();
        } else if (d.icon() != null && RecipeTypeIcons.isCraftContainer(d.icon())) {
            machineName = d.icon().getDisplayName().getString();
        }
        if (machineName == null || machineName.isEmpty() || machineName.equals(label)) {
            return d.name();
        }
        return net.minecraft.network.chat.Component.literal(machineName + " (" + label + ")");
    }

    /**
     * 非處理樣板（合成/鍛造/切石）只有分子裝配室或裝配矩陣能做，
     * 配方類型概念不適用；伺服端 gto$craftFirst 已把合成容器排前，本地不要再動。
     */
    static boolean isCraftMode(AbstractContainerMenu menu) {
        return menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm &&
                petm.getMode() != appeng.parts.encoding.EncodingMode.PROCESSING;
    }

    /**
     * 目前樣板對應的配方類型：讀選單同步的 GTOCore @GuiSync 欄位 gtocore$recipe。
     * <p>
     * GTOCore 只在「載入既有樣板」時更新此欄位，手動填格新編碼不會清 → 會殘留上一張樣板的配方
     *（例：先編液化機、再手動編壓印器樣板，欄位仍是液化機）。因此**必須驗證**編碼格的主產物
     * 確實是該配方 id 的產物之一；對不上即視為殘留 → 回 null（面板顯示未知、不自動上傳）。
     */
    /**
     * {@link UploadOverlay#render} 每幀經 headerIcon()＋headerTitle() 呼叫本方法（閒置時每幀 2 次），
     * 內層 {@link #typeProducesPatternOutput} 會掃整張配方表（真機器數百～數千條）——唯一 per-frame 重活。
     * 以 (menu, gtocore$recipe 字串, 產物槽簽章) 為鍵快取結果；只在樣板實際變動時重算（純等價）。
     */
    private static java.lang.reflect.Field recipeField;
    private static boolean recipeFieldBroken = false;
    @Nullable
    private static AbstractContainerMenu crtMenu;
    @Nullable
    private static String crtKey;
    @Nullable
    private static GTRecipeType crtVal;
    /** 本次樣板配方的電壓 tier（GTRecipeDefinition.tier，產物匹配定義取最小值）；-1＝未知（proxy 路徑等）。 */
    private static int crtTier = -1;
    /**
     * 本次樣板配方的**分類** registry path（{@code GTRecipeDefinition.recipeCategory}）；""＝未知／歧義／預設分類。
     * 同一配方類型底下可再分「專屬分類」——魔力組裝（{@code mana_assembler}）就是 ASSEMBLER_RECIPES 的分類，
     * 魔力組裝機與一般組裝機**型別完全相同**，只有分類分得出誰該做（1.28.0 排序用，不參與匹配判定）。
     */
    private static String crtCat = "";

    @Nullable
    static GTRecipeType currentRecipeType(AbstractContainerMenu menu) {
        if (isCraftMode(menu)) {
            return null; // 殘留的 gtocore$recipe 不適用於合成類樣板
        }
        String recipe = readGtoRecipe(menu);
        // 鍵：配方 id 字串 + 產物槽簽章（皆便宜）；命中即跳過整張配方表掃描
        String key = recipe + ' ' + outputSignature(menu);
        if (menu == crtMenu && key.equals(crtKey)) {
            return crtVal;
        }
        GTRecipeType result = null;
        int tier = -1;
        String cat = "";
        if (recipe != null && !recipe.isEmpty()) {
            ResourceLocation typeRl = ResourceLocation.tryParse(recipe.split("/")[0]);
            GTRecipeType type = typeRl == null ? null : GTRegistries.RECIPE_TYPES.get(typeRl);
            if (type != null) {
                RecipeMatch m = matchingRecipe(menu, type);
                if (m != null) {
                    result = type;
                    tier = m.minTier();
                    cat = m.categoryPath();
                }
            }
        }
        if (result == null) {
            // gtocore$recipe 空（原版燒煉等 proxy 配方）或殘留對不上，反查 proxy 機器（電壓未知）
            result = proxyRecipeTypeFor(menu);
        }
        crtMenu = menu;
        crtKey = key;
        crtVal = result;
        crtTier = tier;
        crtCat = cat;
        return result;
    }

    /**
     * 本次樣板配方的分類 registry path；""＝未知／歧義／預設分類（排序不受影響）。
     * 呼叫前確保 {@link #currentRecipeType} 同一 menu 已算過（共用快取）。
     */
    static String currentRecipeCategory(AbstractContainerMenu menu) {
        currentRecipeType(menu);
        return crtCat;
    }

    /** 本次樣板配方的電壓 tier；-1＝未知。呼叫前確保 {@link #currentRecipeType} 同一 menu 已算過（共用快取）。 */
    static int currentRecipeTier(AbstractContainerMenu menu) {
        currentRecipeType(menu);
        return crtTier;
    }

    /** 電壓等級名（GTValues.VN 如 "LV"）→ tier index；空／對不上回 -1。 */
    static int tierIndexOf(String vn) {
        if (vn == null || vn.isEmpty()) {
            return -1;
        }
        String[] arr = com.gregtechceu.gtceu.api.GTValues.VN;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(vn)) {
                return i;
            }
        }
        return "MAX".equals(vn) ? arr.length : -1;
    }

    /**
     * 該目的地機器電壓是否跑得動本次配方：配方 tier 未知／機器 tier 未知 → 寬容放行（維持舊行為）；
     * 皆已知 → 機器 tier ≥ 配方 tier 才可（GT 低壓機器跑不了高壓配方；高壓可跑低壓配方＝超頻）。
     */
    static boolean voltageOk(int index, int recipeTier) {
        if (recipeTier < 0) {
            return true;
        }
        int mt = tierIndexOf(tierFor(index));
        return mt < 0 || mt >= recipeTier;
    }

    /** 讀 GTOCore @GuiSync 欄位 gtocore$recipe（反射 Field 快取）；欄位不存在即永久停用，取值失敗則下次重抓。 */
    @Nullable
    private static String readGtoRecipe(AbstractContainerMenu menu) {
        if (recipeFieldBroken) {
            return null;
        }
        try {
            if (recipeField == null) {
                recipeField = menu.getClass().getField("gtocore$recipe");
            }
            Object v = recipeField.get(menu);
            return v instanceof String s ? s : null;
        } catch (NoSuchFieldException e) {
            recipeFieldBroken = true; // 欄位名變動 → 永久退「未知」，僅影響顯示 icon
            return null;
        } catch (Throwable t) {
            recipeField = null; // 型別不符等 → 下次重抓，不永久停用
            return null;
        }
    }

    /** 產物槽的內容簽章（各槽 AEKey hashCode 串接）；便宜，用來當 currentRecipeType 快取鍵的一部分。 */
    private static String outputSignature(AbstractContainerMenu menu) {
        if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var slot : petm.getProcessingOutputSlots()) {
            net.minecraft.world.item.ItemStack st = slot.getItem();
            if (st.isEmpty()) {
                sb.append(';');
                continue;
            }
            appeng.api.stacks.GenericStack gs = appeng.api.stacks.GenericStack.unwrapItemStack(st);
            appeng.api.stacks.AEKey k = gs != null ? gs.what() : appeng.api.stacks.AEItemKey.of(st);
            sb.append(k == null ? 0 : k.hashCode()).append(';');
        }
        return sb.toString();
    }

    /**
     * gtocore$recipe 認不出時的後備：靠「編碼格產物能否由某 GTRecipeType 委派（proxy）的原版配方做出」反查機器。
     * <p>
     * gtceu 電力熔爐（FURNACE_RECIPES）不把原版燒煉配方收進自己的 recipes，而是 proxyRecipes = {minecraft:smelting}
     * 委派原版 RecipeManager；且 GTOCore 只在樣板帶 GTRecipeDefinition 時才填 gtocore$recipe，原版燒煉樣板該欄位為空。
     * 兩者疊加使原本一律回 null（面板顯示未知機器）。這裡改查客戶端 RecipeManager 的 proxy 配方產物：
     * 唯一命中一種機器類型才回傳（多種歧義不猜、開面板，與既有哲學一致）。
     */
    @Nullable
    private static GTRecipeType proxyRecipeTypeFor(AbstractContainerMenu menu) {
        try {
            if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm)) {
                return null;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            // 收集編碼格產出的物品鍵（proxy 配方皆物品產出；流體略過）
            List<appeng.api.stacks.AEItemKey> outs = new java.util.ArrayList<>();
            for (var slot : petm.getProcessingOutputSlots()) {
                net.minecraft.world.item.ItemStack st = slot.getItem();
                if (st.isEmpty()) {
                    continue;
                }
                appeng.api.stacks.GenericStack gs = appeng.api.stacks.GenericStack.unwrapItemStack(st);
                appeng.api.stacks.AEKey k = gs != null ? gs.what() : appeng.api.stacks.AEItemKey.of(st);
                if (k instanceof appeng.api.stacks.AEItemKey ik) {
                    outs.add(ik);
                }
            }
            if (outs.isEmpty()) {
                return null;
            }
            net.minecraft.world.item.crafting.RecipeManager rm = mc.level.getRecipeManager();
            net.minecraft.core.RegistryAccess ra = mc.level.registryAccess();
            java.util.Set<GTRecipeType> matched = new java.util.HashSet<>();
            for (var e : RecipeTypeIcons.proxyOwners().entrySet()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                List<? extends net.minecraft.world.item.crafting.Recipe<?>> list =
                        rm.getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) e.getKey());
                for (net.minecraft.world.item.crafting.Recipe<?> r : list) {
                    net.minecraft.world.item.ItemStack result = r.getResultItem(ra);
                    if (result.isEmpty()) {
                        continue;
                    }
                    appeng.api.stacks.AEItemKey rk = appeng.api.stacks.AEItemKey.of(result);
                    if (rk != null && outs.contains(rk)) {
                        matched.addAll(e.getValue());
                        break;
                    }
                }
            }
            // 唯一機器類型才回傳；多種能做則歧義不猜（回 null 開面板）
            return matched.size() == 1 ? matched.iterator().next() : null;
        } catch (Throwable t) {
            return null; // API 異動/查不到，未知機器（開面板讓玩家自選）
        }
    }

    /**
     * 判定 type 這台機器是否真能做出「編碼格的主產物」並回**匹配定義的最小電壓 tier**——防 gtocore$recipe 殘留。
     * <p>
     * gtocore$recipe 只在載入既有樣板時更新，殘留的可能是**別的機器**（壓印器樣板殘留成液化機）
     * 或**同機器別條配方**（組裝機樣板殘留成 disassembly），故不比對精確 recipe id，
     * 改看「該類型的<b>任一</b>配方」是否產出此樣板產物。gtceu 配方不在原版 client RecipeManager，
     * 查 {@code GTRecipeType.recipes}（gtceu 同步到 client 的表）。
     * <p>
     * 回傳：null＝無配方匹配（非該機器／殘留）；否則所有匹配定義中最小的 {@code GTRecipeDefinition.tier}
     *（同產物多條配方取最寬容值；電壓排序／自動直傳的電壓檢查用）＋**配方分類**（見 {@link RecipeMatch}）。
     */
    /** 產物匹配結果：最小電壓 tier ＋ 分類 registry path（分類不唯一／預設＝""）。 */
    private record RecipeMatch(int minTier, String categoryPath) {}

    @Nullable
    private static RecipeMatch matchingRecipe(AbstractContainerMenu menu, GTRecipeType type) {
        try {
            if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm)) {
                return null;
            }
            // 收集編碼格所有非空產出物的 AEKey（物品或流體）。
            // 流體產物在 FakeSlot 是包成 wrapper item，用公開 API GenericStack.unwrapItemStack 解出
            //（避免反射 private encodedOutputsInv——其欄位名在正式包會被 reobf 成 SRG）。
            java.util.List<appeng.api.stacks.AEKey> keys = new java.util.ArrayList<>();
            for (var slot : petm.getProcessingOutputSlots()) {
                net.minecraft.world.item.ItemStack st = slot.getItem();
                if (st.isEmpty()) {
                    continue;
                }
                appeng.api.stacks.GenericStack gs = appeng.api.stacks.GenericStack.unwrapItemStack(st);
                keys.add(gs != null ? gs.what() : appeng.api.stacks.AEItemKey.of(st));
            }
            if (keys.isEmpty()) {
                return null;
            }
            // 該類型任一配方的 item/fluid Outputs 命中任一產出物即算匹配；收所有匹配定義的最小 tier
            // 與分類（分類不唯一＝同產物跨分類，保守回 "" 不影響排序）
            Integer best = null;
            java.util.Set<String> cats = new java.util.HashSet<>();
            for (var def : type.recipes.values()) {
                boolean hit = false;
                for (var key : keys) {
                    if (key instanceof appeng.api.stacks.AEItemKey ik) {
                        for (var content : def.itemOutputs) {
                            if (content.inner.testAeKay(ik)) {
                                hit = true;
                                break;
                            }
                        }
                    } else if (key instanceof appeng.api.stacks.AEFluidKey fk) {
                        for (var content : def.fluidOutputs) {
                            if (content.inner.testAeKay(fk)) {
                                hit = true;
                                break;
                            }
                        }
                    }
                    if (hit) {
                        break;
                    }
                }
                if (hit) {
                    if (best == null || def.tier < best) {
                        best = def.tier;
                    }
                    if (def.recipeCategory != null && def.recipeCategory.registryKey != null) {
                        cats.add(def.recipeCategory.registryKey.getPath());
                    }
                }
            }
            return best == null ? null : new RecipeMatch(best, cats.size() == 1 ? cats.iterator().next() : "");
        } catch (Throwable t) {
            return null; // 任何 API 異動/查不到 → 保守視為不匹配（改開面板讓玩家自選）
        }
    }

    /**
     * 客戶端可見的網路空白樣板數量（gtolib$sendPattern 每送一張目的地就從網路抽一張空白樣板）。
     * PatternEncodingTermMenu extends MEStorageMenu → 有同步到客戶端的網路物品表（ClientRepo）。
     * 回傳：≥0 = 網路空白樣板數；-1 = 查不到（保守維持原樂觀行為，不擋上傳）。
     */
    static long blankPatternCount(AbstractContainerMenu menu) {
        try {
            if (!(menu instanceof appeng.menu.me.common.MEStorageMenu me)) {
                return -1;
            }
            appeng.menu.me.common.IClientRepo repo = me.getClientRepo();
            if (repo == null) {
                return -1;
            }
            var entry = repo.getByKey(appeng.api.stacks.AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN));
            return entry == null ? 0 : entry.getStoredAmount();
        } catch (Throwable t) {
            return -1; // API 異動/查不到 → 回 -1，維持原行為
        }
    }

    @Nullable
    private static UploadOverlay activeOverlay(ScreenEvent event) {
        if (overlay != null && event.getScreen() == overlay.screen()) {
            return overlay;
        }
        return null;
    }

    // ---------------------------------------------------------------- 劫持

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof PatternEncodingTermScreen<?> screen)) {
            return;
        }
        if (!(screen instanceof IExtendedPatternEncodingTerm term)) {
            return;
        }
        var box = term.gto$getPatternDestDisplay();
        if (box == null || !box.isVisible()) {
            return;
        }
        // GTOCore 剛把清單設為可見（新一批目的地）→ 接管
        List<ListBoxReflector.Dest> dests = ListBoxReflector.extract(box);
        if (dests == null) {
            return; // 反射失敗：保留 GTOCore 原清單
        }
        box.setVisible(false);
        // 中鍵手勢：這批清單強制開面板、跳過所有自動直傳（消費即清）
        boolean force = forcePanel;
        forcePanel = false;
        if (!force && isCraftMode(screen.getMenu())) {
            // 合成/鍛造/切石樣板：只有分子裝配室/裝配矩陣能做。伺服端 gto$craftFirst 對這些容器
            // 不可靠（isCraftingContainer 沒實作、平手看網路迭代順序，分子裝配室可能排最後），
            // 所以客戶端自己以 icon 認合成容器：挑第一個未滿的合成容器直傳；全滿 → 停止動作。
            overlay = null;
            var player = Minecraft.getInstance().player;
            // 只允許分子裝配室/裝配矩陣；其他供應器一律不上傳（沒有保底）
            ListBoxReflector.Dest target = null;
            boolean sawCraftContainer = false;
            for (var d : dests) {
                if (RecipeTypeIcons.isCraftContainer(d.icon())) {
                    sawCraftContainer = true;
                    if (!d.full()) {
                        target = d;
                        break;
                    }
                }
            }
            if (target != null && blankPatternCount(screen.getMenu()) == 0) {
                // 網路沒空白樣板 → 不上傳、不謊報成功
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.no_blank"), false);
                }
                LOGGER.info("[pattern_upload] craft: no blank pattern in network, aborted");
            } else if (target != null) {
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(target.index());
                if (player != null) {
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）；目標優先報機器（icon 物品名）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.craft.sent", sentDisplayName(target, null)), false);
                }
                LOGGER.info("[pattern_upload] craft pattern sent directly to '{}'", target.name().getString());
            } else if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        sawCraftContainer ? "pattern_upload.craft.full" : "pattern_upload.craft.none"), false);
            }
            return;
        }
        // 處理樣板：把「自動直傳 vs 開面板」決策延到伺服端座標／建議回來再判（見 decidePending）。
        // 向伺服端要座標＋建議機器；收到（gen 相符）或逾時即決策。決策前不顯示面板（終端正常顯示）。
        requestPositionsFor(screen.getMenu());
        pendingScreen = screen;
        pendingDests = dests;
        pendingForce = force;
        pendingWaitTicks = DECIDE_WAIT_TICKS;
        overlay = null;
        LOGGER.info("[pattern_upload] hijacked {} entries → awaiting positions/suggestions before deciding", dests.size());
    }

    /**
     * 座標／建議到齊（或逾時）後執行：處理樣板的「自動直傳 vs 開面板」決策。
     * 收集所有「明確匹配」（tier 0 指定吻合／tier 1 機器類型吻合，此時含伺服端建議解析出的子網機器）：
     * 剛好一個 → 直傳；多個 → 開面板讓玩家自選（不亂猜）；零個 → 開面板。清 pending。
     */
    private static void decidePending() {
        PatternEncodingTermScreen<?> screen = pendingScreen;
        List<ListBoxReflector.Dest> dests = pendingDests;
        boolean force = pendingForce;
        pendingScreen = null;
        pendingDests = null;
        pendingWaitTicks = -1;
        if (screen == null || dests == null) {
            return;
        }
        if (Minecraft.getInstance().screen != screen) {
            return; // 決策前玩家已關/切終端 → 放棄
        }
        GTRecipeType current = force ? null : currentRecipeType(screen.getMenu());
        if (current != null) {
            int recipeTier = currentRecipeTier(screen.getMenu());
            List<ListBoxReflector.Dest> matches = new java.util.ArrayList<>();
            List<ListBoxReflector.Dest> voltageBlocked = new java.util.ArrayList<>();
            for (var d : dests) {
                int tier = UploadOverlay.sortTier(d, current);
                // 電壓不足（機器 tier < 配方 tier）不算明確匹配：不自動直傳到跑不動的機器（未知電壓寬容放行）
                if (tier == 0 || tier == 1) {
                    if (voltageOk(d.index(), recipeTier)) {
                        matches.add(d);
                    } else {
                        voltageBlocked.add(d); // 機器類型吻合但電壓判定跑不動 → 記下來供診斷
                    }
                }
            }
            // 嚴格唯一（1.24.0）：match 多於一個一律開面板讓玩家選，不自動直傳。
            // **例外（1.27.0）**：多個 match 全都指向**同一台機器且同一模式**（伺服端機器身分鍵相同）
            // → 送哪個供應器結果都一樣，視同單一匹配自動直傳（見 mergedSingle）。
            // 同款不同台的可互換機器仍不合併（那是 1.23.0 被收回的行為）。
            ListBoxReflector.Dest single = matches.size() == 1 ? matches.get(0) : mergedSingle(matches);
            boolean merged = single != null && matches.size() > 1;
            if (single != null && extraDests().isEmpty()) {
                // extras 非空＝網路上已有供應器裝著這張樣板（GTO 從清單藏掉）→ 不自動直傳，
                // 開面板讓玩家看到置頂的「已有該配方」列再自行決定（避免無感重複鋪樣板）。
                ListBoxReflector.Dest target = single;
                overlay = null;
                var player = Minecraft.getInstance().player;
                if (blankPatternCount(screen.getMenu()) == 0) {
                    // 網路沒空白樣板 → 不上傳、不謊報成功
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                "pattern_upload.no_blank"), false);
                    }
                    LOGGER.info("[pattern_upload] single match but no blank pattern in network, aborted");
                    return;
                }
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(target.index());
                if (player != null) {
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）。
                    // 目標優先報機器：有效機器（手動指定/建議）優先，無則用樣板類型 current（本分支必非 null，
                    // target 即以它匹配成功）——改名供應器（標籤=自訂名）也報得出機器。
                    GTRecipeType eff = effectiveMachineFor(target, current);
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.sent", sentDisplayName(target, eff != null ? eff : current)), false);
                }
                LOGGER.info("[pattern_upload] pattern sent directly to '{}' ({})", target.name().getString(),
                        merged ? matches.size() + " providers on the same machine+mode → merged"
                                : "single type match");
                return;
            }
            // 沒能直傳時一律說明「為什麼」——這類「明明只有一台卻不自動上傳」的疑問全靠這幾行定位。
            if (matches.size() > 1) {
                // 逐筆列出「是誰、憑什麼算吻合」——多個 match 一律開面板（1.24.0 嚴格唯一），
                // 玩家常只認得其中一台，需靠這行定位另一個候選（判定來源＝手動指定／伺服端建議／
                // icon 反查／名稱匹配，見 UploadOverlay.sortTier）。
                LOGGER.info("[pattern_upload] {} matches → open panel for user choice; candidates: {}",
                        matches.size(), describeMatches(matches, current));
            } else if (single != null) {
                // 唯一匹配卻沒直傳＝被 extras 押制（網路上已有這張樣板，見上方分支）
                LOGGER.info("[pattern_upload] single match '{}' suppressed: pattern already exists in {} provider(s) "
                        + "→ open panel", single.name().getString(), extraDests().size());
            } else if (!voltageBlocked.isEmpty()) {
                // 機器類型吻合但被電壓判定排除 → 印配方 tier 與各機器電壓，辨別是真跑不動還是判定過嚴
                LOGGER.info("[pattern_upload] no match: {} type-matching dest(s) excluded by voltage "
                        + "(recipe tier {} = {}); {} → open panel",
                        voltageBlocked.size(), recipeTier,
                        recipeTier >= 0 && recipeTier < com.gregtechceu.gtceu.api.GTValues.VN.length
                                ? com.gregtechceu.gtceu.api.GTValues.VN[recipeTier] : "?",
                        describeMatches(voltageBlocked, current));
            } else {
                LOGGER.info("[pattern_upload] no explicit match for recipe type '{}' (recipe tier {}) → open panel",
                        current.registryName, recipeTier);
            }
        } else if (!force) {
            // 配方類型判不出（gtocore$recipe 空／殘留對不上／產物在該類型配方表裡找不到）→ 完全不走
            // 自動直傳判定。印出殘留欄位與產物簽章，直接看得出是哪一種。
            LOGGER.info("[pattern_upload] recipe type unknown (gtocore$recipe='{}', outputs='{}') → open panel",
                    readGtoRecipe(screen.getMenu()), outputSignature(screen.getMenu()));
        }
        // 座標／建議已在 pending 期間請求過，此時已載入 → 面板直接顯示正確機器與排序
        overlay = new UploadOverlay(screen, dests, force);
        LOGGER.info("[pattern_upload] opened panel: {} entries", dests.size());
    }

    /**
     * 多個明確匹配但**全部指向同一台機器、同一模式**時，挑出的代表目的地（視同單一匹配自動直傳）；
     * 條件不成立回 null（照 1.24.0 嚴格唯一開面板）。
     * <p>
     * 「同一台」以伺服端回報的機器身分鍵（多方塊＝控制器座標）判定——機器類型與物品只證明得了「同款」，
     * 兩台同款機器各掛一個供應器仍須玩家自選（1.23.0 曾合併同款、已收回）。判不出身分（舊伺服端、
     * 接口子網歧義、tesseract 混綁）一律不合併。
     * <p>
     * 「同一模式」除機器相同外再比**伺服端建議原字串**與**手動指定**：同一台機器的兩個供應器可能貼在
     * 設了不同配方類型的可程式化倉上（{@code programmedTypeOf} 優先於控制器），或被玩家手動指成不同機器
     * ——這種情況送誰結果不同，不可合併。
     * <p>
     * 代表取**剩餘空格最少**的那個（優先塞快滿的供應器、讓樣板集中，與面板 1.19.0 排序同理念）；
     * 空格未知者排最後，同分取伺服端順序在前者。
     */
    @Nullable
    private static ListBoxReflector.Dest mergedSingle(List<ListBoxReflector.Dest> matches) {
        if (matches.size() < 2) {
            return null;
        }
        String machineKey = machineKeyFor(matches.get(0).index());
        if (machineKey.isEmpty()) {
            return null; // 機器身分判不出 → 不敢視為同一台
        }
        String mode = suggestRawFor(matches.get(0).index());
        String kind = containerKindFor(matches.get(0).index());
        GTRecipeType manual0 = manualMachineFor(matches.get(0));
        for (var d : matches) {
            if (!machineKey.equals(machineKeyFor(d.index()))
                    || !mode.equals(suggestRawFor(d.index()))
                    // 容器種類不同＝能力不等價（同機的 ME 樣板總成 vs ME 催化劑樣板總成：催化劑總成
                    // 不消耗催化劑耐久、能做一般總成的事，反之不行）→ 送誰結果不同，不可合併
                    || !kind.equals(containerKindFor(d.index()))
                    || manualMachineFor(d) != manual0) {
                return null;
            }
        }
        ListBoxReflector.Dest best = null;
        int bestFree = Integer.MAX_VALUE;
        for (var d : matches) {
            int f = freeSlotsFor(d.index());
            int rank = f < 0 ? Integer.MAX_VALUE : f;
            if (best == null || rank < bestFree) {
                best = d;
                bestFree = rank;
            }
        }
        return best;
    }

    /** 伺服端建議的**原始字串**（可為逗號串多類型；未回報＝""）——同機合併時用來比「模式是否相同」。 */
    private static String suggestRawFor(int index) {
        String[] s = posSuggest;
        return (s == null || index < 0 || index >= s.length || s[index] == null) ? "" : s[index];
    }

    /** 該目的地的手動指定機器（座標鍵優先、退名稱鍵）；未指定回 null。 */
    @Nullable
    private static GTRecipeType manualMachineFor(ListBoxReflector.Dest d) {
        return PatternUploadConfig.machineFor(posKeyFor(d.index()), d.name().getString());
    }

    /**
     * 多個明確匹配時的診斷字串：每筆「#index '標籤' via=判定來源 pos=座標鍵 tier=電壓 machine=機器物品 free=空格」。
     * {@code via} 依 {@link UploadOverlay#sortTier} 的同一優先序回推（手動指定 → 伺服端建議 → icon 反查 →
     * 名稱最長機器名），供「為什麼開面板／另一個候選是誰」的現場定位。
     */
    private static String describeMatches(List<ListBoxReflector.Dest> matches, GTRecipeType current) {
        StringBuilder sb = new StringBuilder();
        for (var d : matches) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            String posKey = posKeyFor(d.index());
            GTRecipeType manual = PatternUploadConfig.machineFor(posKey, d.name().getString());
            GTRecipeType sug = manual != null ? null : usableSuggestionFor(d, current);
            String via;
            if (manual != null) {
                via = "手動指定=" + manual.registryName;
            } else if (sug != null) {
                via = "伺服端建議=" + sug.registryName;
            } else if (RecipeTypeIcons.typesForIcon(d.icon()) != null) {
                via = "icon反查";
            } else {
                via = "名稱匹配";
            }
            sb.append('#').append(d.index()).append(" '").append(d.name().getString()).append("' via=").append(via)
                    .append(" pos=").append(posKey == null ? "-" : posKey)
                    .append(" tier=").append(tierFor(d.index()).isEmpty() ? "-" : tierFor(d.index()))
                    .append(" machine=").append(machineItemFor(d.index()).isEmpty() ? "-" : machineItemFor(d.index()))
                    // 機器身分鍵與建議原字串：合併未生效時，一眼看出是「不同台機器」還是「同台不同模式」
                    .append(" mkey=").append(machineKeyFor(d.index()).isEmpty() ? "-" : machineKeyFor(d.index()))
                    .append(" mode=").append(suggestRawFor(d.index()).isEmpty() ? "-" : suggestRawFor(d.index()))
                    .append(" kind=").append(containerKindFor(d.index()).isEmpty() ? "-" : containerKindFor(d.index()))
                    .append(" free=").append(freeSlotsFor(d.index()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 事件疊加

    // LOWEST：Render.Post 監聽器最後執行 → 面板最後畫 → 置頂蓋過 EMI/JEI 的右側物品
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        var o = activeOverlay(event);
        if (o != null) {
            o.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    // HIGHEST：點擊 Pre 最先執行 → 面板先吃掉點擊並取消，EMI 不會搶走
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null) {
            if (o.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
            return;
        }
        // 無面板時：中鍵點 GTOCore 上傳（編碼）鈕 → 要一批目的地清單但強制開面板（不自動上傳）
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                && event.getScreen() instanceof PatternEncodingTermScreen<?> screen
                && screen instanceof IExtendedPatternEncodingTerm term) {
            var btn = term.gto$getEncodeButton();
            if (btn != null && overEncodeButton(btn, event.getMouseX(), event.getMouseY())) {
                forcePanel = true;
                ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendEncodeRequest();
                event.setCanceled(true);
                LOGGER.info("[pattern_upload] middle-click encode button → force panel (no auto-upload)");
            }
        }
    }

    /** 滑鼠是否落在上傳鈕矩形內（手動判界，不受按鈕 active 狀態影響）。 */
    private static boolean overEncodeButton(appeng.client.gui.widgets.ActionButton btn, double mx, double my) {
        return mx >= btn.getX() && mx < btn.getX() + btn.getWidth()
                && my >= btn.getY() && my < btn.getY() + btn.getHeight();
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (overlay != null && event.getScreen() == overlay.screen()) {
            overlay = null;
        }
        // 關終端就清中鍵旗標，避免無效樣板（伺服端沒回清單）殘留到下次右鍵誤觸
        if (event.getScreen() instanceof PatternEncodingTermScreen<?>) {
            forcePanel = false;
            // 放棄尚未決策的 pending（終端已關）
            pendingScreen = null;
            pendingDests = null;
            pendingWaitTicks = -1;
            // 換世代 + 清座標：關終端後若有慢回的 S2C 座標也視為過期，不落到下次開啟的清單
            posGen++;
            posDims = null;
            posPacked = null;
            posSuggest = null;
            posFree = null;
            posHasRecipe = null;
            posExtras = null;
            posTier = null;
            posSugMachine = null;
            posExtraMachine = null;
            // 清 currentRecipeType 快取（不長抓已關終端的 menu 參照）
            crtMenu = null;
            crtKey = null;
            crtVal = null;
            crtTier = -1;
        }
    }

    // ---------------------------------------------------------- 測試指令

    /** 等待「樣板編碼終端開啟」後注入假資料的倒數；-1 = 停用。 */
    private static int testPending = -1;

    /**
     * 診斷指令 /patternupload_test：之後 30 秒內只要開啟樣板編碼終端，
     * 就以假資料觸發 GTOCore 的清單 → 本 mod 應立即劫持並顯示自製面板。
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(net.minecraftforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("patternupload_test")
                        .executes(ctx -> {
                            testPending = 600; // 30 秒內開終端即觸發
                            ctx.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "[pattern_upload] 測試待命：30 秒內開啟樣板編碼終端即會顯示測試清單"));
                            return 1;
                        }));
    }

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        // 決策逾時：伺服端沒回座標／建議（沒裝本 mod）→ 到期用現有資料（無建議）決策，維持舊行為
        if (pendingWaitTicks >= 0 && --pendingWaitTicks < 0) {
            decidePending();
        }
        if (testPending < 0) {
            return;
        }
        testPending--;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PatternEncodingTermScreen<?>) {
            testPending = -1;
            LOGGER.info("[pattern_upload] TEST: injecting dummy destinations via GTOCore list");
            try {
                var dummyGroup = new appeng.api.implementations.blockentities.PatternContainerGroup(
                        appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.CRAFTING_TABLE),
                        net.minecraft.network.chat.Component.literal("pattern_upload test"),
                        java.util.List.of());
                com.gtocore.client.Message.Client.patternDestinationReceived(new com.gtocore.client.Message.PatternDestination[] {
                        new com.gtocore.client.Message.PatternDestination(dummyGroup, false)
                });
            } catch (Throwable t) {
                LOGGER.error("[pattern_upload] TEST threw", t);
            }
        }
    }
}
