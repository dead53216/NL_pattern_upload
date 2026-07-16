package com.patternupload.client;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Just Enough Characters（jecharacters）軟依賴：存在時用其拼音比對器，
 * 中文名稱可用拼音搜尋；缺席時退回大小寫不敏感的子字串比對。
 * 非編譯依賴，純反射解析（與 NL_oreveinfilter 同做法）。
 */
final class PinyinMatch {

    // me.towdium.jecharacters.utils.Match#contains(String, CharSequence)；JECh 未載入時為 null
    private static final Method MATCH;

    static {
        Method m = null;
        try {
            Class<?> cls = Class.forName("me.towdium.jecharacters.utils.Match");
            m = cls.getMethod("contains", String.class, CharSequence.class);
        } catch (Throwable ignored) {
            m = null;
        }
        MATCH = m;
    }

    private PinyinMatch() {}

    /** query 是否命中 text：JECh 在場走拼音比對，否則純子字串。 */
    static boolean matches(String text, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        if (MATCH != null) {
            try {
                if ((boolean) MATCH.invoke(null, text, query)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // JECh 內部丟例外 → 退回純比對
            }
        }
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
