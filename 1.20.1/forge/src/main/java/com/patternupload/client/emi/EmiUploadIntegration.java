package com.patternupload.client.emi;

import com.gtocore.integration.ae.hooks.IExtendedPatternEncodingTerm;

import net.minecraft.client.gui.screens.Screen;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.RecipeFillButtonWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import dev.emi.emi.widget.RecipeButtonWidget;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 在 EMI 配方頁每則配方的「填充配方」鈕**上方**插一顆「編碼並上傳」鈕（{@link EmiUploadButton}）。
 *
 * <p>作法是把自製 widget 直接加進 EMI 的 {@code WidgetGroup.widgets}（公開可變 list）：
 * 繪製、hover、tooltip、點擊派送全由 EMI 自己處理 → 與原生按鈕同層同序，不用 mixin、
 * 也不會蓋到 EMI 的 tooltip。唯一需要反射的是 {@code RecipeScreen.currentPage}（private），
 * EMI 自家欄位名不經 SRG remap，穩定。
 *
 * <p>每幀（{@code ScreenEvent.Render.Pre}）呼叫一次而非只在 init：EMI 翻頁／換分頁會重建
 * {@code currentPage}，逐 group 以「最後一個 widget 是不是我們的」O(1) 判斷是否已插過。
 *
 * <p>位置：自填充鈕往上找第一個沒被 EMI 按鈕佔用的格（間距 14，同 {@code RecipeDisplay.addButtons}）；
 * 往上會超出配方背景時改往下找，一律不重疊。
 */
public final class EmiUploadIntegration {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** EMI 按鈕欄的縱向間距（見 {@code RecipeDisplay.addButtons}）。 */
    private static final int STEP = 14;
    /** 配方背景只往上多 4px（{@code RecipeBackground(-4, -4, …)}），再上去就露出框外。 */
    private static final int TOP_LIMIT = -4;

    private static Field currentPageField;
    private static Field canFillField;
    private static boolean canFillBroken;

    private EmiUploadIntegration() {}

    /** 每幀由 {@code PatternUploadClient.onRenderPre} 呼叫（已用 ModList 旗標守衛 EMI 是否存在）。 */
    public static void onScreen(Screen screen) {
        if (!(screen instanceof RecipeScreen recipeScreen)) {
            return;
        }
        // 只有「從 GTO 樣板編碼終端開的 EMI」才給這顆鈕：其他容器按了也無處可送
        if (!(recipeScreen.old instanceof PatternEncodingTermScreen<?> term)
                || !(term instanceof IExtendedPatternEncodingTerm)) {
            return;
        }
        List<WidgetGroup> page = currentPage(recipeScreen);
        if (page == null) {
            return;
        }
        for (WidgetGroup group : page) {
            inject(group);
        }
    }

    /** 這則配方是否可填充（EMI 填充鈕的 {@code canFill}）——本鈕用來畫成不可用態。取不到一律當可填。 */
    static boolean canFill(Widget fillButton) {
        if (canFillBroken) {
            return true;
        }
        try {
            Field f = canFillField;
            if (f == null) {
                f = RecipeFillButtonWidget.class.getDeclaredField("canFill");
                f.setAccessible(true);
                canFillField = f;
            }
            return f.getBoolean(fillButton);
        } catch (Throwable t) {
            canFillBroken = true;
            LOGGER.warn("[pattern_upload] EMI: cannot read fill button state, always shown enabled", t);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WidgetGroup> currentPage(RecipeScreen screen) {
        try {
            Field f = currentPageField;
            if (f == null) {
                f = RecipeScreen.class.getDeclaredField("currentPage");
                f.setAccessible(true);
                currentPageField = f;
            }
            Object value = f.get(screen);
            return value instanceof List<?> list ? (List<WidgetGroup>) list : null;
        } catch (Throwable t) {
            throw new IllegalStateException("EMI RecipeScreen.currentPage unreadable", t);
        }
    }

    private static void inject(WidgetGroup group) {
        List<Widget> widgets = group.widgets;
        if (widgets.isEmpty() || widgets.get(widgets.size() - 1) instanceof EmiUploadButton) {
            return; // 我們一律 append 在最後 → O(1) 判定已插過
        }
        Widget fill = null;
        for (Widget w : widgets) {
            if (w instanceof EmiUploadButton) {
                return; // 保險：已插過（EMI 之後又 add 了別的 widget）
            }
            if (fill == null && w instanceof RecipeFillButtonWidget) {
                fill = w;
            }
        }
        if (fill == null) {
            return; // 這則配方沒有填充鈕（不支援填充／EMI 設定關掉）→ 也不給上傳鈕
        }
        Bounds base = fill.getBounds();
        int bx = base.x();
        int by = base.y() - STEP;
        while (occupied(widgets, bx, by)) {
            by -= STEP;
        }
        if (by < TOP_LIMIT) {
            // 按鈕欄已頂到配方框上緣 → 改往下找空位，寧可在下方也不畫到框外
            by = base.y() + STEP;
            while (occupied(widgets, bx, by)) {
                by += STEP;
            }
        }
        widgets.add(new EmiUploadButton(bx, by, fill));
    }

    /** 該格是否已被 EMI 的配方按鈕佔用（只看按鈕，配方內容不在按鈕欄的 x 上）。 */
    private static boolean occupied(List<Widget> widgets, int x, int y) {
        for (Widget w : widgets) {
            if (!(w instanceof RecipeButtonWidget)) {
                continue;
            }
            Bounds b = w.getBounds();
            if (b.x() == x && b.y() == y) {
                return true;
            }
        }
        return false;
    }
}
