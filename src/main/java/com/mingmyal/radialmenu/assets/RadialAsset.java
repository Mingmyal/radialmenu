package com.mingmyal.radialmenu.assets;

import com.mingmyal.radialmenu.util.ImportedImageStore;
import com.mingmyal.radialmenu.util.ImportedSoundStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一份"导入资源"(图标 PNG 或音频 MP3)的内存表示。
 * <p>
 * 存在的理由: 配置 JSON 里只保存引用字符串({@code imported:my_icon} /
 * {@code radialmenu:custom.my_sound}), 真正的字节在<b>导入者的本机</b>
 * {@code config/radialmenu/icons|sounds/} 下。服务端拿到配置文本后,
 * 若不把字节一起带走, 其它玩家的客户端就会既画不出图标也放不出声音。
 * <p>
 * 因此这里把引用的<b>名字</b>与对应的<b>字节</b>绑在一起, 让资源跟着配置一起
 * 上传、一起存到服务端、一起下发 —— 生命周期与配置 JSON 完全一致。
 */
public record RadialAsset(Kind kind, String name, byte[] data) {

    /** 资源类型。名字部分(不含前缀)即磁盘文件名主干。 */
    public enum Kind {
        /** 导入的图标图片, 引用形如 {@code imported:<name>}, 文件 {@code icons/<name>.png}。 */
        ICON("icons", ".png"),
        /** 导入的音频, 引用形如 {@code radialmenu:custom.<name>}, 文件 {@code sounds/<name>.mp3}。 */
        SOUND("sounds", ".mp3");

        private final String dirName;
        private final String extension;

        Kind(String dirName, String extension) {
            this.dirName = dirName;
            this.extension = extension;
        }

        /** 该类型在 {@code config/radialmenu/} 下的子目录名。 */
        public String dirName() {
            return dirName;
        }

        /** 该类型的磁盘文件扩展名。 */
        public String extension() {
            return extension;
        }

        /** 磁盘文件名(名字主干 + 扩展名)。 */
        public String fileName(String name) {
            return name + extension;
        }

        static Kind byOrdinal(int ordinal) {
            Kind[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        }
    }

    /** 单个资源的字节上限, 防止畸形包把服务端内存吃光。 */
    public static final int MAX_ASSET_BYTES = 8 * 1024 * 1024;

    /** 单份配置携带的资源数量上限。 */
    public static final int MAX_ASSETS = 256;

    /**
     * 名字必须是安全的文件主干: 与导入时的清洗规则一致, 否则写盘会变成路径穿越。
     * 只允许小写字母、数字、下划线与连字符。
     */
    private static final String SAFE_NAME_PATTERN = "[a-z0-9_\\-]{1,64}";

    public RadialAsset {
        if (name == null || !name.matches(SAFE_NAME_PATTERN)) {
            throw new IllegalArgumentException("unsafe asset name: " + name);
        }
        if (data == null) {
            throw new IllegalArgumentException("asset data is null: " + name);
        }
        if (data.length > MAX_ASSET_BYTES) {
            throw new IllegalArgumentException("asset too large: " + name + " = " + data.length);
        }
    }

    /** 该资源在配置里被引用时使用的字符串。 */
    public String reference() {
        return kind == Kind.ICON ? ImportedImageStore.PREFIX + name : ImportedSoundStore.PREFIX + name;
    }

    /** 同类型同名视为同一份资源(用于去重)。 */
    public String key() {
        return kind.name() + ":" + name;
    }

    /**
     * 从一份配置里收集所有引用到的导入资源名。
     * <p>
     * 递归覆盖子菜单、开关的两套状态动作, 因为这些槽位同样会显示图标、播放音效;
     * 漏掉任何一支都会表现为"某些槽位在别人机上是空白的"。
     */
    public static List<Ref> collectReferences(com.mingmyal.radialmenu.config.RadialConfig config) {
        Set<Ref> refs = new LinkedHashSet<>();
        if (config != null && config.slots != null) {
            for (com.mingmyal.radialmenu.api.RadialSlot slot : config.slots) {
                collectFromSlot(slot, refs);
            }
        }
        return new ArrayList<>(refs);
    }

    /** 配置中引用到的一份资源(只有类型与名字, 没有字节)。 */
    public record Ref(Kind kind, String name) {
        public String key() {
            return kind.name() + ":" + name;
        }

        public String reference() {
            return kind == Kind.ICON
                    ? ImportedImageStore.PREFIX + name
                    : ImportedSoundStore.PREFIX + name;
        }
    }

    private static void collectFromSlot(
            com.mingmyal.radialmenu.api.RadialSlot slot, Set<Ref> out) {
        if (slot == null) {
            return;
        }

        addIconRef(slot.itemId, out);
        addSoundRef(slot.clickSound, out);

        // 子菜单: 整棵子树都要走
        if (slot.children != null) {
            for (com.mingmyal.radialmenu.api.RadialSlot child : slot.children) {
                collectFromSlot(child, out);
            }
        }

        // 开关类型的两套状态动作(独立槽位对象, 同样可能有图标与音效)
        collectFromSlot(slot.toggleActionOn, out);
        collectFromSlot(slot.toggleActionOff, out);
    }

    private static void addIconRef(String itemId, Set<Ref> out) {
        if (ImportedImageStore.isImportedImage(itemId)) {
            String name = ImportedImageStore.getFileName(itemId);
            if (isSafeName(name)) {
                out.add(new Ref(Kind.ICON, name));
            }
        }
    }

    private static void addSoundRef(String clickSound, Set<Ref> out) {
        if (ImportedSoundStore.isImportedSound(clickSound)) {
            String name = ImportedSoundStore.getName(clickSound);
            // 音频名在引用与文件名两端都会再清洗一次, 这里用同样的规则归一化
            String safe = ImportedSoundStore.sanitizeForFile(name);
            if (isSafeName(safe)) {
                out.add(new Ref(Kind.SOUND, safe));
            }
        }
    }

    private static boolean isSafeName(String name) {
        return name != null && name.matches(SAFE_NAME_PATTERN);
    }
}
