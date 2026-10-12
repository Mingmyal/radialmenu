package com.mingmyal.radialmenu.api.toggle;

import com.mingmyal.radialmenu.integration.toggle.ClothAutoConfigToggleProvider;
import com.mingmyal.radialmenu.integration.toggle.MaLiLibConfigScreenProvider;
import com.mingmyal.radialmenu.integration.toggle.MaLiLibToggleProvider;
import com.mingmyal.radialmenu.integration.toggle.MezzConfigToggleProvider;
import com.mingmyal.radialmenu.integration.toggle.ModMenuConfigProvider;
import com.mingmyal.radialmenu.integration.toggle.YaclToggleProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置开关来源提供者的集中构造点。
 * <p>
 * 构造本身不做任何反射, 因此即使对应的配置库没有安装也可以安全实例化;
 * 是否可用由各提供者的 {@link ToggleSourceProvider#isAvailable()} 在发现阶段判断。
 */
public final class BuiltinToggleProviders {

    private BuiltinToggleProviders() {}

    public static List<ToggleSourceProvider> create() {
        List<ToggleSourceProvider> providers = new ArrayList<>();

        providers.add(new MaLiLibToggleProvider());
        providers.add(new ClothAutoConfigToggleProvider());
        providers.add(new YaclToggleProvider());
        // mezz_config: JEI 等模组使用, 提供公开枚举 API
        providers.add(new MezzConfigToggleProvider());
        // 以下仅提供配置界面, 不贡献开关
        providers.add(new MaLiLibConfigScreenProvider());
        providers.add(new ModMenuConfigProvider());

        return providers;
    }
}
