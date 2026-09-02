package com.blanoir.moons.client.ui.theme;

/** Immutable visual contract consumed by renderers, never by module logic. */
public record UiTheme(UiPalette palette, UiMetrics metrics) {
}
