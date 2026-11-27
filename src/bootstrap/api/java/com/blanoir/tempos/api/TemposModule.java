package com.blanoir.tempos.api;

/** Complete lifecycle contract for an independently loaded feature JAR. */
public interface TemposModule {
    void load(ModuleContext context) throws Exception;

    void enable() throws Exception;

    void disable() throws Exception;

    void unload() throws Exception;
}
