package rip.ysm.imagestream.internal;

public class LogWriter {
    public static boolean writeLog = false;

    public static void writeLog(String msg) {
        if(!writeLog) return;
        System.err.println(msg);
    }

    public static void error(Throwable t, String msg) {
        if(!writeLog) return;
        System.err.println("[ERROR] " + msg);
        t.printStackTrace();
    }
}
