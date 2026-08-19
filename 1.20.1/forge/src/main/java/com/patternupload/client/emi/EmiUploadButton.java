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

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.RecipeFillButtonWidget;
import dev.emi.emi.screen.RecipeScreen;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI 配方頁的「編碼並上傳」鈕：等同在樣板編碼終端按 GTOCore 的上傳鈕（編碼並發送）。
 *
 * <p>**直接繼承 EMI 的填充配方鈕**（{@link RecipeFillButtonWidget}）：填充、{@code canFill} 判定、
 * Shift 全量、缺料 tooltip、音效全部沿用 super，只換掉貼圖與點擊後續。繼承還換來一件關鍵的事——
 * {@code RecipeScreen.render} 是以 {@code widget instanceof RecipeFillButtonWidget} 決定要不要呼叫
 * {@code EmiRecipeHandler.render}（滑鼠指著填充鈕時**高亮已有／可合成的材料格**），所以本鈕被指到時
 * EMI 會用**同一個 EmiCraftContext** 畫出同樣的高亮，不必自己重做一份。
 *
 * <p>按下後接著要一批目的地清單走「唯一機器自動直傳 / 多台開面板」決策——行為與終端上傳鈕一模一樣，
 * 但**整條流程留在 EMI 裡**：不關配方頁、面板也開在 EMI 上。
 *
 * <p>不關 EMI 的作法見 {@link #mouseClicked}；清單為何要改由本 mod 伺服端回報見
 * {@code PatternUploadClient.uploadFromEmi}。
 *
 * <p>順序安全：EMI 填充是把 {@code FakeSlot.setFilterTo} 的封包送給伺服端（客戶端槽不會當場更新），
 * 我們的編碼請求是**同一條連線的後續封包**，伺服端照到達順序處理 → 編碼時看到的必是填好的格子。
 * 客戶端這邊的機器判定發生在 {@code decidePending}（等清單／建議回來才判），那時槽位早已同步回來。
 *
 * <p>本類與 {@link EmiUploadIntegration} 是**唯二**碰 {@code dev.emi.*} 的類，只在 EMI 存在時才會被載入。
 */
public final class EmiUploadButton extends RecipeFillButtonWidget {

    /** 12x12 三態（v=0 一般、12 hover、24 不可填充），仿 EMI 自家按鈕貼圖規格。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(PatternUploadMod.MOD_ID, "textures/gui/emi_upload_button.png");
    static final int SIZE = 12;

    EmiUploadButton(int x, int y, EmiRecipe recipe) {
        super(x, y, recipe);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // super 的 getTextureOffset 已經算好三態（0 一般／12 hover／24 不可填充），貼圖規格也照 EMI 排，
        // 直接拿來當 v 位移即可——狀態判定與 EMI 自家填充鈕完全同步。
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(TEXTURE, x, y, 0.0F, (float) getTextureOffset(mouseX, mouseY), SIZE, SIZE, 32, 64);
    }

    @Override
    public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
        List<ClientTooltipComponent> lines = new ArrayList<>();
        lines.add(line(Component.translatable("pattern_upload.emi.upload")));
        lines.add(line(Component.translatable("pattern_upload.emi.upload.desc").withStyle(ChatFormatting.GRAY)));
        lines.add(line(Component.translatable("pattern_upload.emi.upload.force").withStyle(ChatFormatting.DARK_GRAY)));
        // super 的 tooltip（缺料清單／不適用提示）接在後面——不可填充的原因由 EMI／GTO 自己說明。
        try {
            List<ClientTooltipComponent> fill = super.getTooltip(mouseX, mouseY);
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
        // 跑 super 的填充，但**不讓 GTOCore 把 EMI 關掉**：
        // GTO 的 GTAe2PatternTerminalHandler.craft() 末尾會 `if (mc.screen instanceof RecipeScreen rs) rs.close()`。
        // 填充期間把 mc.screen 直接指向終端（不經 setScreen，不跑 removed/init 生命週期，中間也不會有 tick／render）
        // → GTO 那個 instanceof 不成立、EMI 留著；EmiApi.getHandledScreen() 反而更直接（AbstractContainerScreen 分支）。
        Minecraft mc = Minecraft.getInstance();
        boolean filled;
        mc.screen = term;
        try {
            filled = super.mouseClicked(mouseX, mouseY, 0);
        } finally {
            mc.screen = recipeScreen;
        }
        // 填充失敗（缺料／本配方不支援）→ 與 EMI 填充鈕同樣靜默不動作，也不送編碼請求
        if (!filled) {
            return false;
        }
        // 中鍵＝強制開面板（比照終端上傳鈕的中鍵手勢）；左／右鍵走一般自動直傳決策。
        // 決策與面板都掛在 EMI 配方頁上——整條編碼並上傳流程不跳回終端。
        // 帶上這則配方的 id：GT 配方即 GTRecipeDefinition.id，客戶端據此當場定機器，
        // 不必等（會慢一 tick 的）選單同步——否則判到的是上一張樣板的機器。
        PatternUploadClient.uploadFromEmi(term, recipeScreen, button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
                this.recipe.getId());
        return true;
    }
}
