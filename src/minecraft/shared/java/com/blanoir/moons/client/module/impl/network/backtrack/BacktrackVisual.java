package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.utils.render.ArgbColors;

/** Keep even old opaque ESP colors readable during close combat. */
final class BacktrackVisual {
    private BacktrackVisual() {}

    static int fill(int argb) {
        return ArgbColors.withAlpha(argb, Math.min(argb >>> 24, 20));
    }

    static int outline(int argb) {
        return ArgbColors.withAlpha(argb, Math.min(argb >>> 24, 190));
    }
}
