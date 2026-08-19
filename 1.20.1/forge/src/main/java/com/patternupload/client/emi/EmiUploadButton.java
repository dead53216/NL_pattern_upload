package com.patternupload.client.emi;

import com.patternupload.PatternUploadMod;
import com.patternupload.client.PatternUploadClient;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.screen.RecipeScreen;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI 配方頁的「編碼並上傳」鈕：等同在樣板編碼終端按 GTOCore 的上傳鈕（編碼並發送）。
 *
 * <p>按下時**先借 EMI 自己的填充配方鈕**跑一次填充（{@code canFill} 判定、Shift 全量、音效、
 * 關閉配方頁全照 EMI／GTOCore 原邏輯），成功才接著要一批目的地清單走本 mod 既有的
 * 「唯一機器自動直傳 / 多台開面板」決策——故行為與終端上傳鈕一模一樣，只是從 EMI 直接發動。
 *
 * <p>順序安全：EMI 填充是把 {@code FakeSlot.setFilterTo} 的封包送給伺服端（客戶端槽不會當場更新），
 * 我們的編碼請求是**同一條連線的後續封包**，伺服端照到達順序處理 → 編碼時看到的必是填好的格子。
 * 客戶端這邊的機器判定發生在 {@code decidePending}（等座標／建議回來才判），那時槽位早已同步回來。
 *
 * <p>本類與 {@link EmiUploadIntegration} 是**唯二**碰 {@code dev.emi.*} 的類，只在 EMI 存在時才會被載入。
 */
public final class EmiUploadButton extends Widget {

    /** 12x12 三態（v=0 一般、12 hover、24 不可填充），仿 EMI 自家按鈕貼圖規格。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(PatternUploadMod.MOD_ID, "textures/gui/emi_upload_button.png");
    static final int SIZE = 12;

    private final int x;
    private final int y;
    /** EMI 的填充配方鈕：填充實作與「能不能填」的判定都借它，不自己複製一份。 */
    private final Widget fillButton;

    EmiUploadButton(int x, int y, Widget fillButton) {
        this.x = x;
        this.y = y;
        this.fillButton = fillButton;
    }

    @Override
    public Bounds getBounds() {
        return new Bounds(x, y, SIZE, SIZE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int v = EmiUploadIntegration.canFill(fillButton) ? (getBounds().contains(mouseX, mouseY) ? 12 : 0) : 24;
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(TEXTURE, x, y, 0.0F, (float) v, SIZE, SIZE, 32, 64);
    }

    @Override
    public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
        List<ClientTooltipComponent> lines = new ArrayList<>();
        lines.add(line(Component.translatable("pattern_upload.emi.upload")));
        lines.add(line(Component.translatable("pattern_upload.emi.upload.desc").withStyle(ChatFormatting.GRAY)));
        lines.add(line(Component.translatable("pattern_upload.emi.upload.force").withStyle(ChatFormatting.DARK_GRAY)));
        // 借 EMI 填充鈕自己的 tooltip（缺料清單／不適用提示）接在後面——不可填充的原因由它說明。
        try {
            List<ClientTooltipComponent> fill = fillButton.getTooltip(mouseX, mouseY);
            if (fill != null) {
                lines.addAll(fill);
            }
        } catch (Throwable ignored) {
            // EMI 端 tooltip 失敗不影響本鈕
        }
        return lines;
    }

    private static ClientTooltipComponent line(Component text) {
        return ClientTooltipComponent.create(text.getVisualOrderText());
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!(Minecraft.getInstance().screen instanceof RecipeScreen recipeScreen)) {
            return false;
        }
        // 只對「從 GTO 樣板編碼終端開的 EMI」動作；終端實例與選單先抓下來——EMI 填充完會關掉配方頁。
        if (!(recipeScreen.old instanceof PatternEncodingTermScreen<?> term)
                || !(term instanceof IExtendedPatternEncodingTerm)) {
            return false;
        }
        AbstractContainerMenu menu = term.getMenu();
        // 填充失敗（缺料／本配方不支援）→ 與 EMI 填充鈕同樣靜默不動作，也不送編碼請求
        if (!fillButton.mouseClicked(mouseX, mouseY, 0)) {
            return false;
        }
        // 中鍵＝強制開面板（比照終端上傳鈕的中鍵手勢）；左／右鍵走一般自動直傳決策
        PatternUploadClient.uploadFromEmi(menu, button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        return true;
    }
}
