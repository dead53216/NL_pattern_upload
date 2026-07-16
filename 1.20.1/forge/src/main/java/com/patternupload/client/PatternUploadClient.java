package com.patternupload.client;

import com.patternupload.PatternUploadMod;

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

    static {
        LOGGER.info("[pattern_upload] PatternUploadClient loaded (hijack mode, no mixin)");
    }

    private PatternUploadClient() {}

    public static void removeOverlay() {
        overlay = null;
    }

    /** 目前樣板對應的配方類型：讀選單同步的樣板 recipe 資訊（GTOCore @GuiSync 欄位）。 */
    @Nullable
    static GTRecipeType currentRecipeType(AbstractContainerMenu menu) {
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
        overlay = new UploadOverlay(screen, dests);
        LOGGER.info("[pattern_upload] hijacked GTOCore destination list: {} entries", dests.size());
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
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || testPending < 0) {
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
