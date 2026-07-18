package com.patternupload.compat.emi;

import com.patternupload.client.PatternUploadClient;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;

/**
 * EMI 相容：把本 mod overlay 面板註冊成排除區，讓 EMI 右側物品清單自動避開（擠開）。
 * <p>
 * 軟依賴：本類只被 EMI 透過 {@link EmiEntrypoint} 掃描載入；EMI 缺席時整個類不會被載入，
 * 不影響 mod 其餘功能（core 類不引用 EMI）。
 */
@EmiEntrypoint
public final class PatternUploadEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // 每幀查目前面板矩形；有開就回報為排除區
        registry.addGenericExclusionArea((screen, consumer) -> {
            int[] b = PatternUploadClient.activePanelBounds();
            if (b != null) {
                consumer.accept(new Bounds(b[0], b[1], b[2], b[3]));
            }
        });
    }
}
