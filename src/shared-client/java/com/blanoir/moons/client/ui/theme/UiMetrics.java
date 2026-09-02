package com.blanoir.moons.client.ui.theme;

/** A compact spacing and sizing scale used by every native UI control. */
public record UiMetrics(
        float scale,
        int radius,
        int controlRadius,
        int rowHeight,
        int rowGap,
        int contentPadding,
        int columnPadding,
        int toggleWidth,
        int toggleHeight,
        double hoverResponse,
        double activeResponse
) {
}
