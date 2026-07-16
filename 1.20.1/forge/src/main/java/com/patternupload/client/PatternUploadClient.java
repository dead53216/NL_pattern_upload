package com.patternupload.client;

import com.patternupload.PatternUploadMod;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.gtocore.client.Message;
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

/**
 * 客戶端狀態橋接：接手 GTOCore 的目的地清單，改顯示本 mod 的上傳介面 overlay。
 * overlay 不注入畫面 widget 樹，而是透過 Forge ScreenEvent 疊加渲染與攔截輸入
 * （避免對 vanilla Screen 做 mixin 的混淆風險）。
 */
@Mod.EventBusSubscriber(modid = PatternUploadMod.MOD_ID, value = Dist.CLIENT)
public final class PatternUploadClient {

    @Nullable
    private static UploadOverlay overlay;
    /** 玩家本次手動指定的機器（優先於從樣板 NBT 同步回來的自動判定）。 */
    @Nullable
    static GTRecipeType lastManualType;
    /** 手動指定後等待伺服端重送清單中；此時保留 lastManualType。 */
    private static boolean expectingRefresh;

    private PatternUploadClient() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** MessageClientMixin 進入點。回傳 true = 已接手，GTOCore 原清單不再顯示。 */
    public static boolean onDestinations(Message.PatternDestination[] destinations) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PatternEncodingTermScreen<?> screen)) {
            LOGGER.info("[pattern_upload] skip: screen is {}", mc.screen == null ? "null" : mc.screen.getClass().getName());
            return false;
        }
        if (!(screen.getMenu() instanceof IExtendedPatternEncodingTerm.Menu)) {
            LOGGER.info("[pattern_upload] skip: menu is {}", screen.getMenu().getClass().getName());
            return false;
        }
        LOGGER.info("[pattern_upload] showing overlay ({} destinations)", destinations.length);
        if (!expectingRefresh) {
            lastManualType = null; // 新一輪上傳：清掉上次的手動指定
        }
        expectingRefresh = false;
        overlay = new UploadOverlay(screen, destinations);
        return true;
    }

    /** 手動指定機器：同步到伺服端（寫進樣板 NBT 的 recipe 標籤）並重新請求排序後的目的地清單。 */
    static void onManualSelect(PatternEncodingTermScreen<?> screen, GTRecipeType type) {
        lastManualType = type;
        expectingRefresh = true;
        var menu = screen.getMenu();
        ((com.gtolib.api.ae2.IPatterEncodingTermMenu) menu).gtolib$addRecipe(type.registryName + "/manual");
        ((IExtendedPatternEncodingTerm.Menu) menu).gtolib$sendEncodeRequest();
    }

    public static void removeOverlay() {
        overlay = null;
    }

    /** 目前樣板對應的配方類型：手動指定優先，否則讀選單同步的樣板 recipe 資訊（GTOCore @GuiSync 欄位）。 */
    @Nullable
    static GTRecipeType currentRecipeType(AbstractContainerMenu menu) {
        if (lastManualType != null) {
            return lastManualType;
        }
        try {
            Object value = menu.getClass().getField("gtocore$recipe").get(menu);
            if (value instanceof String s && !s.isEmpty()) {
                ResourceLocation rl = ResourceLocation.tryParse(s.split("/")[0]);
                if (rl != null) {
                    return GTRegistries.RECIPE_TYPES.get(rl);
                }
            }
        } catch (Throwable ignored) {
            // GTOCore 內部欄位名變動時退回「未知」，僅影響顯示 icon，不影響功能
        }
        return null;
    }

    @Nullable
    private static UploadOverlay activeOverlay(ScreenEvent event) {
        if (overlay != null && event.getScreen() == overlay.screen()) {
            return overlay;
        }
        return null;
    }

    // ------------------------------------------------------------- 事件疊加

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        var o = activeOverlay(event);
        if (o != null) {
            o.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        var o = activeOverlay(event);
        if (o != null && o.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
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
            lastManualType = null;
            expectingRefresh = false;
        }
    }
}
