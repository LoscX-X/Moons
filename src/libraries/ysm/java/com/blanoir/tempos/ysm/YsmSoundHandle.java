package com.blanoir.tempos.ysm;

/** A locally owned playback instance, usable by any host audio backend. */
public interface YsmSoundHandle extends AutoCloseable {
    boolean stopped();

    @Override
    void close();
}
