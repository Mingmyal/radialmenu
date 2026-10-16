package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.api.RadialSlot;

import java.util.List;

/**
 * 槽位在槽位树中的定位键。
 * <p>
 * 轮盘本身就按下标寻址, 所以键直接用<b>深度优先的下标路径</b>表示:
 * {@code "0"}、{@code "0.2"}、{@code "0.2.1"}。
 * <p>
 * 开关类型的槽位, 其条件约束挂在"状态动作"子槽位上而不是父槽位, 因此键尾部
 * 需要一个状态后缀: {@code "0#on"} / {@code "0#off"}。没有后缀即表示槽位自身。
 * <p>
 * 读写两侧必须走同一套遍历顺序, 键才能对齐 —— 生成与解析都由本类提供,
 * 避免两处各写一份而悄悄错位。
 */
public final class SlotPath {

    /** 开关"开启"状态动作槽位的后缀。 */
    public static final String SUFFIX_ON = "#on";

    /** 开关"关闭"状态动作槽位的后缀。 */
    public static final String SUFFIX_OFF = "#off";

    private SlotPath() {}

    /** 由父路径与下标拼出子键。 */
    public static String child(String prefix, int index) {
        return prefix == null || prefix.isEmpty() ? String.valueOf(index) : prefix + "." + index;
    }

    /** 为键加上状态动作后缀。 */
    public static String withAction(String key, boolean on) {
        return key + (on ? SUFFIX_ON : SUFFIX_OFF);
    }

    /**
     * 按键定位槽位; 键无效或路径不存在时返回 null。
     * <p>
     * 纯数组/列表遍历, 不做任何模组扫描或注册表查询, 因此服务端与客户端都能安全调用。
     */
    public static RadialSlot resolve(List<RadialSlot> root, String key) {
        if (root == null || key == null || key.isBlank()) {
            return null;
        }

        String body = key.trim();
        boolean actionOn = false;
        boolean hasAction = false;
        if (body.endsWith(SUFFIX_ON)) {
            body = body.substring(0, body.length() - SUFFIX_ON.length());
            actionOn = true;
            hasAction = true;
        } else if (body.endsWith(SUFFIX_OFF)) {
            body = body.substring(0, body.length() - SUFFIX_OFF.length());
            hasAction = true;
        }

        RadialSlot slot = resolvePath(root, body);
        if (slot == null || !hasAction) {
            return slot;
        }

        // 状态动作槽位可能尚未创建(旧配置/开关类型未启用), 此时退回槽位自身
        RadialSlot action = actionOn ? slot.toggleActionOn : slot.toggleActionOff;
        return action != null ? action : slot;
    }

    private static RadialSlot resolvePath(List<RadialSlot> root, String body) {
        String[] segments = body.split("\\.");
        List<RadialSlot> level = root;
        RadialSlot current = null;

        for (String segment : segments) {
            int index;
            try {
                index = Integer.parseInt(segment.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (level == null || index < 0 || index >= level.size()) {
                return null;
            }
            current = level.get(index);
            if (current == null) {
                return null;
            }
            level = current.children;
        }

        return current;
    }

    /**
     * 遍历槽位树, 为每个"自身 + 两个状态动作槽位"回调一次键。
     * <p>
     * 读写共用的唯一遍历入口: 任何一侧新增需要按键定位的状态, 都从这里出发,
     * 否则键就会错位。
     */
    public static void forEach(List<RadialSlot> slots, Visitor visitor) {
        walk(slots, "", visitor);
    }

    private static void walk(List<RadialSlot> slots, String prefix, Visitor visitor) {
        if (slots == null) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            RadialSlot slot = slots.get(i);
            if (slot == null) {
                continue;
            }

            String key = child(prefix, i);
            visitor.visit(slot, key);

            // 开关类型的状态动作槽位自身也可能带条件约束, 需要单独的键
            if (slot.toggleActionOn != null) {
                visitor.visit(slot.toggleActionOn, withAction(key, true));
            }
            if (slot.toggleActionOff != null) {
                visitor.visit(slot.toggleActionOff, withAction(key, false));
            }

            walk(slot.children, key, visitor);
        }
    }

    /** 槽位访问回调。 */
    @FunctionalInterface
    public interface Visitor {
        void visit(RadialSlot slot, String key);
    }
}
