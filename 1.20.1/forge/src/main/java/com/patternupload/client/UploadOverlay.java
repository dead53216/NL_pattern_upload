package com.patternupload.client;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.gtocore.client.Message;
import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 上傳介面 overlay：由 ScreenEvent 疊加在樣板編碼終端上。
 * DESTINATIONS 模式列出（伺服端已排序的）目的地樣板供應器；
 * 最左 icon 顯示樣板對應機器（判不出時顯示樣板 icon），點擊切換 MACHINE_SELECT 模式手動指定機器，
 * 指定後重新請求 → 有對應機器的供應器浮頂。
 */
final class UploadOverlay {

    private enum Mode {
        DESTINATIONS,
        MACHINE_SELECT
    }

    private static final int PANEL_W = 172;
    private static final int ROW_H = 18;
    private static final int MAX_ROWS = 8;
    private static final int HEADER_H = 22;
    private static final int SEARCH_H = 16;

    private final PatternEncodingTermScreen<?> screen;
    private final Message.PatternDestination[] destinations;
    private final Font font;
    private final EditBox searchBox;
    private final int x;
    private final int y;

    private Mode mode = Mode.DESTINATIONS;
    private int scrollOff = 0;
    private boolean refreshing = false;
    private final List<Row> rows = new ArrayList<>();

    private record Row(ItemStack icon, Component name, boolean full, int destIndex, GTRecipeType type) {}

    UploadOverlay(PatternEncodingTermScreen<?> screen, Message.PatternDestination[] destinations) {
        this.screen = screen;
        this.destinations = destinations;
        this.font = Minecraft.getInstance().font;
        this.x = Math.min(screen.getGuiLeft() + screen.getXSize() + 4, screen.width - PANEL_W - 2);
        this.y = Math.max(2, screen.getGuiTop() + 4);
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
        String filter = searchBox.getValue().toLowerCase(Locale.ROOT);
        if (mode == Mode.DESTINATIONS) {
            for (int i = 0; i < destinations.length; i++) {
                var dest = destinations[i];
                Component name = dest.group().name();
                if (!filter.isEmpty() && !name.getString().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                var iconKey = dest.group().icon();
                ItemStack icon = iconKey != null ? iconKey.toStack() : RecipeTypeIcons.patternIcon();
                rows.add(new Row(icon, name, dest.full(), i, null));
            }
        } else {
            for (GTRecipeType type : RecipeTypeIcons.allTypes()) {
                Component name = RecipeTypeIcons.name(type);
                if (!filter.isEmpty() && !name.getString().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                rows.add(new Row(RecipeTypeIcons.icon(type), name, false, -1, type));
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
            return Component.translatable("pattern_upload.title.machines");
        }
        GTRecipeType type = PatternUploadClient.currentRecipeType(screen.getMenu());
        return type != null ? RecipeTypeIcons.name(type) : Component.translatable("pattern_upload.no_machine");
    }

    // ---------------------------------------------------------------- render

    void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int h = panelHeight();

        g.fill(x, y, x + PANEL_W, y + h, 0xF0141414);
        g.renderOutline(x, y, PANEL_W, h, 0xFF8B8B8B);

        // header：最左機器/樣板 icon（可點）+ 標題 + 關閉鈕
        g.renderItem(headerIcon(), x + 4, y + 3);
        boolean iconHover = isOverHeaderIcon(mouseX, mouseY);
        if (iconHover) {
            g.fill(x + 3, y + 2, x + 21, y + 20, 0x40FFFFFF);
        }
        String title = font.plainSubstrByWidth(headerTitle().getString(), PANEL_W - 24 - 14);
        g.drawString(font, title, x + 24, y + 8, 0xFFFFFF);
        boolean closeHover = isOverClose(mouseX, mouseY);
        g.drawString(font, "✕", x + PANEL_W - 11, y + 8, closeHover ? 0xFF5555 : 0xAAAAAA);

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
            if (hover && !(mode == Mode.DESTINATIONS && row.full())) {
                g.fill(x + 2, ry, x + PANEL_W - 2, ry + ROW_H, 0x40FFFFFF);
            }
            g.renderItem(row.icon(), x + 4, ry + 1);
            int color = (mode == Mode.DESTINATIONS && row.full()) ? 0x777777 : 0xE0E0E0;
            String name = row.name().getString();
            if (mode == Mode.DESTINATIONS && row.full()) {
                name = name + " [" + Component.translatable("pattern_upload.full").getString() + "]";
            }
            g.drawString(font, font.plainSubstrByWidth(name, PANEL_W - 28), x + 24, ry + 5, color);
        }
        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("pattern_upload.empty").getString(), x + 6, top + 5, 0x888888);
        }
        if (rows.size() > MAX_ROWS) {
            String pos = (scrollOff + 1) + "-" + Math.min(scrollOff + MAX_ROWS, rows.size()) + "/" + rows.size();
            g.drawString(font, pos, x + PANEL_W - 6 - font.width(pos), y + h - 12, 0x888888);
        }
        if (refreshing) {
            g.drawString(font, Component.translatable("pattern_upload.refreshing").getString(), x + 6, y + h - 12, 0x55FF55);
        }

        // tooltips
        if (iconHover && mode == Mode.DESTINATIONS) {
            g.renderTooltip(font, Component.translatable("pattern_upload.machine.tooltip"), mouseX, mouseY);
        } else if (mode == Mode.DESTINATIONS) {
            int idx = rowIndexAt(mouseX, mouseY);
            if (idx >= 0 && rows.get(idx).full()) {
                g.renderTooltip(font, Component.translatable("pattern_upload.full.tooltip"), mouseX, mouseY);
            }
        }
    }

    // ----------------------------------------------------------------- input

    private boolean isInside(double mx, double my) {
        return mx >= x && mx < x + PANEL_W && my >= y && my < y + panelHeight();
    }

    private boolean isOverHeaderIcon(double mx, double my) {
        return mx >= x + 3 && mx < x + 21 && my >= y + 2 && my < y + 20;
    }

    private boolean isOverClose(double mx, double my) {
        return mx >= x + PANEL_W - 14 && mx < x + PANEL_W - 2 && my >= y + 4 && my < y + 16;
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
            PatternUploadClient.removeOverlay();
            return true;
        }
        if (isOverHeaderIcon(mx, my)) {
            mode = mode == Mode.DESTINATIONS ? Mode.MACHINE_SELECT : Mode.DESTINATIONS;
            searchBox.setValue("");
            scrollOff = 0;
            rebuildRows();
            return true;
        }
        int idx = rowIndexAt(mx, my);
        if (idx >= 0) {
            Row row = rows.get(idx);
            if (mode == Mode.DESTINATIONS) {
                if (!row.full()) {
                    ((IExtendedPatternEncodingTerm.Menu) screen.getMenu()).gtolib$sendPattern(row.destIndex());
                    PatternUploadClient.removeOverlay();
                }
            } else {
                // 指定機器 → 同步到伺服端（寫入樣板 NBT）並重新請求排序後的目的地
                PatternUploadClient.onManualSelect(screen, row.type());
                refreshing = true;
            }
            return true;
        }
        return true; // 面板內其他區域：吃掉點擊避免誤觸終端
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
            PatternUploadClient.removeOverlay();
            return true; // 第一次 ESC 先關 overlay，再按才關終端
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
