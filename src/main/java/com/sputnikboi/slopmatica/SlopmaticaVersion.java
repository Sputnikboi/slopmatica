package com.sputnikboi.slopmatica;

import net.fabricmc.loader.api.FabricLoader;

/** This mod's version, from fabric.mod.json. */
public final class SlopmaticaVersion {
    private SlopmaticaVersion() {
    }

    public static String get() {
        return FabricLoader.getInstance().getModContainer("slopmatica").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }
}
