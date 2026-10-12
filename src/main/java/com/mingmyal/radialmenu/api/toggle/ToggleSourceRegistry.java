package com.mingmyal.radialmenu.api.toggle;

import com.mingmyal.radialmenu.RadialCommon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * 外部模组开关来源的中央注册表。
 * <p>
 * 设计要点:
 * <ul>
 *   <li>发现结果被缓存: {@link #all()} 走缓存, 可在渲染循环里安全调用。</li>
 *   <li>刷新是显式行为({@link #refresh()}), 由选择界面或首次需要时触发,
 *       避免每帧反射扫描所有模组的配置。</li>
 *   <li>单个提供者抛异常不会影响其它提供者, 也不会让整张表为空。</li>
 * </ul>
 */
public final class ToggleSourceRegistry {

    private static final List<ToggleSourceProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** 已发现的开关, 以 id 为键保持稳定顺序。 */
    private static volatile Map<ResourceLocation, ToggleSource> discovered = Map.of();

    /**
     * 已经历过一次自动刷新仍未命中的 id。
     * <p>
     * {@link #resolve} 会在缓存未命中时尝试刷新一次, 而它处于渲染热路径
     * (每帧、每个绑定槽位都会调用)。若某个绑定指向已卸载的模组, 没有这个负缓存
     * 就会每帧触发一次全量扫描(YACL 扫描要遍历模组 jar)。
     */
    private static final Set<String> KNOWN_MISSING = ConcurrentHashMap.newKeySet();

    private static volatile boolean initialized = false;

    /** 是否至少完成过一次发现(即使结果为空), 避免空结果导致反复全量扫描。 */
    private static volatile boolean hasDiscovered = false;

    private ToggleSourceRegistry() {}

    // --- 提供者注册 ---

    public static void registerProvider(ToggleSourceProvider provider) {
        if (provider == null || provider.providerId() == null) return;

        for (ToggleSourceProvider existing : PROVIDERS) {
            if (existing.providerId().equals(provider.providerId())) {
                RadialCommon.LOGGER.warn("Duplicate toggle source provider ignored: {}", provider.providerId());
                return;
            }
        }

        PROVIDERS.add(provider);
        // 新提供者加入后旧缓存不再完整
        invalidate();
    }

    public static List<ToggleSourceProvider> getProviders() {
        return List.copyOf(PROVIDERS);
    }

    /**
     * 注册内置提供者。可在 {@code onInitializeClient} 中安全重复调用。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        for (ToggleSourceProvider provider : BuiltinToggleProviders.create()) {
            registerProvider(provider);
        }
    }

    // --- 发现与查询 ---

    /**
     * 使缓存失效, 下次 {@link #resolve} 或 {@link #ensureDiscovered} 会重新扫描。
     * 注册新提供者时调用。
     */
    private static void invalidate() {
        discovered = Map.of();
        hasDiscovered = false;
        KNOWN_MISSING.clear();
    }

    /**
     * 重新扫描全部提供者并重建缓存。
     * 会忽略不可用的提供者, 且对每个提供者单独捕获异常。
     * <p>
     * 加锁的原因: 后台预热({@link #warmUpAsync()})与界面上手动"重新扫描"
     * 可能并发进入, 两个线程同时构建缓存虽无害但会重复做昂贵的 jar 遍历。
     */
    public static synchronized void refresh() {
        Map<ResourceLocation, ToggleSource> result = new LinkedHashMap<>();

        for (ToggleSourceProvider provider : PROVIDERS) {
            try {
                if (!provider.isAvailable()) continue;

                provider.discover(source -> {
                    if (source == null || source.id() == null) return;
                    // 首个注册者优先, 避免重复 id 相互覆盖
                    result.putIfAbsent(source.id(), source);
                });
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Toggle source provider '{}' failed to discover switches", provider.providerId(), t);
            }
        }

        discovered = Map.copyOf(result);
        hasDiscovered = true;
        // 注意: 这里<b>不能</b>清空 KNOWN_MISSING。
        // resolve() 在未命中时会调用本方法, 若在这里清空, 两个都不存在的 id 会互相
        // 抹掉对方的负缓存, 导致每帧都触发一次全量扫描 —— 正是负缓存要避免的情况。
        // 负缓存只在 invalidate()(注册新提供者)或玩家显式"重新扫描"时清空。
    }

    /**
     * 清空"确认不存在"的负缓存, 供玩家显式"重新扫描"时调用。
     * <p>
     * 与 {@link #refresh()} 分开: 刷新本身由渲染热路径触发, 不能顺带清空负缓存。
     */
    public static synchronized void clearNegativeCache() {
        KNOWN_MISSING.clear();
    }

    /**
     * 在后台线程预先建立缓存。
     * <p>
     * YACL 的发现需要遍历模组 jar 的类文件, 首次扫描可能耗时数百毫秒。
     * 若留到打开轮盘时才做, 会在渲染线程上造成卡顿, 因此这里提前预热。
     * <p>
     * 发现阶段不触碰 OpenGL 或 I18n(提供者已保证), 因此可安全地在非渲染线程执行;
     * 失败不影响后续在主线程正常刷新。
     */
    public static void warmUpAsync() {
        Thread thread = new Thread(() -> {
            try {
                refresh();
            } catch (Throwable t) {
                RadialCommon.LOGGER.warn("Background toggle discovery failed; will retry on demand", t);
            }
        }, "radialmenu-toggle-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 确保缓存已建立。
     * <p>
     * 只在从未发现过时扫描一次: 若确实没有任何可绑定的开关,
     * 反复扫描(尤其 YACL 的 jar 遍历)会带来无谓的开销。
     * 需要强制刷新时使用 {@link #refresh()}。
     */
    public static void ensureDiscovered() {
        if (!hasDiscovered) {
            refresh();
        }
    }

    /** 当前已缓存的全部开关(未排序快照)。 */
    public static Collection<ToggleSource> all() {
        return discovered.values();
    }

    public static boolean isEmpty() {
        return discovered.isEmpty();
    }

    /** 按 id 精确查找; 不存在返回 null。 */
    public static ToggleSource get(ResourceLocation id) {
        return id == null ? null : discovered.get(id);
    }

    /**
     * 按字符串 id 查找; 非法或不存在返回 null。
     */
    public static ToggleSource find(String id) {
        if (id == null || id.isBlank()) return null;

        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed != null ? get(parsed) : null;
    }

    /**
     * 解析槽位绑定的开关。
     * <p>
     * 缓存未命中时会尝试刷新一次: 这样配置文件里保存的绑定在首次打开轮盘时
     * 依然能解析成功, 而不要求玩家先打开选择界面。
     * 失败结果会被负缓存, 避免渲染热路径上反复触发全量扫描。
     */
    public static ToggleSource resolve(String id) {
        if (id == null || id.isBlank()) return null;

        ToggleSource source = find(id);
        if (source != null) return source;

        // 已经扫描过仍找不到: 说明来源确实不存在, 不再重复扫描
        if (KNOWN_MISSING.contains(id)) return null;

        refresh();
        source = find(id);

        if (source == null) {
            KNOWN_MISSING.add(id);
        }
        return source;
    }

    /**
     * 按所属模组分组, 供选择界面使用。
     * <p>
     * 选择界面是以"模组"为主轴的: 玩家通常先想到"我要改某个模组的开关",
     * 而不是"这个开关来自哪种配置库", 因此不按提供者分组。
     */
    public static Map<String, List<ToggleSource>> groupByMod() {
        Map<String, List<ToggleSource>> grouped = new LinkedHashMap<>();
        for (ToggleSource source : all()) {
            grouped.computeIfAbsent(source.modId(), k -> new ArrayList<>()).add(source);
        }

        for (List<ToggleSource> list : grouped.values()) {
            list.sort((a, b) -> a.displayName().getString().compareToIgnoreCase(b.displayName().getString()));
        }
        return grouped;
    }

    /** 某个模组下的全部开关; 不存在时返回空列表。 */
    public static List<ToggleSource> sourcesOfMod(String modId) {
        if (modId == null) return List.of();
        return groupByMod().getOrDefault(modId, List.of());
    }

    /** 该模组标签页的展示名; 取该模组任一开关的 modName, 无开关时退回模组 ID。 */
    public static String modLabel(String modId) {
        for (ToggleSource source : sourcesOfMod(modId)) {
            String name = source.modName().getString();
            if (name != null && !name.isBlank()) return name;
        }
        return modId;
    }

    /** 按提供者(配置库)分组。界面已改为按模组浏览, 此处保留供扩展与排错使用。 */
    public static Map<String, List<ToggleSource>> groupByProvider() {
        Map<String, List<ToggleSource>> grouped = new LinkedHashMap<>();
        List<ToggleSource> sorted = new ArrayList<>(all());
        sorted.sort((a, b) -> {
            int byProvider = a.providerId().compareToIgnoreCase(b.providerId());
            if (byProvider != 0) return byProvider;
            int byMod = a.modId().compareToIgnoreCase(b.modId());
            if (byMod != 0) return byMod;
            return a.displayName().getString().compareToIgnoreCase(b.displayName().getString());
        });

        for (ToggleSource source : sorted) {
            grouped.computeIfAbsent(source.providerId(), k -> new ArrayList<>()).add(source);
        }
        return grouped;
    }

    // --- 配置界面(代理给提供者) ---

    /**
     * 打开指定模组的配置界面。
     *
     * @return 是否成功打开(没有可用界面时返回 false)
     */
    public static boolean openConfigScreen(String modId, Screen parent) {
        if (modId == null || modId.isBlank()) return false;

        for (ToggleSourceProvider provider : PROVIDERS) {
            try {
                if (!provider.isAvailable()) continue;

                Screen screen = provider.createConfigScreen(modId, parent);
                if (screen != null) {
                    Minecraft.getInstance().setScreen(screen);
                    return true;
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to open config screen for '{}' via {}", modId, provider.providerId(), t);
            }
        }
        return false;
    }

    /** 已知能打开配置界面的模组 ID 集合(各提供者并集)。 */
    public static Collection<String> modIdsWithConfigScreens() {
        Set<String> ids = new LinkedHashSet<>();
        for (ToggleSourceProvider provider : PROVIDERS) {
            try {
                if (provider.isAvailable()) {
                    ids.addAll(provider.knownConfigScreenModIds());
                }
            } catch (Throwable ignored) {
                // 查询失败视为无贡献
            }
        }
        ids.removeIf(Objects::isNull);
        return ids;
    }

    /**
     * 是否存在任一可用的、能打开模组配置界面的提供者。
     * <p>
     * "打开模组设置界面"动作据此决定是否出现在动作模式列表中:
     * 不能硬编码具体的配置库(玩家可能只装了 MaLiLib 或只装了 ModMenu),
     * 否则会出现"装了模组却看不到这个动作"的情况。
     */
    public static boolean hasConfigScreenCapability() {
        for (ToggleSourceProvider provider : PROVIDERS) {
            try {
                if (!provider.isAvailable()) continue;

                // 声明了可开界面的模组, 或实现了 createConfigScreen
                if (!provider.knownConfigScreenModIds().isEmpty()) return true;
                if (provider.canOpenConfigScreens()) return true;
            } catch (Throwable ignored) {
                // 单个提供者异常不影响其它提供者的判定
            }
        }
        return false;
    }
}
