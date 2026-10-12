package com.mingmyal.radialmenu.integration;

import com.mingmyal.radialmenu.config.RadialConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
                return RadialConfigScreen.create(parent);
            }
            // Instead of returning null, return the parent (the ModMenu screen)
            // so it stays on the current screen instead of doing nothing.
            return parent;
        };
    }
}
