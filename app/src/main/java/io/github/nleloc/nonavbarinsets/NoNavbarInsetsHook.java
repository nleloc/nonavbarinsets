package io.github.nleloc.nonavbarinsets;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

public class NoNavbarInsetsHook extends XposedModule {

    private final AtomicBoolean mInstalled = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            log(Log.INFO, ConfigStore.LOG_TAG, "loaded in system_server on "
                    + getFrameworkName() + " " + getFrameworkVersion()
                    + " (API " + getApiVersion() + ")");
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!mInstalled.compareAndSet(false, true)) return;

        try {
            new SystemServerHook(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            log(Log.ERROR, ConfigStore.LOG_TAG, "failed to install system_server hooks", t);
        }
    }
}
