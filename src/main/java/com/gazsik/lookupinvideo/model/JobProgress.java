package com.gazsik.lookupinvideo.model;

public class JobProgress {

    public enum Status { RUNNING, DONE, ERROR }

    private volatile int processed = 0;
    private volatile int total = 0;
    private volatile int framePercent = 0;   // aktuális fájlon belüli %
    private volatile String currentFile = "";
    private volatile Status status = Status.RUNNING;
    private volatile String statusText = "Feldolgozas indul...";
    private volatile String error = null;

    public int getProcessed()     { return processed; }
    public int getTotal()         { return total; }
    public int getFramePercent()  { return framePercent; }
    public String getCurrentFile(){ return currentFile; }
    public Status getStatus()     { return status; }
    public String getStatusText() { return statusText; }
    public String getError()      { return error; }

    /** Összesített %, tartalmazza az aktuális fájlon belüli frame-haladást is. */
    public int getPercent() {
        if (total <= 0) return 0;
        return (int) ((processed * 100L + framePercent) / total);
    }

    public void startFile(int processed, int total, String fileName) {
        this.processed = processed;
        this.total = total;
        this.currentFile = fileName;
        this.framePercent = 0;
        this.statusText = String.format("Feldolgozas: %s  (%d / %d)", fileName, processed + 1, total);
    }

    public void updateFrame(int percent) {
        this.framePercent = percent;
    }

    public void done(int total) {
        this.processed = total;
        this.total = total;
        this.framePercent = 100;
        this.currentFile = "";
        this.status = Status.DONE;
        this.statusText = String.format("Kesz! %d fajl feldolgozva.", total);
    }

    public void error(String message) {
        this.status = Status.ERROR;
        this.error = message;
        this.statusText = "Hiba tortent.";
    }
}
