package com.blanoir.tempos.client.profile;

/** Detached response from Mojang's profile endpoints. */
public record MojangProfile(String id, String name) {}
