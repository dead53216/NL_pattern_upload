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
    /** 伺服端建議機器（配方類型 registry id，照 index 對齊；空字串＝無建議）。接口→子網→存儲總線解析而來。 */
    @Nullable
    private static String[] posSuggest = null;

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

    /** 第 index 個目的地的伺服端建議機器（接口→子網→存儲總線解析）；無建議回 null。 */
    @Nullable
    static GTRecipeType suggestionFor(int index) {
        String[] sug = posSuggest;
        if (sug == null || index < 0 || index >= sug.length || sug[index] == null || sug[index].isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(sug[index]);
        return rl == null ? null : GTRegistries.RECIPE_TYPES.get(rl);
    }

    /**
     * 第 index 個目的地的「有效機器」＝手動指定（座標／名稱鍵）優先，無則用伺服端建議。
     * 供 overlay 顯示、排序、tier 判定共用；手動指定永遠蓋過建議。
     */
    @Nullable
    static GTRecipeType effectiveMachineFor(int index, String providerName) {
        GTRecipeType manual = PatternUploadConfig.machineFor(posKeyFor(index), providerName);
        return manual != null ? manual : suggestionFor(index);
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

    @Nullable
    static GTRecipeType currentRecipeType(AbstractContainerMenu menu) {
        if (isCraftMode(menu)) {
            return null; // 殘留的 gtocore$recipe 不適用於合成類樣板
        }
        String recipe = readGtoRecipe(menu);
        // 鍵：配方 id 字串 + 產物槽簽章（皆便宜）；命中即跳過整張配方表掃描
        String key = recipe + ' ' + outputSignature(menu);
        if (menu == crtMenu && key.equals(crtKey)) {
            return crtVal;
        }
        GTRecipeType result = (recipe != null && !recipe.isEmpty())
                ? computeCurrentRecipeType(menu, recipe) : null;
        if (result == null) {
            // gtocore$recipe 空（原版燒煉等 proxy 配方）或殘留對不上，反查 proxy 機器
            result = proxyRecipeTypeFor(menu);
        }
        crtMenu = menu;
        crtKey = key;
        crtVal = result;
        return result;
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

    @Nullable
    private static GTRecipeType computeCurrentRecipeType(AbstractContainerMenu menu, String recipe) {
        ResourceLocation typeRl = ResourceLocation.tryParse(recipe.split("/")[0]);
        if (typeRl == null) {
            return null;
        }
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(typeRl);
        if (type == null) {
            return null;
        }
        return typeProducesPatternOutput(menu, type) ? type : null;
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
     * 判定 type 這台機器是否真能做出「編碼格的主產物」——防 gtocore$recipe 殘留。
     * <p>
     * gtocore$recipe 只在載入既有樣板時更新，殘留的可能是**別的機器**（壓印器樣板殘留成液化機）
     * 或**同機器別條配方**（組裝機樣板殘留成 disassembly），故不比對精確 recipe id，
     * 改看「該類型的<b>任一</b>配方」是否產出此樣板產物。gtceu 配方不在原版 client RecipeManager，
     * 查 {@code GTRecipeType.recipes}（gtceu 同步到 client 的表）。
     */
    private static boolean typeProducesPatternOutput(AbstractContainerMenu menu, GTRecipeType type) {
        try {
            if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu petm)) {
                return false;
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
                return false;
            }
            // 該類型任一配方的 item/fluid Outputs 命中任一產出物即算匹配
            for (var def : type.recipes.values()) {
                for (var key : keys) {
                    if (key instanceof appeng.api.stacks.AEItemKey ik) {
                        for (var content : def.itemOutputs) {
                            if (content.inner.testAeKay(ik)) {
                                return true;
                            }
                        }
                    } else if (key instanceof appeng.api.stacks.AEFluidKey fk) {
                        for (var content : def.fluidOutputs) {
                            if (content.inner.testAeKay(fk)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false; // 任何 API 異動/查不到 → 保守視為不匹配（改開面板讓玩家自選）
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
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.craft.sent", target.name()), false);
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
            List<ListBoxReflector.Dest> matches = new java.util.ArrayList<>();
            for (var d : dests) {
                int tier = UploadOverlay.sortTier(d, current);
                if (tier == 0 || tier == 1) {
                    matches.add(d);
                }
            }
            if (matches.size() == 1) {
                ListBoxReflector.Dest target = matches.get(0);
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
                    // false = 聊天欄（actionbar 會被終端 GUI 蓋住看不到）
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pattern_upload.sent", target.name()), false);
                }
                LOGGER.info("[pattern_upload] pattern sent directly to '{}' (single type match)", target.name().getString());
                return;
            }
            if (matches.size() > 1) {
                LOGGER.info("[pattern_upload] {} matches → open panel for user choice", matches.size());
            }
        }
        // 座標／建議已在 pending 期間請求過，此時已載入 → 面板直接顯示正確機器與排序
        overlay = new UploadOverlay(screen, dests, force);
        LOGGER.info("[pattern_upload] opened panel: {} entries", dests.size());
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
            // 清 currentRecipeType 快取（不長抓已關終端的 menu 參照）
            crtMenu = null;
            crtKey = null;
            crtVal = null;
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
