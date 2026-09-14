package com.blanoir.moons.ysm.internal;

/** Logging bridge for the extracted upstream sources; has no loader lifecycle. */
public final class YesSteveModel {
    public static final Log LOGGER = new Log();

    public static final class Log {
        private void log(System.Logger.Level level, String message, Object... args) {
            for (Object arg : args)
                message =
                        message.replaceFirst(
                                "\\{\\}",
                                java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
            System.getLogger("moons.ysm").log(level, message);
        }

        public void debug(String text, Object... args) {
            log(System.Logger.Level.DEBUG, text, args);
        }

        public void info(String text, Object... args) {
            log(System.Logger.Level.INFO, text, args);
        }

        public void warn(String text, Object... args) {
            log(System.Logger.Level.WARNING, text, args);
        }

        public void error(String text, Object... args) {
            log(System.Logger.Level.ERROR, text, args);
        }
    }
}
