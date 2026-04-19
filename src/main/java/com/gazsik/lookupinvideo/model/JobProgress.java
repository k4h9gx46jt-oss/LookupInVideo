package com.gazsik.lookupinvideo.model;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class JobProgress {

    public enum Status { RUNNING, DONE, ERROR }

    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger matchesFound = new AtomicInteger(0);
    private volatile int total = 0;
    private volatile int framePercent = 0;
    private volatile String currentFile = "";
    /** Parhuzamos mod: fajlnev -> frame-szazu szazalek (0-100) */
    private final ConcurrentHashMap<String, Integer> fileProgress = new ConcurrentHashMap<>();
    private volatile Status status = Status.RUNNING;
    private volatile String statusText = "Feldolgozas indul...";
    private volatile String error = null;
    private volatile boolean parallelMode = false;
    private volatile int threadCount = 1;

    public int getMatchesFound()        { return matchesFound.get(); }
    public int getProcessed()            { return processed.get(); }
    public Map<String, Integer> getFileProgress() { return Collections.unmodifiableMap(fileProgress); }
    public int getTotal()          { return total; }
    public int getFramePercent()   { return parallelMode ? 0 : framePercent; }
    public String getCurrentFile() { return currentFile; }
    public Status getStatus()      { return status; }
    public String getStatusText()  { return statusText; }
    public String getError()       { return error; }
    public boolean isParallelMode(){ return parallelMode; }
    public int getThreadCount()    { return threadCount; }

    /** Osszetett % — parhuzamos modban fajlszintu, szekvencialis modban frame-szintu is. */
    public int getPercent() {
        if (total <= 0) return 0;
        if (parallelMode) {
            return Math.min(100, processed.get() * 100 / total);
        }
        return Math.min(100, (int) ((processed.get() * 100L + framePercent) / total));
    }

    /** Szekvencialis mod: adott fajl megkezdesekor hivando. */
    public void startFile(int fileIndex, int total, String fileName) {
        this.processed.set(fileIndex);
        this.total = total;
        this.currentFile = fileName;
        this.framePercent = 0;
        this.statusText = String.format("Feldolgozas: %s  (%d / %d)", fileName, fileIndex + 1, total);
    }

    /** Parhuzamos mod: a thread pool inditasa elott hivando. */
    public void startParallel(int total, int threadCount) {
        this.total = total;
        this.parallelMode = true;
        this.threadCount = threadCount;
        this.statusText = String.format("%d fajl feldolgozasa indul, %d szallon parhuzamosan...", total, threadCount);
    }

    /** Parhuzamos mod: thread-safe, minden fajl befejezesekor hivando. */
    public int fileCompleted(String fileName, int matchCount) {
        fileProgress.remove(fileName);
        matchesFound.addAndGet(matchCount);
        int done = processed.incrementAndGet();
        int m = matchesFound.get();
        this.statusText = String.format("%d / %d fajl kesz  |  %d talalat", done, total, m);
        return done;
    }

    /** Parhuzamos mod: adott fajl field-szintu progress regisztralas, 0% kezdeti ertekkel. */
    public void startFileTracking(String fileName) {
        if (parallelMode) fileProgress.put(fileName, 0);
    }

    /**
     * Frame-szintu progress frissitese.
     * Parhuzamos modban: fajlonkenti map frissitese.
     * Szekvencialis modban: a globalis framePercent frissitese.
     */
    public void updateFileFrame(String fileName, int percent) {
        if (parallelMode) {
            fileProgress.put(fileName, percent);
        } else {
            int bounded = Math.max(0, Math.min(100, percent));
            this.framePercent = bounded;
            this.statusText = String.format("Feldolgozas: %s  (%d / %d) - %d%%", currentFile, processed.get() + 1, total, bounded);
        }
    }

    public void done(int total) {
        this.processed.set(total);
        this.total = total;
        this.framePercent = 0;
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
