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
import java.util.List;
import java.util.Locale;

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

    private static final int ROW_H = 16;
    private static final int HEADER_H = 18;
    private static final int SEARCH_H = 14;
    private static final int DEFAULT_W = 150;
    private static final int DEFAULT_ROWS = 6;
    private static final int MIN_W = 120;
    private static final int MAX_W = 280;
    private static final int MIN_ROWS = 3;
    private static final int MAX_ROWS_LIMIT = 12;
    /** MACHINE_SELECT 清單裡「清除指定」列的 destIndex 哨兵值。 */
    private static final int CLEAR_ROW = -2;

    private final PatternEncodingTermScreen<?> screen;
    private final java.util.List<ListBoxReflector.Dest> destinations;
    private final Font font;
    private final EditBox searchBox;
    private int x;
    private int y;
    private int w;
    private int maxRows;
    private boolean dragging = false;
    private boolean resizing = false;
    private int dragOffX;
    private int dragOffY;

    private Mode mode = Mode.DESTINATIONS;
    /** MACHINE_SELECT 模式的目標供應器名稱（config 的鍵）。 */
    private String selectingName = "";
    /** 目標名稱在本次清單中出現多次（同名供應器會共用指定）。 */
    private boolean selectingDup = false;
    private int scrollOff = 0;
    private final List<Row> rows = new ArrayList<>();

    private record Row(ItemStack icon, AEKey key, Component name, boolean full, int destIndex, GTRecipeType type,
                       String providerName) {}

    UploadOverlay(PatternEncodingTermScreen<?> screen, java.util.List<ListBoxReflector.Dest> destinations) {
        this.screen = screen;
        this.destinations = destinations;
        this.font = Minecraft.getInstance().font;
        this.w = clamp(orDefault(PatternUploadConfig.panelW(), DEFAULT_W), MIN_W, MAX_W);
        this.maxRows = clamp(orDefault(PatternUploadConfig.panelRows(), DEFAULT_ROWS), MIN_ROWS, MAX_ROWS_LIMIT);
        Integer px = PatternUploadConfig.panelX();
        Integer py = PatternUploadConfig.panelY();
        if (px != null && py != null) {
            this.x = Math.max(0, Math.min(px, screen.width - w));
            this.y = Math.max(0, Math.min(py, screen.height - 40));
        } else {
            defaultPosition();
        }
        this.searchBox = new EditBox(this.font, x + 4, y + HEADER_H, w - 8, SEARCH_H - 2, Component.empty());
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(true);
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

    PatternEncodingTermScreen<?> screen() {
        return screen;
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
            boolean craft = PatternUploadClient.isCraftMode(screen.getMenu());
            GTRecipeType current = craft ? null : PatternUploadClient.currentRecipeType(screen.getMenu());
            List<ListBoxReflector.Dest> ordered = new ArrayList<>(destinations);
            if (current != null) {
                ordered.sort(Comparator.comparingInt(d -> sortTier(d, current)));
            }
            for (var dest : ordered) {
                String providerName = dest.name().getString();
                GTRecipeType assigned = craft ? null : PatternUploadConfig.machineFor(providerName);
                Component display = dest.name();
                if (assigned != null) {
                    // 指定後顯示「機器名（供應器貼著的方塊）」；貼著的方塊取自清單 icon（GTOCore 給的群組圖示）
                    String attached = dest.icon() != null ? dest.icon().getDisplayName().getString() : providerName;
                    display = Component.literal(RecipeTypeIcons.name(assigned).getString() + " (" + attached + ")");
                }
                if (!PinyinMatch.matches(display.getString(), filter)) {
                    continue;
                }
                if (assigned != null) {
                    rows.add(new Row(RecipeTypeIcons.icon(assigned), null, display, dest.full(), dest.index(), assigned,
                            providerName));
                } else {
                    rows.add(new Row(null, dest.icon(), display, dest.full(), dest.index(), null, providerName));
                }
            }
        } else {
            if (filter.isEmpty() && PatternUploadConfig.machineFor(selectingName) != null) {
                rows.add(new Row(RecipeTypeIcons.patternIcon(), null,
                        Component.translatable("pattern_upload.assign.clear"), false, CLEAR_ROW, null, ""));
            }
            for (GTRecipeType type : RecipeTypeIcons.allTypes()) {
                Component name = RecipeTypeIcons.name(type);
                if (!PinyinMatch.matches(name.getString(), filter)) {
                    continue;
                }
                rows.add(new Row(RecipeTypeIcons.icon(type), null, name, false, -1, type, ""));
            }
        }
        scrollOff = Math.max(0, Math.min(scrollOff, rows.size() - maxRows));
    }

    /**
     * 目的地排序層級（越小越前）：
     * 0 手動指定且吻合本樣板；1 icon 反查機器支援本類型；2 名稱含類型名（電路組裝機≠組裝機這種
     * 誤中放最後一層吻合）；3 無法判定；4 手動指定但不吻合；5 滿槽。
     */
    static int sortTier(ListBoxReflector.Dest d, GTRecipeType current) {
        if (d.full()) {
            return 5;
        }
        GTRecipeType assigned = PatternUploadConfig.machineFor(d.name().getString());
        if (assigned != null) {
            return RecipeTypeIcons.matchesType(assigned, current) ? 0 : 4;
        }
        var iconTypes = RecipeTypeIcons.typesForIcon(d.icon());
        if (iconTypes != null) {
            for (GTRecipeType t : iconTypes) {
                if (RecipeTypeIcons.matchesType(t, current)) {
                    return 1;
                }
            }
        }
        String typeName = RecipeTypeIcons.name(current).getString().toLowerCase(Locale.ROOT);
        if (!typeName.isEmpty() && d.name().getString().toLowerCase(Locale.ROOT).contains(typeName)) {
            return 2;
        }
        return 3;
    }

    private int visibleRows() {
        return Math.min(rows.size(), maxRows);
    }

    private int rowsTop() {
        return y + HEADER_H + SEARCH_H + 2;
    }

    private static int heightFor(int rowCount) {
        return HEADER_H + SEARCH_H + 4 + rowCount * ROW_H + 4;
    }

    private int panelHeight() {
        // 高度固定跟著 maxRows（縮放把手調的值），列不足時留空白，長寬才都真正可調
        return heightFor(maxRows);
    }

    private boolean craftMode() {
        return PatternUploadClient.isCraftMode(screen.getMenu());
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

    void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int h = panelHeight();

        g.fill(x, y, x + w, y + h, 0xF0141414);
        g.renderOutline(x, y, w, h, 0xFF8B8B8B);

        // 右下角縮放把手（⌟ 形記號）
        int gripColor = isOverResizeGrip(mouseX, mouseY) || resizing ? 0xFFFFFFFF : 0xFF9B9B9B;
        g.fill(x + w - 8, y + h - 2, x + w - 1, y + h - 1, gripColor);
        g.fill(x + w - 2, y + h - 8, x + w - 1, y + h - 1, gripColor);

        // header：樣板機器 icon（顯示用）+ 標題 + 關閉鈕
        g.renderItem(headerIcon(), x + 2, y + 1);
        String title = font.plainSubstrByWidth(headerTitle().getString(), w - 21 - 13);
        g.drawString(font, title, x + 21, y + 5, 0xFFFFFF);
        boolean closeHover = isOverClose(mouseX, mouseY);
        g.drawString(font, "✕", x + w - 10, y + 5, closeHover ? 0xFF5555 : 0xAAAAAA);

        searchBox.render(g, mouseX, mouseY, partialTick);

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
            boolean hover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean iconHover = mode == Mode.DESTINATIONS && !craftMode() && isOverRowIcon(mouseX, mouseY, ry);
            if (hover && !(mode == Mode.DESTINATIONS && row.full() && !iconHover)) {
                g.fill(x + 2, ry, x + w - 2, ry + ROW_H, 0x40FFFFFF);
            }
            if (iconHover) {
                g.fill(x + 2, ry, x + 20, ry + ROW_H, 0x60FFFFFF);
            }
            if (row.key() != null) {
                AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x + 3, ry, row.key());
            } else {
                g.renderItem(row.icon(), x + 3, ry);
            }
            int color = (mode == Mode.DESTINATIONS && row.full()) ? 0x777777 : 0xE0E0E0;
            String name = row.name().getString();
            if (mode == Mode.DESTINATIONS && row.full()) {
                name = name + " [" + Component.translatable("pattern_upload.full").getString() + "]";
            }
            g.drawString(font, font.plainSubstrByWidth(name, w - 25), x + 21, ry + 4, color);
        }
        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("pattern_upload.empty").getString(), x + 6, top + 4, 0x888888);
        }
        if (rows.size() > maxRows) {
            String pos = (scrollOff + 1) + "-" + Math.min(scrollOff + maxRows, rows.size()) + "/" + rows.size();
            g.drawString(font, pos, x + w - 12 - font.width(pos), y + h - 11, 0x888888);
        }
        if (mode == Mode.MACHINE_SELECT && selectingDup) {
            // 同名供應器共用指定（客戶端只拿得到名稱，分不出實體）——提醒玩家先改名
            String warn = font.plainSubstrByWidth(
                    Component.translatable("pattern_upload.dup.warn").getString(), w - 10);
            g.drawString(font, warn, x + 5, y + h - 11, 0xFFCC44);
        }

        // tooltips
        if (mode == Mode.DESTINATIONS) {
            int idx = rowIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                int ry = top + (idx - scrollOff) * ROW_H;
                if (!craftMode() && isOverRowIcon(mouseX, mouseY, ry)) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.assign.tooltip"), mouseX, mouseY);
                } else if (rows.get(idx).full()) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.full.tooltip"), mouseX, mouseY);
                }
            }
        }
    }

    // ----------------------------------------------------------------- input

    private boolean isInside(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + panelHeight();
    }

    private boolean isOverClose(double mx, double my) {
        return mx >= x + w - 13 && mx < x + w - 1 && my >= y + 2 && my < y + 15;
    }

    /** 右下角 12x12 = 縮放把手。 */
    private boolean isOverResizeGrip(double mx, double my) {
        int h = panelHeight();
        return mx >= x + w - 12 && mx < x + w && my >= y + h - 12 && my < y + h;
    }

    /** 標題列空白處（扣掉右關閉鈕）= 拖曳把手。 */
    private boolean isOverDragHandle(double mx, double my) {
        return mx >= x && mx < x + w - 14 && my >= y && my < y + HEADER_H;
    }

    /** 目的地列最左 icon 區（點擊 = 指定該供應器的機器）。 */
    private boolean isOverRowIcon(double mx, double my, int rowY) {
        return mx >= x + 2 && mx < x + 20 && my >= rowY && my < rowY + ROW_H;
    }

    boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (resizing) {
            // 以左上角為錨點，拖右下角改寬與列數
            w = clamp((int) mx - x, MIN_W, MAX_W);
            int targetH = (int) my - y;
            maxRows = clamp(Math.round((targetH - HEADER_H - SEARCH_H - 8) / (float) ROW_H), MIN_ROWS, MAX_ROWS_LIMIT);
            searchBox.setWidth(w - 8);
            rebuildRows();
            return true;
        }
        if (!dragging) {
            return false;
        }
        x = Math.max(0, Math.min((int) mx - dragOffX, screen.width - w));
        y = Math.max(0, Math.min((int) my - dragOffY, screen.height - 40));
        searchBox.setX(x + 4);
        searchBox.setY(y + HEADER_H);
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
        if (mx < x + 2 || mx >= x + w - 2) {
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

        if (isOverResizeGrip(mx, my)) {
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
            dragOffX = (int) mx - x;
            dragOffY = (int) my - y;
            return true;
        }
        int idx = rowIndexAt(mx, my);
        if (idx >= 0) {
            Row row = rows.get(idx);
            if (mode == Mode.DESTINATIONS) {
                int ry = rowsTop() + (idx - scrollOff) * ROW_H;
                if (!craftMode() && isOverRowIcon(mx, my, ry)) {
                    // 點 icon → 指定該供應器對應機器（滿槽也可指定）
                    selectingName = row.providerName();
                    selectingDup = destinations.stream()
                            .filter(d -> d.name().getString().equals(selectingName)).count() > 1;
                    mode = Mode.MACHINE_SELECT;
                    searchBox.setValue("");
                    scrollOff = 0;
                    rebuildRows();
                } else if (!row.full()) {
                    ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(row.destIndex());
                    PatternUploadClient.removeOverlay();
                }
            } else {
                // 指定 / 清除該供應器的機器 → 持久化 → 回目的地清單並重排
                PatternUploadConfig.assign(selectingName, row.destIndex() == CLEAR_ROW ? null : row.type());
                exitMachineSelect();
            }
            return true;
        }
        return true; // 面板內其他區域：吃掉點擊避免誤觸終端
    }

    private void exitMachineSelect() {
        mode = Mode.DESTINATIONS;
        selectingName = "";
        selectingDup = false;
        searchBox.setValue("");
        scrollOff = 0;
        rebuildRows();
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        if (!isInside(mx, my)) {
            return false;
        }
        scrollOff = Math.max(0, Math.min(scrollOff - (int) Math.signum(delta), rows.size() - maxRows));
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
