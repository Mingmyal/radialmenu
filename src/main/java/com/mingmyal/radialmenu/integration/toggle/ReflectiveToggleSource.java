package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * {@link ReflectiveConfigToggleProvider} 发现出的单个布尔开关。
 * <p>
 * 读取时优先走 getter, 失败再直接读字段: 部分模组的访问器带派生逻辑(或名字不规则,
 * 如 {@code areBerriesEnabled} 对应字段 {@code berriesEnabled}), 直接读字段则作为兜底。
 */
public final class ReflectiveToggleSource implements ToggleSource {

    private final ReflectiveConfigTarget target;
    private final String fieldName;
    private final Field field;
    private final Method getter;
    private final Method setter;
    private final Method saver;
    private final ReflectiveConfigTarget.Writer writer;
    private final ResourceLocation id;
    private final String labelKey;

    private Component cachedDisplay;

    ReflectiveToggleSource(
            ReflectiveConfigTarget target,
            String fieldName,
            Field field,
            Method getter,
            Method setter,
            Method saver,
            ReflectiveConfigTarget.Writer writer,
            ResourceLocation id,
            String labelKey) {
        this.target = target;
        this.fieldName = fieldName;
        this.field = field;
        this.getter = getter;
        this.setter = setter;
        this.saver = saver;
        this.writer = writer;
        this.id = id;
        this.labelKey = labelKey;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public Component displayName() {
        if (cachedDisplay != null) return cachedDisplay;

        // 翻译键不存在时 translatable 会原样返回键名, 据此判断是否有可用翻译
        try {
            Component translated = Component.translatable(labelKey);
            String text = translated.getString();
            if (text != null && !text.isBlank() && !text.equals(labelKey)) {
                cachedDisplay = translated;
                return cachedDisplay;
            }
        } catch (Throwable ignored) {
            // 语言未就绪时退回字段名
        }

        cachedDisplay = Component.literal(fieldName);
        return cachedDisplay;
    }

    @Override
    public String modId() {
        return target.modId();
    }

    @Override
    public Component modName() {
        return Component.literal(ReflectUtil.modDisplayName(target.modId()));
    }

    @Override
    public Component description() {
        return Component.literal(fieldName);
    }

    @Override
    public String providerId() {
        return ReflectiveConfigToggleProvider.ID;
    }

    @Override
    public boolean isAvailable() {
        return target.resolveRoot() != null;
    }

    @Override
    public boolean getState() {
        Object root = target.resolveRoot();
        if (root == null) return false;

        if (getter != null) {
            Object value = ReflectUtil.invoke(getter, root);
            if (value instanceof Boolean b) return b;
        }

        Object raw = ReflectUtil.getField(field, root);
        return raw instanceof Boolean b && b;
    }

    @Override
    public void setState(boolean value) {
        Object root = target.resolveRoot();
        if (root == null) return;

        if (writer != null) {
            try {
                writer.write(root, value);
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Reflective toggle '{}' could not be written", id, t);
            }
            return;
        }

        if (setter == null) return;
        ReflectUtil.invoke(setter, root, value);

        // 改内存不会落盘: 这类模组把持久化放在自己的 save() 里
        if (saver != null) {
            ReflectUtil.invoke(saver, root);
        }
    }

    @Override
    public String toString() {
        return target.modId() + ":" + fieldName;
    }
}
