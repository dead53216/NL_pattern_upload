package com.patternupload.client;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上傳介面 overlay：由 ScreenEvent 疊加在樣板編碼終端上。
 * DESTINATIONS 模式列出目的地樣板供應器；標題列最左顯示樣板對應機器（自動判定）。
 * 點「目的地列的 icon」→ MACHINE_SELECT 模式指定該供應器是什麼機器（接口貼子網時用），
 * 指定持久化於 config/pattern_upload.json；與樣板機器吻合的供應器本地浮頂。
 * 搜尋欄支援 Just Enough Characters 拼音比對（軟依賴）。
 */
final class UploadOverlay {

    private enum Mode {
        DESTINATIONS,
        MACHINE_SELECT
    }

    private static final int PANEL_W = 150;
    private static final int ROW_H = 16;
    private static final int MAX_ROWS = 6;
    private static final int HEADER_H = 18;
    private static final int SEARCH_H = 14;
    /** MACHINE_SELECT 清單裡「清除指定」列的 destIndex 哨兵值。 */
    private static final int CLEAR_ROW = -2;

    private final PatternEncodingTermScreen<?> screen;
    private final java.util.List<ListBoxReflector.Dest> destinations;
    private final Font font;
    private final EditBox searchBox;
    private int x;
    private int y;
    private boolean dragging = false;
    private int dragOffX;
    private int dragOffY;

    private Mode mode = Mode.DESTINATIONS;
    /** MACHINE_SELECT 模式的目標供應器名稱（config 的鍵）。 */
    private String selectingName = "";
    private int scrollOff = 0;
    private final List<Row> rows = new ArrayList<>();

    private record Row(ItemStack icon, AEKey key, Component name, boolean full, int destIndex, GTRecipeType type) {}

    UploadOverlay(PatternEncodingTermScreen<?> screen, java.util.List<ListBoxReflector.Dest> destinations) {
        this.screen = screen;
        this.destinations = destinations;
        this.font = Minecraft.getInstance().font;
        Integer px = PatternUploadConfig.panelX();
        Integer py = PatternUploadConfig.panelY();
        if (px != null && py != null) {
            this.x = Math.max(0, Math.min(px, screen.width - PANEL_W));
            this.y = Math.max(0, Math.min(py, screen.height - 40));
        } else {
            this.x = Math.min(screen.getGuiLeft() + screen.getXSize() + 4, screen.width - PANEL_W - 2);
            this.y = Math.max(2, screen.getGuiTop() + 4);
        }
        this.searchBox = new EditBox(this.font, x + 4, y + HEADER_H, PANEL_W - 8, SEARCH_H - 2, Component.empty());
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(true);
        this.searchBox.setHint(Component.translatable("pattern_upload.search"));
        this.searchBox.setResponder(s -> rebuildRows());
        rebuildRows();
    }

    PatternEncodingTermScreen<?> screen() {
        return screen;
    }

    // ------------------------------------------------------------------ data

    private void rebuildRows() {
        rows.clear();
        String filter = searchBox.getValue();
        if (mode == Mode.DESTINATIONS) {
            // 本地重排：被指定機器且吻合本樣板者浮頂（伺服端排序處理不了接口類供應器）
            GTRecipeType current = PatternUploadClient.currentRecipeType(screen.getMenu());
            List<ListBoxReflector.Dest> ordered = new ArrayList<>(destinations);
            if (current != null) {
                ordered.sort(Comparator.comparingInt(
                        d -> (!d.full() && current == PatternUploadConfig.machineFor(d.name().getString())) ? 0 : 1));
            }
            for (var dest : ordered) {
                Component name = dest.name();
                if (!PinyinMatch.matches(name.getString(), filter)) {
                    continue;
                }
                GTRecipeType assigned = PatternUploadConfig.machineFor(name.getString());
                if (assigned != null) {
                    rows.add(new Row(RecipeTypeIcons.icon(assigned), null, name, dest.full(), dest.index(), assigned));
                } else {
                    rows.add(new Row(null, dest.icon(), name, dest.full(), dest.index(), null));
                }
            }
        } else {
            if (filter.isEmpty() && PatternUploadConfig.machineFor(selectingName) != null) {
                rows.add(new Row(RecipeTypeIcons.patternIcon(), null,
                        Component.translatable("pattern_upload.assign.clear"), false, CLEAR_ROW, null));
            }
            for (GTRecipeType type : RecipeTypeIcons.allTypes()) {
                Component name = RecipeTypeIcons.name(type);
                if (!PinyinMatch.matches(name.getString(), filter)) {
                    continue;
                }
                rows.add(new Row(RecipeTypeIcons.icon(type), null, name, false, -1, type));
            }
        }
        scrollOff = Math.max(0, Math.min(scrollOff, rows.size() - MAX_ROWS));
    }

    private int visibleRows() {
        return Math.min(rows.size(), MAX_ROWS);
    }

    private int rowsTop() {
        return y + HEADER_H + SEARCH_H + 2;
    }

    private int panelHeight() {
        return HEADER_H + SEARCH_H + 4 + Math.max(1, visibleRows()) * ROW_H + 4;
    }

    private ItemStack headerIcon() {
        if (mode == Mode.MACHINE_SELECT) {
            return RecipeTypeIcons.patternIcon();
        }
        GTRecipeType type = PatternUploadClient.currentRecipeType(screen.getMenu());
        return type != null ? RecipeTypeIcons.icon(type) : RecipeTypeIcons.patternIcon();
    }

    private Component headerTitle() {
        if (mode == Mode.MACHINE_SELECT) {
            return Component.literal(selectingName);
        }
        GTRecipeType type = PatternUploadClient.currentRecipeType(screen.getMenu());
        return type != null ? RecipeTypeIcons.name(type) : Component.translatable("pattern_upload.no_machine");
    }

    // ---------------------------------------------------------------- render

    void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int h = panelHeight();

        g.fill(x, y, x + PANEL_W, y + h, 0xF0141414);
        g.renderOutline(x, y, PANEL_W, h, 0xFF8B8B8B);

        // header：最左樣板機器 icon（顯示用）+ 標題 + 關閉鈕
        g.renderItem(headerIcon(), x + 2, y + 1);
        String title = font.plainSubstrByWidth(headerTitle().getString(), PANEL_W - 21 - 13);
        g.drawString(font, title, x + 21, y + 5, 0xFFFFFF);
        boolean closeHover = isOverClose(mouseX, mouseY);
        g.drawString(font, "✕", x + PANEL_W - 10, y + 5, closeHover ? 0xFF5555 : 0xAAAAAA);

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
            boolean hover = mouseX >= x + 2 && mouseX < x + PANEL_W - 2 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean iconHover = mode == Mode.DESTINATIONS && isOverRowIcon(mouseX, mouseY, ry);
            if (hover && !(mode == Mode.DESTINATIONS && row.full() && !iconHover)) {
                g.fill(x + 2, ry, x + PANEL_W - 2, ry + ROW_H, 0x40FFFFFF);
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
            g.drawString(font, font.plainSubstrByWidth(name, PANEL_W - 25), x + 21, ry + 4, color);
        }
        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("pattern_upload.empty").getString(), x + 6, top + 4, 0x888888);
        }
        if (rows.size() > MAX_ROWS) {
            String pos = (scrollOff + 1) + "-" + Math.min(scrollOff + MAX_ROWS, rows.size()) + "/" + rows.size();
            g.drawString(font, pos, x + PANEL_W - 5 - font.width(pos), y + h - 11, 0x888888);
        }

        // tooltips
        if (mode == Mode.DESTINATIONS) {
            int idx = rowIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                int ry = top + (idx - scrollOff) * ROW_H;
                if (isOverRowIcon(mouseX, mouseY, ry)) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.assign.tooltip"), mouseX, mouseY);
                } else if (rows.get(idx).full()) {
                    g.renderTooltip(font, Component.translatable("pattern_upload.full.tooltip"), mouseX, mouseY);
                }
            }
        }
    }

    // ----------------------------------------------------------------- input

    private boolean isInside(double mx, double my) {
        return mx >= x && mx < x + PANEL_W && my >= y && my < y + panelHeight();
    }

    private boolean isOverClose(double mx, double my) {
        return mx >= x + PANEL_W - 13 && mx < x + PANEL_W - 1 && my >= y + 2 && my < y + 15;
    }

    /** 標題列空白處（扣掉右關閉鈕）= 拖曳把手。 */
    private boolean isOverDragHandle(double mx, double my) {
        return mx >= x && mx < x + PANEL_W - 14 && my >= y && my < y + HEADER_H;
    }

    /** 目的地列最左 icon 區（點擊 = 指定該供應器的機器）。 */
    private boolean isOverRowIcon(double mx, double my, int rowY) {
        return mx >= x + 2 && mx < x + 20 && my >= rowY && my < rowY + ROW_H;
    }

    boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (!dragging) {
            return false;
        }
        x = Math.max(0, Math.min((int) mx - dragOffX, screen.width - PANEL_W));
        y = Math.max(0, Math.min((int) my - dragOffY, screen.height - 40));
        searchBox.setX(x + 4);
        searchBox.setY(y + HEADER_H);
        return true;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            PatternUploadConfig.savePanelPos(x, y); // 拖曳結束才落盤
            return true;
        }
        return false;
    }

    private int rowIndexAt(double mx, double my) {
        if (mx < x + 2 || mx >= x + PANEL_W - 2) {
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
                if (isOverRowIcon(mx, my, ry)) {
                    // 點 icon → 指定該供應器對應機器（滿槽也可指定）
                    selectingName = row.name().getString();
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
        searchBox.setValue("");
        scrollOff = 0;
        rebuildRows();
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        if (!isInside(mx, my)) {
            return false;
        }
        scrollOff = Math.max(0, Math.min(scrollOff - (int) Math.signum(delta), rows.size() - MAX_ROWS));
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
