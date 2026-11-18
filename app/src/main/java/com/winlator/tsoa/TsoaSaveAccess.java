package com.winlator.tsoa;

import java.io.IOException;

/** Mutual exclusion between the dedicated game's lifetime and Chronos changes. */
public final class TsoaSaveAccess {
    private static boolean running;
    private static boolean working;

    private TsoaSaveAccess() {}

    public static synchronized boolean startGame() {
        if (running || working) return false;
        running = true;
        return true;
    }

    public static synchronized void endGame() { running = false; }
    public static synchronized boolean gameRunning() { return running; }

    static synchronized void beginMaintenance() throws IOException {
        if (running || working) throw new IOException("Game or save operation active");
        working = true;
    }

    static synchronized void endMaintenance() { working = false; }
}
