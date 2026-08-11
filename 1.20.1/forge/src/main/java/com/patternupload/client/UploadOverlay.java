package com.patternupload.client;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.me.items.PatternEncodingTermMenu;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上傳介面 overlay：由 ScreenEvent 疊加在樣板編碼終端上。
 * DESTINATIONS 模式列出目的地樣板供應器；標題列最左顯示樣板對應機器（自動判定）。
 * 點「目的地列的 icon」→ MACHINE_SELECT 模式指定該供應器是什麼機器（接口貼子網時用），
 * 指定後列名顯示「機器名（原名）」，指定持久化於 config/pattern_upload.json；吻合者本地浮頂。
 * 標題列可拖曳、左上角可縮放（寬與列數），位置與尺寸皆持久化；
 * 預設位置在合成欄（3x3 編碼格）右邊。搜尋欄支援 Just Enough Characters 拼音比對（軟依賴）。
 */
final class UploadOverlay {

    private enum Mode {
        DESTINATIONS,
        MACHINE_SELECT
    }

    private static final int ROW_H = 18;
    private static final int HEADER_H = 18;
    /** 底部狀態列（放已選提示、捲動位置、警告）高度；獨立一條，不與列表重疊。 */
    private static final int FOOTER_H = 12;
    private static final int DEFAULT_W = 150;
    private static final int DEFAULT_ROWS = 6;
    private static final int MIN_W = 120;
    private static final int MAX_W = 280;
    private static final int MIN_ROWS = 3;
    private static final int MAX_ROWS_LIMIT = 12;
    /** MACHINE_SELECT 清單裡「清除指定」列的 destIndex 哨兵值。 */
    private static final int CLEAR_ROW = -2;
    /** 「已有該配方但被 GTO 藏掉」的置頂資訊列 sentinel（不可上傳、不可指定機器）。 */
    private static final int EXTRA_ROW = -3;
    /** 「通用工廠」類供應器前綴（子機器名接在分隔符後，顯示時拆兩行）。 */
    private static final String[] FACTORY_PREFIXES = {"通用工廠", "通用工厂"};
    /** 通用工廠前綴與子機器名之間可能的分隔符。 */
    private static final String FACTORY_SEPARATORS = " -－—:：·・";

    private final PatternEncodingTermScreen<?> screen;
    private final java.util.List<ListBoxReflector.Dest> destinations;
    /** 中鍵強制開面板：面板一律視為非合成模式（見 {@link #craftMode()}），讓玩家改機器／排序／逐列上傳。 */
    private final boolean forced;
    private final Font font;
    private final EditBox searchBox;
    private int x;
    private int y;
    private int w;
    private int maxRows;
    /** 面板整體縮放（Ctrl+滾輪調整，0.5–2.0；含字體）。內部座標一律「邏輯座標」，畫面經 pose scale 呈現。 */
    private float uiScale = 1.0f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;
    private boolean dragging = false;
    private boolean resizing = false;
    private int dragOffX;
    private int dragOffY;

    private Mode mode = Mode.DESTINATIONS;
    /** MACHINE_SELECT 模式的目標供應器顯示名稱（標題與名稱鍵退路用）。 */
    private String selectingName = "";
    /** MACHINE_SELECT 目標供應器的座標鍵（有座標時 config 以此為鍵 → 同名獨立）；無座標為 null。 */
    @org.jetbrains.annotations.Nullable
    private String selectingPosKey = null;
    /** 目標名稱在本次清單中出現多次且無座標可分辨（此時同名供應器才會共用指定）。 */
    private boolean selectingDup = false;
    private int scrollOff = 0;
    private final List<Row> rows = new ArrayList<>();
    /** 中鍵多選高亮的目的地（以 dest.index() 為鍵，本次 overlay 期間 index 穩定）；再中鍵已選列 = 批次上傳。 */
    private final Set<Integer> selected = new HashSet<>();

    private record Row(ItemStack icon, AEKey key, Component name, boolean full, int destIndex, GTRecipeType type,
                       String providerName, @org.jetbrains.annotations.Nullable String posKey, boolean suggested,
                       boolean hasRecipe) {

        /** 不可上傳（視同滿槽的行為門檻）：真滿槽，或已有本次編碼的樣板（上傳會被 GTO 忽略）。 */
        boolean blocked() {
            return full || hasRecipe;
        }
    }

    UploadOverlay(PatternEncodingTermScreen<?> screen, java.util.List<ListBoxReflector.Dest> destinations, boolean forced) {
        this.screen = screen;
        this.destinations = destinations;
        this.forced = forced;
        this.font = Minecraft.getInstance().font;
        this.w = clamp(orDefault(PatternUploadConfig.panelW(), DEFAULT_W), MIN_W, MAX_W);
        this.maxRows = clamp(orDefault(PatternUploadConfig.panelRows(), DEFAULT_ROWS), MIN_ROWS, MAX_ROWS_LIMIT);
        Float ps = PatternUploadConfig.panelScale();
        this.uiScale = ps == null ? 1.0f : Math.max(MIN_SCALE, Math.min(MAX_SCALE, ps));
        Integer px = PatternUploadConfig.panelX();
        Integer py = PatternUploadConfig.panelY();
        if (px != null && py != null) {
            this.x = Math.max(0, Math.min(px, screen.width - w));
            this.y = Math.max(0, Math.min(py, screen.height - 40));
        } else {
            defaultPosition();
        }
        // 搜尋欄併入標題列：icon（左，拖曳把手）＋搜尋欄（中）＋關閉鈕（右）；機器名以 hint 呈現。
        // 寬度用內容邏輯寬（cw）：內容縮放時搜尋欄跟著框內邏輯版面走。
        this.searchBox = new EditBox(this.font, x + 21, y + 3, cw() - 34, 12, Component.empty());
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(false);
        this.searchBox.setHint(Component.translatable("pattern_upload.search"));
        this.searchBox.setResponder(s -> rebuildRows());
        rebuildRows();
    }

    /** 預設位置：合成欄（3x3 編碼格）右邊；取不到 slot 時退回終端 GUI 右側。 */
    private void defaultPosition() {
        int gx = -1;
        int gy = -1;
        try {
            if (screen.getMenu() instanceof PatternEncodingTermMenu menu) {
                int right = 0;
                int top = Integer.MAX_VALUE;
                for (Slot s : menu.getCraftingGridSlots()) {
                    right = Math.max(right, s.x + 18);
                    top = Math.min(top, s.y);
                }
                if (right > 0 && top != Integer.MAX_VALUE) {
                    gx = screen.getGuiLeft() + right + 4;
                    gy = screen.getGuiTop() + top - 4;
                }
            }
        } catch (Throwable ignored) {
            // AE2 內部變動時退回 GUI 右側
        }
        if (gx < 0) {
            gx = screen.getGuiLeft() + screen.getXSize() + 4;
            gy = screen.getGuiTop() + 4;
        }
        this.x = Math.max(0, Math.min(gx, screen.width - w));
        this.y = Math.max(2, Math.min(gy, screen.height - heightFor(maxRows) - 2));
    }

    private static int orDefault(Integer v, int def) {
        return v != null ? v : def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    // ------------------------------------------------------------- 縮放座標
    // 外框（x/y/w/panelHeight）固定為螢幕座標；縮放只作用框內內容——
    // 內容邏輯寬高 = 實寬高 ÷ uiScale（縮小＝同框塞更多列與更多字）。

    /** 內容邏輯寬（實寬 ÷ 縮放）。 */
    private int cw() {
        return Math.max(40, Math.round(w / uiScale));
    }

    /** 內容邏輯高（實高 ÷ 縮放）。 */
    private int contentH() {
        return Math.round(panelHeight() / uiScale);
    }

    /** 內容區可容納的列數（邏輯高扣掉標題列與底部狀態列）。 */
    private int contentRows() {
        return Math.max(1, (contentH() - HEADER_H - 2 - FOOTER_H) / ROW_H);
    }

    /** 螢幕滑鼠 X → 內容邏輯座標（以面板左上角 (x,y) 為縮放錨點）。 */
    private double lx(double mx) {
        return x + (mx - x) / uiScale;
    }

    /** 螢幕滑鼠 Y → 內容邏輯座標。 */
    private double ly(double my) {
        return y + (my - y) / uiScale;
    }

    PatternEncodingTermScreen<?> screen() {
        return screen;
    }

    /** 伺服端座標回來後（PatternUploadClient 呼叫）：以新座標鍵重排、刷新指定顯示。 */
    void onPositionsUpdated() {
        rebuildRows();
    }

    // ------------------------------------------------------------------ data

    private void rebuildRows() {
        rows.clear();
        String filter = searchBox.getValue();
        if (mode == Mode.DESTINATIONS) {
            // 本地重排（穩定排序，同層維持伺服端順序）。伺服端的類型排序靠 menu 暫存的
            // gto$lastRecipeType，重新編碼舊樣板時是 null → 只剩空位排序；這裡用
            // @GuiSync 的 gtocore$recipe 補回正確的類型優先。
            // 合成類樣板（craftMode）：類型與指定皆不適用，完全沿用伺服端順序
            //（gto$craftFirst 已把分子裝配室/裝配矩陣排前）。
            boolean craft = craftMode();
            GTRecipeType current = craft ? null : PatternUploadClient.currentRecipeType(screen.getMenu());
            // 置頂資訊列：已有該配方但被 GTO 從清單移除的供應器（伺服端整網枚舉補回）。
            // 純展示（blocked）：讓玩家一眼看到「這張樣板已經在哪」，避免重複鋪到別台。
            var exList = PatternUploadClient.extraDests();
            for (int ei = 0; ei < exList.size(); ei++) {
                var ex = exList.get(ei);
                GTRecipeType sugType = PatternUploadClient.pickSuggestion(
                        PatternUploadClient.parseSuggestions(ex.suggest()), current);
                // 實際機器優先（化工廠不顯示成同類型的大型化學反應釜）；未回報退類型代表機器
                ItemStack actual = PatternUploadClient.machineItemStack(PatternUploadClient.extraMachineFor(ei));
                String label = ex.name();
                Component display = actual != null ? Component.literal(actual.getHoverName().getString())
                        : sugType != null ? Component.literal(RecipeTypeIcons.name(sugType).getString())
                        : Component.literal(label);
                boolean machineKnown = actual != null || sugType != null;
                String filterText = machineKnown ? display.getString() + " (" + label + ")" : label;
                if (!PinyinMatch.matches(filterText, filter)) {
                    continue;
                }
                ItemStack icon = actual != null ? actual
                        : sugType != null ? RecipeTypeIcons.icon(sugType) : iconFromId(ex.iconId());
                rows.add(new Row(icon, null, display, false, EXTRA_ROW, sugType,
                        machineKnown ? label : "", null, machineKnown, true));
            }
            // 單趟預算：每 dest 只算一次 posKey/manual/suggestion/effective/tier/free，
            // 避免 comparator（sortTier）每次比較與 isSuggested 各自重呼 machineFor/suggestionFor。
            record Pre(ListBoxReflector.Dest dest, String posKey, GTRecipeType effective, boolean suggested,
                       int tier, int free, String groupKey, int voltRank, int machineTier, int catRank) {

                /** 群組錨定鍵：同層＋同分類吻合度＋同機器的列聚在該群首見位置。 */
                String groupOf() {
                    return tier + "|" + catRank + "|" + groupKey;
                }
            }
            // 配方電壓 tier（GTRecipeDefinition.tier；-1 未知）：機器電壓對照排序／過濾用
            int recipeTier = current == null ? -1 : PatternUploadClient.currentRecipeTier(screen.getMenu());
            // 配方分類（同類型底下再分，如 assembler 底下的 mana_assembler）：只影響排序，不影響匹配／直傳。
            // 該類型無專屬分類（絕大多數機器）時 specialPaths 為空 → catRank 恆 1 → 排序與 1.27.0 完全相同。
            String recipeCat = current == null ? "" : PatternUploadClient.currentRecipeCategory(screen.getMenu());
            java.util.Set<String> specialCats = RecipeTypeIcons.specialCategoryPaths(current);
            List<Pre> pre = new ArrayList<>(destinations.size());
            for (var dest : destinations) {
                String posKey = PatternUploadClient.posKeyFor(dest.index());
                GTRecipeType manual = craft ? null : PatternUploadConfig.machineFor(posKey, dest.name().getString());
                GTRecipeType sug = craft ? null : PatternUploadClient.usableSuggestionFor(dest, current);
                GTRecipeType effective = manual != null ? manual : sug; // 有效機器＝手動指定優先，無則建議
                boolean suggested = manual == null && sug != null;       // 有效機器來自建議（非手動）→ 青色標示
                int tier = current == null ? 0 : sortTier(dest, current, effective);
                // 相同機器列的次序鍵：剩餘空格小→大（-1 未知排最後）；群組鍵＝有效機器，判不出者退標籤
                int fr = PatternUploadClient.freeSlotsFor(dest.index());
                String groupKey = effective != null ? effective.registryName.toString() : dest.name().getString();
                // 配方電壓對應機器電壓：0 跑得動（機器 tier ≥ 配方 tier）→ 1 任一方未知 → 2 電壓不足（跑不動）
                int mt = PatternUploadClient.tierIndexOf(PatternUploadClient.tierFor(dest.index()));
                int voltRank = (recipeTier < 0 || mt < 0) ? 1 : (mt >= recipeTier ? 0 : 2);
                // 分類吻合度：機器 id 取 伺服端回報的實際機器 ?? 清單 icon 物品（貼著的機器）
                String machineId = PatternUploadClient.machineItemFor(dest.index());
                if (machineId.isEmpty()) {
                    machineId = itemIdOf(dest.icon());
                }
                int catRank = RecipeTypeIcons.categoryRank(machineId, recipeCat, specialCats);
                pre.add(new Pre(dest, posKey, effective, suggested, tier, fr < 0 ? Integer.MAX_VALUE : fr,
                        groupKey, voltRank, mt < 0 ? Integer.MAX_VALUE : mt, catRank));
            }
            if (current != null) {
                // 穩定排序，同層維持伺服端順序；同層內先分「配方分類吻合度」——魔力組裝樣板讓魔力組裝機
                // 在前、一般組裝機在後（反之亦然）。無專屬分類的類型 catRank 恆 1 → 這一鍵不產生任何差異。
                pre.sort(Comparator.comparingInt(Pre::tier).thenComparingInt(Pre::catRank));
                // 相同機器再依 電壓適配（跑得動→未知→不足）→ 機器電壓低→高（最貼近配方電壓者先，不佔高壓機）
                // → 剩餘空格小→大（優先塞快滿的、樣板集中）。群組錨定在「該群同層＋同分類吻合度首見位置」——
                // 同機器列聚在一起，跨群與跨層仍維持上面排序後的相對順序（不亂跳）。
                java.util.Map<String, Integer> groupFirst = new java.util.HashMap<>();
                for (int i = 0; i < pre.size(); i++) {
                    groupFirst.putIfAbsent(pre.get(i).groupOf(), i);
                }
                pre.sort(Comparator
                        .comparingInt((Pre p) -> groupFirst.get(p.groupOf()))
                        .thenComparingInt(Pre::voltRank)
                        .thenComparingInt(Pre::machineTier)
                        .thenComparingInt(Pre::free));
            }
            for (var p : pre) {
                var dest = p.dest();
                String providerName = dest.name().getString();
                GTRecipeType assigned = p.effective();
                Component display = dest.name();
                // 搜尋鍵一律含伺服端回的電壓等級（"LV" 等）——GTO 標籤有無帶電壓都搜得到（1.20.0）
                String tier = PatternUploadClient.tierFor(dest.index());
                String filterText = providerName + (tier.isEmpty() ? "" : " " + tier);
                // 建議路徑的實際機器（伺服端回報）：同類型異機種（化工廠 vs 大型化學反應釜）不再顯示成
                // 類型代表機器；未回報（舊伺服端／混綁不唯一）退類型代表機器。手動指定者尊重玩家選的類型顯示。
                ItemStack actual = p.suggested()
                        ? PatternUploadClient.machineItemStack(PatternUploadClient.machineItemFor(dest.index()))
                        : null;
                // 改名救援（1.29.0）：樣板總成用內建改名後，GTO 標籤變成**純自訂名**（`forPatternBuffer`
                // 的 customName 分支），但 icon 仍是機器物品 → 機器判得出、只是名字沒顯示。這裡補出
                // 顯示用機器，讓它比照子網建議路徑顯示「機器名 (自訂名)」兩行。排序與匹配完全不受影響
                //（只動 Row 顯示，Pre.effective／tier 不變）。
                if (assigned == null && actual == null) {
                    actual = renamedIconMachine(dest, providerName);
                }
                boolean machineShown = assigned != null || actual != null;
                if (machineShown) {
                    // 已判定機器：icon 換成該機器；第一行放「機器名＋電壓」，原標籤（改名後的自訂名）換行放括號裡（見 render）。
                    String machineName = (actual != null
                            ? actual.getHoverName().getString()
                            : RecipeTypeIcons.name(assigned).getString())
                            + (tier.isEmpty() ? "" : " " + tier);
                    display = Component.literal(machineName);
                    filterText = machineName + " (" + providerName + ")";
                }
                if (!PinyinMatch.matches(filterText, filter)) {
                    continue;
                }
                boolean hasRecipe = PatternUploadClient.hasRecipeFor(dest.index());
                if (machineShown) {
                    // 機器名顏色：手動指定＝白；伺服端建議與改名救援＝青（自動判定，可手動覆寫）
                    rows.add(new Row(actual != null ? actual : RecipeTypeIcons.icon(assigned), null, display,
                            dest.full(), dest.index(), assigned, providerName, p.posKey(),
                            p.suggested() || assigned == null, hasRecipe));
                } else {
                    rows.add(new Row(null, dest.icon(), display, dest.full(), dest.index(), null, providerName,
                            p.posKey(), false, hasRecipe));
                }
            }
        } else {
            if (filter.isEmpty() && PatternUploadConfig.machineFor(selectingPosKey, selectingName) != null) {
                rows.add(new Row(RecipeTypeIcons.patternIcon(), null,
                        Component.translatable("pattern_upload.assign.clear"), false, CLEAR_ROW, null, "", null, false,
                        false));
            }
            for (GTRecipeType type : RecipeTypeIcons.allTypes()) {
                Component name = RecipeTypeIcons.name(type);
                if (!PinyinMatch.matches(name.getString(), filter)) {
                    continue;
                }
                rows.add(new Row(RecipeTypeIcons.icon(type), null, name, false, -1, type, "", null, false, false));
            }
        }
        scrollOff = Math.max(0, Math.min(scrollOff, rows.size() - contentRows()));
    }

    /**
     * 被**內建改名**的目的地（樣板總成等）的顯示用機器物品；未改名／判不出回 null。
     * <p>
     * GTO 的 `PatternContainerGroupHelper.forPatternBuffer` 在 customName 非空且不以 `+` 開頭時，
     * 直接回「icon＝控制器機器物品、名稱＝**純自訂名**」——機器判定其實還在（icon 反查得到），
     * 只是標籤裡再也沒有機器名，面板遂只顯示自訂名一行。判準即「icon 反查得到機器**且**標籤不含該機器名」：
     * 未改名時 GTO 標籤必含機器名（格式 `%m %t %s %r` 的 `%m`），改名後只剩自訂名。
     * <p>
     * AE2 供應器改名是另一回事（icon 會變成供應器自身物品、機器資訊全失，靠伺服端建議救援，見 1.13.1），
     * 那條路 icon 反查必為 null → 不會走到這裡，兩者互不干擾。
     */
    @org.jetbrains.annotations.Nullable
    private static ItemStack renamedIconMachine(ListBoxReflector.Dest dest, String providerName) {
        try {
            if (RecipeTypeIcons.typesForIcon(dest.icon()) == null) {
                return null; // icon 不是（有配方的）機器物品 → 非本情境
            }
            if (!(dest.icon() instanceof appeng.api.stacks.AEItemKey ik)) {
                return null;
            }
            ItemStack stack = new ItemStack(ik.getItem());
            String machineName = stack.getHoverName().getString();
            if (machineName.isEmpty() || stripFmt(providerName).contains(stripFmt(machineName))) {
                return null; // 標籤已含機器名＝未改名（GTO 原標籤）→ 顯示照舊
            }
            return stack;
        } catch (Throwable ignored) {
            return null; // 任何異常 → 維持原顯示
        }
    }

    /** 去除 § 格式碼後的素字串（GTO 標籤的電壓帶顏色碼，與素字串比對用）。 */
    private static String stripFmt(String s) {
        String r = net.minecraft.ChatFormatting.stripFormatting(s);
        return r == null ? "" : r;
    }

    /** 目的地 icon（AEKey＝供應器貼著的機器物品）的 registry id；非物品／取不到回 ""。 */
    private static String itemIdOf(@org.jetbrains.annotations.Nullable AEKey key) {
        try {
            if (key instanceof appeng.api.stacks.AEItemKey ik) {
                var rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ik.getItem());
                return rl == null ? "" : rl.toString();
            }
        } catch (Throwable ignored) {
            // 取不到 → 視為未知（分類排序不生效）
        }
        return "";
    }

    /** 額外資訊列 icon：群組 icon 物品 id → ItemStack；解析不到退樣板 icon。 */
    private static ItemStack iconFromId(String id) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl != null) {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item != net.minecraft.world.item.Items.AIR) {
                    return new ItemStack(item);
                }
            }
        } catch (Throwable ignored) {
            // 退樣板 icon
        }
        return RecipeTypeIcons.patternIcon();
    }

    /**
     * 目的地排序層級（越小越前），0/1 視為「明確匹配」可自動上傳：
     * -1 已有該配方（**置頂**展示——上傳會被 GTO 忽略，行為視同滿槽、也不算進自動直傳明確匹配）；
     * 0 手動指定且吻合；1 icon 反查機器 或 名稱最長機器名 支援本類型（多類型機器有伺服端「已決定類型」
     * 時以它為準）；3 無法判定；4 手動指定但不吻合／多類型機器已決定其他模式；5 滿槽。
     */
    static int sortTier(ListBoxReflector.Dest d, GTRecipeType current) {
        // 有效機器＝手動指定優先，無則「可採用」的伺服端建議（多類型優先挑吻合 current 者）；
        // 委派給帶預算值的多載（外部呼叫者用此便捷版）
        return sortTier(d, current, PatternUploadClient.effectiveMachineFor(d, current));
    }

    /** 同 {@link #sortTier(ListBoxReflector.Dest, GTRecipeType)}，但吃已算好的有效機器（rebuildRows 單趟預算用，免重算）。 */
    static int sortTier(ListBoxReflector.Dest d, GTRecipeType current, @org.jetbrains.annotations.Nullable GTRecipeType effective) {
        if (PatternUploadClient.hasRecipeFor(d.index())) {
            return -1; // 已有該配方：置頂（與被 GTO 藏掉的額外列同區）
        }
        if (d.full()) {
            return 5;
        }
        if (effective != null) {
            return RecipeTypeIcons.matchesType(effective, current) ? 0 : 4;
        }
        var iconTypes = RecipeTypeIcons.typesForIcon(d.icon());
        if (iconTypes != null) {
            // 多類型機器（大型切割機＝切割＋車床）：icon 全類型集會把「另一模式」的機器也判吻合 →
            // 車床樣板連切割模式那台都算 match、永遠開面板。伺服端 1.19.1 起回報「已決定類型」
            //（當下設定的模式）——有回報就以它判定：吻合 → 1；已決定其他模式 → 4（該台跑不了這張樣板，
            // 不算明確匹配、也不誤置頂）。沒回報（舊伺服端／逾時）→ 退回 icon 全集判定（舊行為）。
            if (iconTypes.size() > 1) {
                var decided = PatternUploadClient.suggestionsFor(d.index());
                if (!decided.isEmpty()) {
                    for (GTRecipeType t : decided) {
                        if (RecipeTypeIcons.matchesType(t, current)) {
                            return 1;
                        }
                    }
                    return 4;
                }
            }
            for (GTRecipeType t : iconTypes) {
                if (RecipeTypeIcons.matchesType(t, current)) {
                    return 1;
                }
            }
        }
        // 通用工廠等 icon 不帶子機器者：靠供應器名稱裡最長機器名判定（最長匹配避免子字串誤中）
        if (RecipeTypeIcons.nameMachineSupports(d.name().getString(), current)) {
            return 1;
        }
        return 3;
    }

    /**
     * 「通用工廠 - 子機器」拆成 {"通用工廠", "子機器"}；非此格式（無前綴或前綴後非分隔符）回 null。
     * 用來讓通用工廠類供應器名稱換行顯示（前綴一行、子機器名一行）。
     */
    private static String[] splitFactoryName(String s) {
        for (String pre : FACTORY_PREFIXES) {
            if (s.length() > pre.length() && s.startsWith(pre)
                    && FACTORY_SEPARATORS.indexOf(s.charAt(pre.length())) >= 0) {
                int i = pre.length();
                while (i < s.length() && FACTORY_SEPARATORS.indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                if (i < s.length()) {
                    return new String[] {pre, s.substring(i)};
                }
            }
        }
        return null;
    }

    private int visibleRows() {
        return Math.min(rows.size(), contentRows());
    }

    private int rowsTop() {
        return y + HEADER_H + 2;
    }

    private static int heightFor(int rowCount) {
        return HEADER_H + 2 + rowCount * ROW_H + FOOTER_H;
    }

    private int panelHeight() {
        // 高度固定跟著 maxRows（縮放把手調的值），列不足時留空白，長寬才都真正可調
        return heightFor(maxRows);
    }

    /**
     * 合成類樣板判定。中鍵**強制開面板**（{@code forced}）時一律視為非合成 → 面板改用一般目的地流程：
     * 顯示機器類型、點列 icon 可指定／改機器、逐列上傳。中鍵手勢的用意本就是跳過 gto$craftFirst 自動流程、讓玩家自控。
     */
    private boolean craftMode() {
        return !forced && PatternUploadClient.isCraftMode(screen.getMenu());
    }

    private ItemStack headerIcon() {
        if (mode == Mode.MACHINE_SELECT) {
            return RecipeTypeIcons.patternIcon();
        }
        if (craftMode()) {
            return new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE);
        }
        GTRecipeType type = PatternUploadClient.currentRecipeType(screen.getMenu());
        return type != null ? RecipeTypeIcons.icon(type) : RecipeTypeIcons.patternIcon();
    }

    private Component headerTitle() {
        if (mode == Mode.MACHINE_SELECT) {
            return Component.literal(selectingName);
        }
        if (craftMode()) {
            return Component.translatable("pattern_upload.craft.title");
        }
        GTRecipeType type = PatternUploadClient.currentRecipeType(screen.getMenu());
        return type != null ? RecipeTypeIcons.name(type) : Component.translatable("pattern_upload.no_machine");
    }

    // ---------------------------------------------------------------- render

    void render(GuiGraphics g, int rawMouseX, int rawMouseY, float partialTick) {
        int h = panelHeight();

        // 外框固定不隨內容縮放：底色、邊框、右下角縮放把手都畫在實際框上（hover 用螢幕座標判）
        g.fill(x, y, x + w, y + h, 0xF0141414);
        g.renderOutline(x, y, w, h, 0xFF8B8B8B);
        int gripColor = isOverResizeGrip(rawMouseX, rawMouseY) || resizing ? 0xFFFFFFFF : 0xFF9B9B9B;
        g.fill(x + w - 8, y + h - 2, x + w - 1, y + h - 1, gripColor);
        g.fill(x + w - 2, y + h - 8, x + w - 1, y + h - 1, gripColor);

        // 內容縮放（Ctrl+滾輪）：框不動、框內以 (x,y) 為錨縮放；內容邏輯寬高＝實寬高 ÷ 縮放
        //（縮小＝同框塞更多列與更多字）。滑鼠轉邏輯座標，hover/tooltip 沿用（tooltip 經縮放 pose 映回原位）。
        int mouseX = (int) lx(rawMouseX);
        int mouseY = (int) ly(rawMouseY);
        int cw = cw();
        int ch = contentH();
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(uiScale, uiScale, 1);
        pose.translate(-x, -y, 0);

        // header：樣板機器 icon（左，拖曳把手）+ 搜尋欄／機器名（中）+ 關閉鈕（右）
        g.renderItem(headerIcon(), x + 2, y + 1);
        g.fill(x + 20, y + 2, x + cw - 12, y + HEADER_H - 2, 0x40000000); // 搜尋欄底色
        if (searchBox.isFocused() || !searchBox.getValue().isEmpty()) {
            // 聚焦或有輸入 → 顯示可編輯搜尋欄
            searchBox.render(g, mouseX, mouseY, partialTick);
        } else {
            // 未搜尋 → 該列當標題，亮白顯示機器名／模式標題（點一下即變搜尋欄）
            String t = font.plainSubstrByWidth(headerTitle().getString(), cw - 21 - 13);
            g.drawString(font, t, x + 21, y + 5, 0xFFFFFF);
        }
        boolean closeHover = isOverClose(mouseX, mouseY);
        g.drawString(font, "✕", x + cw - 10, y + 5, closeHover ? 0xFF5555 : 0xAAAAAA);

        // rows
        int top = rowsTop();
        int vis = visibleRows();
        for (int i = 0; i < vis; i++) {
            int idx = scrollOff + i;
            if (idx >= rows.size()) {
                break;
            }
            Row row = rows.get(idx);
            int ry = top + i * ROW_H;
            boolean hover = mouseX >= x + 2 && mouseX < x + cw - 2 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean iconHover = mode == Mode.DESTINATIONS && !craftMode() && row.destIndex() != EXTRA_ROW
                    && isOverRowIcon(mouseX, mouseY, ry);
            if (hover && !(mode == Mode.DESTINATIONS && row.blocked() && !iconHover)) {
                g.fill(x + 2, ry, x + cw - 2, ry + ROW_H, 0x40FFFFFF);
            }
            if (iconHover) {
                g.fill(x + 2, ry, x + 20, ry + ROW_H, 0x60FFFFFF);
            }
            if (mode == Mode.DESTINATIONS && selected.contains(row.destIndex())) {
                // 中鍵多選高亮
                g.fill(x + 2, ry, x + cw - 2, ry + ROW_H, 0x5044DD44);
                g.renderOutline(x + 2, ry, cw - 4, ROW_H, 0xFF55EE55);
            }
            if (row.key() != null) {
                AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x + 3, ry, row.key());
            } else {
                g.renderItem(row.icon(), x + 3, ry);
            }
            int color = (mode == Mode.DESTINATIONS && row.blocked()) ? 0x777777 : 0xE0E0E0;
            String fullTag = (mode == Mode.DESTINATIONS && row.full())
                    ? " [" + Component.translatable("pattern_upload.full").getString() + "]" : "";
            // 右緣註記（右緣置中，名稱寬度讓位避免重疊）：已有該配方（橙）優先，否則樣板槽剩餘空格
            //（伺服端隨座標封包回報；-1＝未知不畫）
            int freeW = 0;
            if (mode == Mode.DESTINATIONS) {
                String tail = null;
                int tailColor = 0;
                if (row.hasRecipe()) {
                    tail = Component.translatable("pattern_upload.has_recipe").getString();
                    tailColor = 0xCC9944;
                } else {
                    int freeN = PatternUploadClient.freeSlotsFor(row.destIndex());
                    if (freeN >= 0) {
                        tail = Component.translatable("pattern_upload.free", freeN).getString();
                        tailColor = row.full() ? 0x996666 : 0x77BB77;
                    }
                }
                if (tail != null) {
                    int fw = font.width(tail);
                    g.drawString(font, tail, x + cw - 5 - fw, ry + 5, tailColor);
                    freeW = fw + 4;
                }
            }
            int nameW = cw - 25 - freeW;
            // 已顯示機器的列＝有配方類型（type）或 icon 已換成機器物品（改名救援：type 可為 null）；
            // 未判定列 icon 為 null（畫 AEKey 原 icon）。
            if (mode == Mode.DESTINATIONS && (row.type() != null || row.icon() != null)
                    && !row.providerName().isEmpty()) {
                // 已判定機器：第一行機器名，第二行括號放（改名後的）原標籤；「通用工廠 - 子機器」只留子機器。
                // 機器名顏色：手動指定＝白；伺服端建議（接口→存儲總線自動解析）＝青色標示，可分辨並提醒可手動覆寫。
                String[] pf = splitFactoryName(row.providerName());
                String label = pf != null ? pf[1] : row.providerName();
                int nameColor = row.blocked() ? 0x777777 : (row.suggested() ? 0x66CCFF : color);
                g.drawString(font, font.plainSubstrByWidth(row.name().getString() + fullTag, nameW), x + 21, ry + 1, nameColor);
                g.drawString(font, font.plainSubstrByWidth("(" + label + ")", nameW), x + 21, ry + 9, 0x999999);
            } else {
                // 未指定：「通用工廠 - 子機器」拆兩行（通用工廠 / 子機器），其餘單行置中。
                String[] pf = mode == Mode.DESTINATIONS ? splitFactoryName(row.name().getString()) : null;
                if (pf != null) {
                    g.drawString(font, font.plainSubstrByWidth(pf[0], nameW), x + 21, ry + 1, color);
                    g.drawString(font, font.plainSubstrByWidth(pf[1] + fullTag, nameW), x + 21, ry + 9, 0x999999);
                } else {
                    g.drawString(font, font.plainSubstrByWidth(row.name().getString() + fullTag, nameW), x + 21, ry + 5, color);
                }
            }
        }
        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("pattern_upload.empty").getString(), x + 6, top + 4, 0x888888);
        }
        int bottomY = y + ch - 11;
        int hintRight = x + cw - 5; // 底列提示可用的右界（有捲動指示時往左讓位，避免文字重疊）
        if (rows.size() > contentRows()) {
            String pos = (scrollOff + 1) + "-" + Math.min(scrollOff + contentRows(), rows.size()) + "/" + rows.size();
            int posX = x + cw - 12 - font.width(pos);
            g.drawString(font, pos, posX, bottomY, 0x888888);
            hintRight = posX - 4;
        }
        if (mode == Mode.DESTINATIONS && !selected.isEmpty()) {
            // 右鍵多選提示：已選數量＋左鍵批次上傳（寬度截到捲動指示前，兩者不重疊）
            String hint = font.plainSubstrByWidth(
                    Component.translatable("pattern_upload.batch.hint", selected.size()).getString(),
                    Math.max(20, hintRight - (x + 5)));
            g.drawString(font, hint, x + 5, bottomY, 0x55EE55);
        }
        if (mode == Mode.MACHINE_SELECT && selectingDup) {
            // 同名供應器共用指定（客戶端只拿得到名稱，分不出實體）——提醒玩家先改名
            String warn = font.plainSubstrByWidth(
                    Component.translatable("pattern_upload.dup.warn").getString(), cw - 10);
            g.drawString(font, warn, x + 5, y + ch - 11, 0xFFCC44);
        }

        // tooltips
        if (mode == Mode.DESTINATIONS) {
            int idx = rowIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                int ry = top + (idx - scrollOff) * ROW_H;
                if (!craftMode() && rows.get(idx).destIndex() != EXTRA_ROW && isOverRowIcon(mouseX, mouseY, ry)) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.assign.tooltip"), mouseX, mouseY);
                } else if (rows.get(idx).hasRecipe()) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.has_recipe.tooltip"), mouseX, mouseY);
                } else if (rows.get(idx).full()) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.full.tooltip"), mouseX, mouseY);
                }
            }
        }
        pose.popPose();
    }

    // ----------------------------------------------------------------- input

    private boolean isInside(double mx, double my) {
        // 邏輯座標；內容邏輯寬高與外框等價（同一矩形除以縮放），框內判定一致
        return mx >= x && mx < x + cw() && my >= y && my < y + contentH();
    }

    private boolean isOverClose(double mx, double my) {
        return mx >= x + cw() - 13 && mx < x + cw() - 1 && my >= y + 2 && my < y + 15;
    }

    /** 右下角 12x12 = 縮放把手。 */
    private boolean isOverResizeGrip(double mx, double my) {
        int h = panelHeight();
        return mx >= x + w - 12 && mx < x + w && my >= y + h - 12 && my < y + h;
    }

    /** 標題列最左 icon 區 = 拖曳把手（中間是搜尋欄、右邊是關閉鈕，都不可拖）。 */
    private boolean isOverDragHandle(double mx, double my) {
        return mx >= x && mx < x + 20 && my >= y && my < y + HEADER_H;
    }

    /** 目的地列最左 icon 區（點擊 = 指定該供應器的機器）。 */
    private boolean isOverRowIcon(double mx, double my, int rowY) {
        return mx >= x + 2 && mx < x + 20 && my >= rowY && my < rowY + ROW_H;
    }

    boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (resizing) {
            // 以左上角為錨點，拖右下角改「外框」寬與列數（螢幕座標——外框不受內容縮放影響）
            w = clamp((int) mx - x, MIN_W, MAX_W);
            int targetH = (int) my - y;
            maxRows = clamp(Math.round((targetH - HEADER_H - 2 - FOOTER_H) / (float) ROW_H), MIN_ROWS, MAX_ROWS_LIMIT);
            searchBox.setWidth(cw() - 34);
            rebuildRows();
            return true;
        }
        if (!dragging) {
            return false;
        }
        // 拖曳移動用螢幕座標（面板錨點 (x,y) 本來就是螢幕座標，1:1 跟手）
        x = Math.max(0, Math.min((int) mx - dragOffX, screen.width - w));
        y = Math.max(0, Math.min((int) my - dragOffY, screen.height - 40));
        searchBox.setX(x + 21);
        searchBox.setY(y + 3);
        return true;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (dragging || resizing) {
            dragging = false;
            resizing = false;
            PatternUploadConfig.savePanel(x, y, w, maxRows); // 拖曳/縮放結束才落盤
            return true;
        }
        return false;
    }

    private int rowIndexAt(double mx, double my) {
        if (mx < x + 2 || mx >= x + cw() - 2) {
            return -1;
        }
        int top = rowsTop();
        if (my < top || my >= top + visibleRows() * ROW_H) {
            return -1;
        }
        int idx = scrollOff + (int) ((my - top) / ROW_H);
        return idx < rows.size() ? idx : -1;
    }

    boolean mouseClicked(double mx, double my, int button) {
        double rawMx = mx;
        double rawMy = my;
        mx = lx(mx);
        my = ly(my); // 之後全用邏輯座標；拖曳位移例外（下方 dragOff 用螢幕座標）
        if (!isInside(mx, my)) {
            searchBox.setFocused(false);
            return false;
        }
        // 搜尋欄
        if (searchBox.mouseClicked(mx, my, button)) {
            searchBox.setFocused(true);
            return true;
        }
        searchBox.setFocused(false);

        // 右鍵：多選高亮切換（滿槽不選）。左鍵上傳時：有多選 → 批次上傳全部已選，無多選 → 傳點擊列。
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && mode == Mode.DESTINATIONS && !craftMode()) {
            int mi = rowIndexAt(mx, my);
            if (mi >= 0) {
                Row row = rows.get(mi);
                if (!row.blocked() && !selected.remove(row.destIndex())) {
                    selected.add(row.destIndex());
                }
            }
            return true; // 面板內右鍵一律吃掉，避免誤觸終端
        }

        if (isOverResizeGrip(rawMx, rawMy)) { // 縮放把手在外框角落（螢幕座標，不受內容縮放影響）
            resizing = true;
            return true;
        }
        if (isOverClose(mx, my)) {
            if (mode == Mode.MACHINE_SELECT) {
                exitMachineSelect();
            } else {
                PatternUploadClient.removeOverlay();
            }
            return true;
        }
        if (isOverDragHandle(mx, my)) {
            dragging = true;
            dragOffX = (int) rawMx - x; // 拖曳位移以螢幕座標記（mouseDragged 同座標系）
            dragOffY = (int) rawMy - y;
            return true;
        }
        int idx = rowIndexAt(mx, my);
        if (idx >= 0 && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Row row = rows.get(idx);
            if (mode == Mode.DESTINATIONS) {
                int ry = rowsTop() + (idx - scrollOff) * ROW_H;
                if (!craftMode() && row.destIndex() != EXTRA_ROW && isOverRowIcon(mx, my, ry)) {
                    // 點 icon → 指定該供應器對應機器（滿槽也可指定）；置頂資訊列（EXTRA_ROW）不可指定
                    selectingName = row.providerName();
                    selectingPosKey = row.posKey();
                    // 有座標鍵即可分辨實體 → 不算共用；無座標時才看同名數量提醒共用
                    selectingDup = selectingPosKey == null && destinations.stream()
                            .filter(d -> d.name().getString().equals(selectingName)).count() > 1;
                    mode = Mode.MACHINE_SELECT;
                    searchBox.setValue("");
                    scrollOff = 0;
                    rebuildRows();
                } else if (!selected.isEmpty()) {
                    // 有多選 → 左鍵批次上傳全部已選（不論點在哪列）
                    batchUpload();
                } else if (!row.blocked()) {
                    var player = Minecraft.getInstance().player;
                    if (PatternUploadClient.blankPatternCount(screen.getMenu()) == 0) {
                        // 網路沒空白樣板 → 不上傳、不謊報成功；面板留著讓玩家補樣板後重試
                        if (player != null) {
                            player.displayClientMessage(Component.translatable("pattern_upload.no_blank"), false);
                        }
                        return true;
                    }
                    ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(row.destIndex());
                    if (player != null) {
                        // 與自動上傳一致：手動選擇上傳也在聊天欄回報，目標優先報機器（sentDisplayName 統一格式）。
                        var dest = destinations.stream()
                                .filter(dd -> dd.index() == row.destIndex()).findFirst().orElse(null);
                        Component sentName = dest != null
                                ? PatternUploadClient.sentDisplayName(dest, row.type())
                                : ((row.type() != null && !row.providerName().isEmpty())
                                        ? Component.literal(row.name().getString() + " (" + row.providerName() + ")")
                                        : row.name());
                        player.displayClientMessage(
                                Component.translatable("pattern_upload.sent", sentName), false);
                    }
                    PatternUploadClient.removeOverlay();
                }
            } else {
                // 指定 / 清除該供應器的機器 → 持久化（有座標則以座標為鍵）→ 回目的地清單並重排
                PatternUploadConfig.assign(selectingPosKey, selectingName, row.destIndex() == CLEAR_ROW ? null : row.type());
                exitMachineSelect();
            }
            return true;
        }
        return true; // 面板內其他區域：吃掉點擊避免誤觸終端
    }

    /**
     * 批次上傳：對所有中鍵已選的目的地各送一次樣板。
     * 伺服端 gtolib$sendPattern 每次自 ME 網路抽一張空白樣板、寫入該供應器（樣板由伺服端自扣），
     * 故這裡逐一呼叫即可。以 destinations（伺服端順序）迭代，涵蓋被搜尋過濾掉的已選列；跳過滿槽。
     */
    private void batchUpload() {
        var menu = (IExtendedPatternEncodingTerm.Menu) screen.getMenu();
        var player = Minecraft.getInstance().player;
        // 需要的張數 = 已選且非滿槽的目的地數（每張目的地各扣一張空白樣板）
        int needed = 0;
        for (var d : destinations) {
            if (!d.full() && !PatternUploadClient.hasRecipeFor(d.index()) && selected.contains(d.index())) {
                needed++;
            }
        }
        if (needed == 0) {
            return; // 已選全滿槽或無有效選取
        }
        long blanks = PatternUploadClient.blankPatternCount(screen.getMenu());
        if (blanks != -1 && blanks < needed) {
            // 空白樣板不足以涵蓋全部已選 → 完全不上傳（all-or-nothing），面板留著讓玩家補樣板後重試
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("pattern_upload.batch.short", needed, blanks), false);
            }
            return;
        }
        int count = 0;
        for (var d : destinations) {
            if (d.full() || PatternUploadClient.hasRecipeFor(d.index()) || !selected.contains(d.index())) {
                continue;
            }
            menu.gtolib$sendPattern(d.index());
            count++;
        }
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable("pattern_upload.batch.sent", count), false);
        }
        PatternUploadClient.removeOverlay();
    }

    private void exitMachineSelect() {
        mode = Mode.DESTINATIONS;
        selectingName = "";
        selectingPosKey = null;
        selectingDup = false;
        searchBox.setValue("");
        scrollOff = 0;
        rebuildRows();
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        if (!isInside(lx(mx), ly(my))) {
            return false;
        }
        // Ctrl+滾輪：調整框內內容縮放（0.5–2.0，一格 0.1；外框不動），即時生效並落盤
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            float next = uiScale + 0.1f * Math.signum((float) delta);
            uiScale = Math.round(Math.max(MIN_SCALE, Math.min(MAX_SCALE, next)) * 10f) / 10f;
            searchBox.setWidth(cw() - 34);
            scrollOff = Math.max(0, Math.min(scrollOff, rows.size() - contentRows()));
            PatternUploadConfig.saveScale(uiScale);
            return true;
        }
        scrollOff = Math.max(0, Math.min(scrollOff - (int) Math.signum(delta), rows.size() - contentRows()));
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (mode == Mode.MACHINE_SELECT) {
                exitMachineSelect(); // 第一下先退回目的地清單
            } else {
                PatternUploadClient.removeOverlay(); // 再關 overlay，第三下才關終端
            }
            return true;
        }
        if (searchBox.isFocused()) {
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true; // 搜尋欄聚焦時吞掉按鍵，避免 E 關閉背包/切換快捷欄
        }
        return false;
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (searchBox.isFocused()) {
            searchBox.charTyped(codePoint, modifiers);
            return true;
        }
        return false;
    }
}
