package com.patternupload.client;

import com.gtocore.integration.ae.client.AESearchPatternProviderListBox;

import com.mojang.logging.LogUtils;

import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 以反射讀取 GTOCore 目的地清單框（AESearchPatternProviderListBox）的內部資料。
 * 只做「讀」，不需要任何類變換（mixin），任何環境皆可用。
 * 欄位名已對照 0.5.6-alpha / 0.5.6-beta / 26.7.x：allItems / index / icon / name / full。
 */
final class ListBoxReflector {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一個目的地列（自 GTOCore SimpleItem 抽出）。 */
    record Dest(@Nullable AEKey icon, Component name, boolean full, int index) {}

    private static Field allItemsField;
    private static Field itemIndexField;
    private static Field itemIconField;
    private static Field itemNameField;
    private static Field itemFullField;
    private static boolean broken = false;

    private ListBoxReflector() {}

    /** 抽出全部目的地；反射失敗回 null（呼叫端應保留 GTOCore 原清單）。 */
    @Nullable
    static List<Dest> extract(AESearchPatternProviderListBox box) {
        if (broken) {
            return null;
        }
        try {
            if (allItemsField == null) {
                allItemsField = AESearchPatternProviderListBox.class.getDeclaredField("allItems");
                allItemsField.setAccessible(true);
            }
            List<?> items = (List<?>) allItemsField.get(box);
            List<Dest> result = new ArrayList<>(items.size());
            for (Object item : items) {
                if (itemIndexField == null) {
                    Class<?> c = item.getClass();
                    itemIndexField = c.getDeclaredField("index");
                    itemIconField = c.getDeclaredField("icon");
                    itemNameField = c.getDeclaredField("name");
                    itemFullField = c.getDeclaredField("full");
                    itemIndexField.setAccessible(true);
                    itemIconField.setAccessible(true);
                    itemNameField.setAccessible(true);
                    itemFullField.setAccessible(true);
                }
                result.add(new Dest(
                        (AEKey) itemIconField.get(item),
                        (Component) itemNameField.get(item),
                        itemFullField.getBoolean(item),
                        itemIndexField.getInt(item)));
            }
            return result;
        } catch (Throwable t) {
            broken = true; // 只報一次，之後放行原清單
            LOGGER.error("[pattern_upload] 無法讀取 GTOCore 清單內部資料，退回原介面", t);
            return null;
        }
    }
}
