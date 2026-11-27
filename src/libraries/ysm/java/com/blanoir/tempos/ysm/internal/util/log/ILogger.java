package com.blanoir.tempos.ysm.internal.util.log;

public interface ILogger {
    void logFormatted(String text, Object... args);

    void logComponent(String text);
}
