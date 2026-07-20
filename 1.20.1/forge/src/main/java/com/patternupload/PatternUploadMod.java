package com.patternupload;

import com.patternupload.common.ModConstants;
import com.patternupload.net.Network;

import net.minecraftforge.fml.common.Mod;

@Mod(PatternUploadMod.MOD_ID)
public final class PatternUploadMod {
    public static final String MOD_ID = ModConstants.MOD_ID;

    public PatternUploadMod() {
        // 目的地座標同步封包（雙端註冊；伺服端反射 GTOCore 私有欄位供同名供應器獨立身分用）
        Network.init();
    }
}
