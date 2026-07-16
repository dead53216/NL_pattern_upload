package com.patternupload.mixin;

import com.patternupload.client.PatternUploadClient;

import com.gtocore.client.Message;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 攔截 GTOCore「編碼並發送」流程送回客戶端的目的地清單，
 * 改開本 mod 的上傳介面（取代 GTOCore 內建的目的地列表框）。
 */
@Mixin(value = Message.Client.class, remap = false)
public abstract class MessageClientMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final Logger PATTERN_UPLOAD$LOGGER = LogUtils.getLogger();

    @Inject(method = "patternDestinationReceived", at = @At("HEAD"), cancellable = true)
    private static void pattern_upload$onDestinations(Message.PatternDestination[] destinations, CallbackInfo ci) {
        PatternUploadClient.interceptSeen = true;
        PATTERN_UPLOAD$LOGGER.info("[pattern_upload] intercepted patternDestinationReceived: {} destinations", destinations.length);
        try {
            if (PatternUploadClient.onDestinations(destinations)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // 絕不讓例外打斷 GTOCore 原流程；記錄後放行原介面
            PATTERN_UPLOAD$LOGGER.error("[pattern_upload] overlay failed, falling back to GTOCore list", t);
        }
    }
}
