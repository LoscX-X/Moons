package com.blanoir.tempos.api;

import java.nio.file.Path;

/** Stable services exposed to a hot-loadable feature module. */
public interface ModuleContext {
    String moduleId();

    Path dataDirectory();

    ResourceScope resources();

    <T> T service(Class<T> type);
}
