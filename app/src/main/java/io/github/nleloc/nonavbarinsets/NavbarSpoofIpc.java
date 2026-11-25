package io.github.nleloc.nonavbarinsets;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * lightweight Binder IPC contract between NavbarSpoofTileService (app process)
 * and SystemServerHook (ActivityManagerService.onTransact in system_server).
 */
final class NavbarSpoofIpc {

    static final String MODULE_PACKAGE = "io.github.nleloc.nonavbarinsets";
    static final String DESCRIPTOR = MODULE_PACKAGE + ".INavbarSpoof";

    // Safely inside [FIRST_CALL_TRANSACTION, LAST_CALL_TRANSACTION] and far above IActivityManager codes.
    static final int TRANSACTION_CODE = 0x004E4247; // 'NBG'

    static final int ACTION_GET_FOREGROUND_STATE = 1;
    static final int ACTION_TOGGLE_FOREGROUND = 2;

    static final class StateResult {
        final boolean hookActive;
        final String packageName;
        final String appLabel;
        final boolean enabled;
        final int totalCount;

        StateResult(boolean hookActive, String packageName, String appLabel,
                    boolean enabled, int totalCount) {
            this.hookActive = hookActive;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.enabled = enabled;
            this.totalCount = totalCount;
        }

        static StateResult inactive() {
            return new StateResult(false, null, null, false, 0);
        }
    }

    static final class ToggleResult {
        final boolean hookActive;
        final boolean success;
        final String packageName;
        final String appLabel;
        final boolean newEnabled;
        final int notifiedWindows;
        final boolean toastShown;

        ToggleResult(boolean hookActive, boolean success, String packageName, String appLabel,
                     boolean newEnabled, int notifiedWindows, boolean toastShown) {
            this.hookActive = hookActive;
            this.success = success;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.newEnabled = newEnabled;
            this.notifiedWindows = notifiedWindows;
            this.toastShown = toastShown;
        }

        static ToggleResult inactive() {
            return new ToggleResult(false, false, null, null, false, 0, false);
        }
    }

    private NavbarSpoofIpc() {
    }

    static StateResult getForegroundState() {
        IBinder ams = getActivityBinder();
        if (ams == null) return StateResult.inactive();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(ACTION_GET_FOREGROUND_STATE);
            if (!ams.transact(TRANSACTION_CODE, data, reply, 0)) {
                return StateResult.inactive();
            }
            reply.readException();
            String pkg = reply.readString();
            String label = reply.readString();
            boolean enabled = reply.readInt() != 0;
            int totalCount = reply.readInt();
            return new StateResult(true, pkg, label, enabled, totalCount);
        } catch (Throwable ignored) {
            return StateResult.inactive();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static ToggleResult toggleForeground(
            String toastOnTemplate, String toastOffTemplate, String toastNoApp) {
        IBinder ams = getActivityBinder();
        if (ams == null) return ToggleResult.inactive();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(ACTION_TOGGLE_FOREGROUND);
            data.writeString(toastOnTemplate);
            data.writeString(toastOffTemplate);
            data.writeString(toastNoApp);
            if (!ams.transact(TRANSACTION_CODE, data, reply, 0)) {
                return ToggleResult.inactive();
            }
            reply.readException();
            boolean success = reply.readInt() != 0;
            String pkg = reply.readString();
            String label = reply.readString();
            boolean newEnabled = reply.readInt() != 0;
            int notified = reply.readInt();
            reply.readString(); // reserved legacy error slot
            boolean toastShown = reply.dataAvail() > 0 && reply.readInt() != 0;
            return new ToggleResult(true, success, pkg, label, newEnabled, notified, toastShown);
        } catch (Throwable ignored) {
            return ToggleResult.inactive();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @SuppressLint("PrivateApi")
    private static IBinder getActivityBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, "activity");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
