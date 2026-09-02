package com.blanoir.moons.client.module.impl.misc.antibot;

enum AntiBotMode {
    CUSTOM("custom"),
    MATRIX("matrix"),
    INTAVE_HEAVY("intave_heavy"),
    HORIZON("horizon");

    private final String configName;

    AntiBotMode(String configName) {
        this.configName = configName;
    }

    String configName() {
        return configName;
    }

}
