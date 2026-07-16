package com.patternupload.mixin;

import com.mojang.logging.LogUtils;

import com.gtocore.client.Message;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 診斷用對照組：攔外層 Message.init()，分辨「gtocore 不可變換」vs「內部類目標問題」。 */
@Mixin(value = Message.class, remap = false)
public abstract class MessageInitMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final Logger PATTERN_UPLOAD$LOGGER2 = LogUtils.getLogger();

    @Inject(method = "init", at = @At("HEAD"))
    private static void pattern_upload$onInit(CallbackInfo ci) {
        PATTERN_UPLOAD$LOGGER2.info("[pattern_upload] CONTROL: outer Message mixin applied (init intercepted)");
    }
}
