package com.otto.cyclea.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Makes Cyclea's config screen reachable from Mod Menu's mod list, in addition
 * to the in-game config key.
 */
public class CycleaModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new CycleaConfigScreen();
    }
}
