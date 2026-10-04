package com.blanoir.moons.client.compat.input;

public record InputSnapshot(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean shift,
        boolean sprint) {}
