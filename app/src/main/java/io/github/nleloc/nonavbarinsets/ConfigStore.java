package io.github.nleloc.nonavbarinsets;

import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import io.github.libxposed.api.XposedInterface;

/**
 * Persists the spoofed package list in /data/system/nonavbarinsets/list.txt
 * and watches the directory so config file edits can reload automatically
 */
final class ConfigStore {

    static final String LOG_TAG = "nonavbarinsets";
    static final String CONFIG_DIR_PATH = "/data/system/nonavbarinsets";
    static final String CONFIG_FILE_NAME = "list.txt";

    private static final String HEADER =
            "# NoNavbarInsets config\n"
                    + "# one package name per line, please\n";

    private static final long RELOAD_DEBOUNCE_MS = 150L;

    interface ChangeListener {
        void onPackagesChanged(Set<String> changedPackages);
    }

    private final XposedInterface mXposed;
    private final File mConfigDir = new File(CONFIG_DIR_PATH);
    private final File mConfigFile = new File(mConfigDir, CONFIG_FILE_NAME);
    private final Object mIoLock = new Object();

    // immutable snapshot for lock-free reads on WMS hot path.
    private volatile Set<String> mPackages = Collections.emptySet();
    private volatile boolean mInitialized = false;
    private volatile ChangeListener mListener;

    private HandlerThread mObserverThread;
    private Handler mObserverHandler;
    private FileObserver mFileObserver;
    private final Runnable mReloadRunnable = this::reloadFromDiskAndNotify;

    ConfigStore(XposedInterface xposed) {
        mXposed = xposed;
    }

    void setChangeListener(ChangeListener listener) {
        mListener = listener;
    }

    void ensureInitialized() {
        if (mInitialized) return;
        synchronized (mIoLock) {
            if (mInitialized) return;
            try {
                ensureConfigDirLocked();
                if (!mConfigFile.exists()) {
                    writePackagesLocked(Collections.emptySet());
                }
                mPackages = readPackagesLocked();
                startObserverLocked();
                mInitialized = true;
                mXposed.log(Log.INFO, LOG_TAG, "config initialized (" + mPackages.size()
                        + " packages) at " + mConfigFile.getAbsolutePath());
            } catch (Throwable t) {
                mXposed.log(Log.ERROR, LOG_TAG, "config init failed", t);
            }
        }
    }

    boolean isEmpty() {
        return mPackages.isEmpty();
    }

    boolean isEnabled(String packageName) {
        return packageName != null && mPackages.contains(packageName);
    }

    int count() {
        return mPackages.size();
    }

    /**
     * toggles a package and atomically persists the updated list
     *
     * @return the new enabled state for {@code packageName}
     */
    boolean toggle(String packageName) {
        ensureInitialized();
        synchronized (mIoLock) {
            Set<String> updated = new TreeSet<>(mPackages);
            boolean nowEnabled;
            if (updated.contains(packageName)) {
                updated.remove(packageName);
                nowEnabled = false;
            } else {
                updated.add(packageName);
                nowEnabled = true;
            }
            mPackages = Collections.unmodifiableSet(new HashSet<>(updated));
            try {
                ensureConfigDirLocked();
                writePackagesLocked(updated);
            } catch (Throwable t) {
                mXposed.log(Log.ERROR, LOG_TAG, "failed to save config", t);
            }
            return nowEnabled;
        }
    }

    private void reloadFromDiskAndNotify() {
        Set<String> changed = Collections.emptySet();
        synchronized (mIoLock) {
            try {
                Set<String> oldSet = mPackages;
                Set<String> newSet = readPackagesLocked();
                if (!oldSet.equals(newSet)) {
                    mPackages = newSet;
                    Set<String> diff = new HashSet<>(oldSet);
                    for (String pkg : newSet) {
                        if (!diff.remove(pkg)) {
                            diff.add(pkg);
                        }
                    }
                    changed = diff;
                    mXposed.log(Log.INFO, LOG_TAG, "reloaded config (" + newSet.size()
                            + " packages, changed: " + changed + ")");
                }
            } catch (Throwable t) {
                mXposed.log(Log.WARN, LOG_TAG,
                        "reload config failed (check if config file permission is 0644 and owner is system)", t);
            }
        }

        ChangeListener listener = mListener;
        if (listener != null && !changed.isEmpty()) {
            try {
                listener.onPackagesChanged(changed);
            } catch (Throwable t) {
                mXposed.log(Log.ERROR, LOG_TAG, "change listener error", t);
            }
        }
    }

    private void ensureConfigDirLocked() {
        if (!mConfigDir.exists()) {
            mConfigDir.mkdirs();
        }
        try {
            // 0700 system:system prevents untrusted apps from traversing into the directory.
            Os.chmod(mConfigDir.getAbsolutePath(), 0700);
        } catch (Throwable t) {
            mXposed.log(Log.WARN, LOG_TAG, "chmod dir failed", t);
        }
    }

    private Set<String> readPackagesLocked() throws Exception {
        if (!mConfigFile.exists()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(mConfigFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private void writePackagesLocked(Set<String> packages) throws Exception {
        File tmpFile = new File(mConfigDir, CONFIG_FILE_NAME + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            writer.write(HEADER);
            for (String pkg : new TreeSet<>(packages)) {
                writer.write(pkg);
                writer.newLine();
            }
        }
        // correct permission
        try {
            Os.chmod(tmpFile.getAbsolutePath(), 0644);
        } catch (Throwable ignored) {
        }
        if (!tmpFile.renameTo(mConfigFile)) {
            if (!mConfigFile.delete() || !tmpFile.renameTo(mConfigFile)) {
                throw new IllegalStateException("renameTo failed for " + mConfigFile);
            }
        }
    }

    private void startObserverLocked() {
        if (mFileObserver != null) return;
        mObserverThread = new HandlerThread("NavbarSpoofConfig");
        mObserverThread.start();
        mObserverHandler = new Handler(mObserverThread.getLooper());

        int mask = FileObserver.CLOSE_WRITE
                | FileObserver.MOVED_TO
                | FileObserver.CREATE
                | FileObserver.DELETE;
        mFileObserver = new FileObserver(mConfigDir, mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && !CONFIG_FILE_NAME.equals(path)) return;
                Handler h = mObserverHandler;
                if (h != null) {
                    h.removeCallbacks(mReloadRunnable);
                    h.postDelayed(mReloadRunnable, RELOAD_DEBOUNCE_MS);
                }
            }
        };
        mFileObserver.startWatching();
    }
}
