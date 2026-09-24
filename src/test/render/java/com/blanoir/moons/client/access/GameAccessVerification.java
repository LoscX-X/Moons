package com.blanoir.moons.client.access;

/** Resolves private game members before a live client starts using the feature host. */
public final class GameAccessVerification {
    private GameAccessVerification() {}

    public static void main(String[] arguments) throws Exception {
        Class.forName(GameAccess.class.getName(), true, GameAccess.class.getClassLoader());
        System.out.println("MOONS_GAME_ACCESS_VERIFIED private-members=resolved");
    }
}
