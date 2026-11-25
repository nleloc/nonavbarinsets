package io.github.nleloc.nonavbarinsets;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * installs hooks inside system_server via libxposed API 101+:
 * 1. WMS constructors + addWindow/relayoutWindow -> captures live WMS,
 *    initializes ConfigStore, and strips client-bound InsetsState even if AOT inlined
 *    WindowState.fillInsetsState() / getCompatInsetsState()
 *
 * 2. WindowState.getCompatInsetsState() (A11 fallback: getInsetsState()) + fillInsetsState()
 *    + notifyInsetsControlChanged()/reportResized() + ClientTransaction items -> strips navbar
 *    insets across all WMS->client dispatch paths (including Android 15-17 / heavy modified OEM like 1+ with ColorOS 17)
 *
 * 3. ActivityManagerService.onTransact() -> private IPC endpoint for NavbarSpoofTileService
 *    to query/toggle the foreground app behind the notification shade
 */
final class SystemServerHook {

    private static final String LOG_TAG = ConfigStore.LOG_TAG;

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final ConfigStore mConfigStore;
    private final InsetsStripper mStripper;

    private volatile Object mWms;
    private volatile Handler mUiHandler;
    private Toast mLastToast;
    private Field mWmsGlobalLockField;
    private Field mWmsWindowMapField;
    private Field mWmsRootField;

    private Field mAttrsField;
    private Field mRemovedField;
    private Field mViewVisibilityField;
    private Field mLastReportedInsetsStateField;
    private Method mNotifyInsetsChanged;

    private Class<?> mRelayoutResultClass;
    private Field mRelayoutResultInsetsField;

    private final ThreadLocal<String> mActiveDispatchPkg = new ThreadLocal<>();
    private final AtomicBoolean mLoggedInsetsError = new AtomicBoolean(false);
    private final Set<String> mLoggedStripSites = ConcurrentHashMap.newKeySet();

    SystemServerHook(XposedInterface xposed, ClassLoader classLoader) {
        mXposed = xposed;
        mClassLoader = classLoader;
        mConfigStore = new ConfigStore(xposed);
        mStripper = new InsetsStripper(xposed, classLoader);
    }

    void install() {
        if (!mStripper.isReady()) {
            mXposed.log(Log.ERROR, LOG_TAG, "aborting install: InsetsStripper not ready");
            return;
        }

        mConfigStore.setChangeListener(changedPackages -> {
            Object wms = mWms;
            if (wms != null) {
                int count = notifyWindowsForPackages(wms, changedPackages);
                mXposed.log(Log.INFO, LOG_TAG,
                        "live-notified " + count + " window(s) for " + changedPackages);
            }
        });

        hookWindowManagerService();
        hookWindowState();
        hookClientTransactionItems();
        hookActivityManagerService();
    }

    private void hookWindowManagerService() {
        Class<?> wmsClass = findClassOrNull("com.android.server.wm.WindowManagerService");
        if (wmsClass == null) {
            mXposed.log(Log.ERROR, LOG_TAG, "WindowManagerService class not found");
            return;
        }

        mWmsGlobalLockField = findFieldInHierarchy(wmsClass, "mGlobalLock");
        mWmsWindowMapField = findFieldInHierarchy(wmsClass, "mWindowMap");
        mWmsRootField = findFieldInHierarchy(wmsClass, "mRoot");

        mRelayoutResultClass = findClassOrNull("android.view.WindowRelayoutResult");
        if (mRelayoutResultClass != null) {
            mRelayoutResultInsetsField = findFieldInHierarchy(mRelayoutResultClass, "insetsState");
        }

        hookAllConstructors(wmsClass, chain -> {
            Object result = chain.proceed();
            mWms = chain.getThisObject();
            mConfigStore.ensureInitialized();
            return result;
        });

        // Strip outInsetsState returned by addWindow / relayoutWindow in case ART AOT
        // inlined WindowState.fillInsetsState() / getCompatInsetsState() into WMS
        XposedInterface.Hooker wmsOutInsetsHook = chain -> {
            Object result = chain.proceed();
            if (mConfigStore.isEmpty()) return result;
            try {
                List<Object> args = chain.getArgs();
                String pkg = findTargetPackageForWmsCall(chain.getThisObject(), args);
                if (pkg == null) return result;
                Object outInsets = findOutInsetsStateFromArgs(args);
                if (outInsets != null && mStripper.stripNavigationBarInPlace(outInsets)) {
                    logFirstStrip(chain.getExecutable().getName(), pkg);
                }
            } catch (Throwable t) {
                logInsetsError(t);
            }
            return result;
        };

        int addHooks = hookAllMethods(wmsClass, "addWindow", wmsOutInsetsHook);
        int relayoutHooks = hookAllMethods(wmsClass, "relayoutWindow", wmsOutInsetsHook);
        int deoptCount = deoptimizeMethods(wmsClass, "addWindow", "relayoutWindow", "relayoutWindowInner");
        mXposed.log(Log.INFO, LOG_TAG, "hooked WMS addWindow(" + addHooks
                + "), relayoutWindow(" + relayoutHooks + "), deopt=" + deoptCount);
    }

    private void hookWindowState() {
        Class<?> windowStateClass = findClassOrNull("com.android.server.wm.WindowState");
        if (windowStateClass == null) {
            mXposed.log(Log.ERROR, LOG_TAG, "WindowState class not found");
            return;
        }

        mAttrsField = findFieldInHierarchy(windowStateClass, "mAttrs");
        if (mAttrsField == null) {
            mXposed.log(Log.ERROR, LOG_TAG, "WindowState.mAttrs field not found");
            return;
        }
        mRemovedField = findFieldInHierarchy(windowStateClass, "mRemoved");
        mViewVisibilityField = findFieldInHierarchy(windowStateClass, "mViewVisibility");
        mLastReportedInsetsStateField = findFieldInHierarchy(
                windowStateClass, "mLastReportedInsetsState");
        mNotifyInsetsChanged = findMethodInHierarchy(windowStateClass, "notifyInsetsChanged");

        //  WindowState.getCompatInsetsState() (A12-17+) or getInsetsState() (A11 fallback).
        Method target = findMethodInHierarchy(windowStateClass, "getCompatInsetsState");
        if (target == null) {
            target = findMethodInHierarchy(windowStateClass, "getInsetsState");
        }
        if (target != null) {
            final String methodName = target.getName();
            mXposed.hook(target).intercept(chain -> {
                Object original = chain.proceed();
                if (original == null || mConfigStore.isEmpty()) return original;
                try {
                    String pkg = getTargetPackageForWindow(chain.getThisObject());
                    if (pkg == null) return original;
                    Object stripped = mStripper.stripNavigationBar(original);
                    if (stripped != null && stripped != original) {
                        logFirstStrip("WindowState." + methodName, pkg);
                        return stripped;
                    }
                } catch (Throwable t) {
                    logInsetsError(t);
                }
                return original;
            });
            mXposed.log(Log.INFO, LOG_TAG, "hooked WindowState." + methodName + "()");
        } else {
            mXposed.log(Log.WARN, LOG_TAG, "WindowState insets getter not found");
        }

        //  WindowState.fillInsetsState(...) (Android 15-17+): catches cases where
        //  getCompatInsetsState() is inlined or OEM code modifies outInsetsState after it
        int fillHooks = hookAllMethods(windowStateClass, "fillInsetsState", chain -> {
            Object result = chain.proceed();
            if (mConfigStore.isEmpty()) return result;
            try {
                Object win = chain.getThisObject();
                String pkg = getTargetPackageForWindow(win);
                if (pkg == null) return result;
                boolean stripped = false;
                List<Object> args = chain.getArgs();
                for (int i = 0; i < args.size(); i++) {
                    Object arg = args.get(i);
                    if (mStripper.isInsetsState(arg)) {
                        stripped |= mStripper.stripNavigationBarInPlace(arg);
                    }
                }
                if (mLastReportedInsetsStateField != null) {
                    Object lastReported = mLastReportedInsetsStateField.get(win);
                    if (mStripper.isInsetsState(lastReported)) {
                        stripped |= mStripper.stripNavigationBarInPlace(lastReported);
                    }
                }
                if (stripped) {
                    logFirstStrip("WindowState.fillInsetsState", pkg);
                }
            } catch (Throwable t) {
                logInsetsError(t);
            }
            return result;
        });

        //  Track active WindowState during notifyInsetsControlChanged / reportResized
        //  so ClientTransaction item hooks can still strip InsetsState even if both
        //  fillInsetsState() and getCompatInsetsState() were transitively AOT-inlined
        XposedInterface.Hooker dispatchScopeHook = chain -> {
            if (mConfigStore.isEmpty()) {
                return chain.proceed();
            }
            String pkg = null;
            try {
                pkg = getTargetPackageForWindow(chain.getThisObject());
            } catch (Throwable ignored) {
            }
            if (pkg == null) {
                return chain.proceed();
            }
            String prev = mActiveDispatchPkg.get();
            mActiveDispatchPkg.set(pkg);
            try {
                return chain.proceed();
            } finally {
                if (prev != null) {
                    mActiveDispatchPkg.set(prev);
                } else {
                    mActiveDispatchPkg.remove();
                }
            }
        };

        int controlHooks = hookAllMethods(
                windowStateClass, "notifyInsetsControlChanged", dispatchScopeHook);
        int resizeHooks = hookAllMethods(
                windowStateClass, "reportResized", dispatchScopeHook);

        int deoptCount = deoptimizeMethods(windowStateClass,
                "fillInsetsState", "notifyInsetsControlChanged", "reportResized");

        mXposed.log(Log.INFO, LOG_TAG, "hooked WindowState fillInsetsState(" + fillHooks
                + "), notifyInsetsControlChanged(" + controlHooks
                + "), reportResized(" + resizeHooks + "), deopt=" + deoptCount);
    }

    private void hookClientTransactionItems() {
        String[] itemClasses = {
                "android.app.servertransaction.WindowStateInsetsControlChangeItem",
                "android.app.servertransaction.WindowStateResizeItem"
        };

        XposedInterface.Hooker itemHook = chain -> {
            String pkg = mActiveDispatchPkg.get();
            if (pkg == null) {
                return chain.proceed();
            }
            Object[] modifiedArgs = null;
            try {
                List<Object> args = chain.getArgs();
                for (int i = 0; i < args.size(); i++) {
                    Object arg = args.get(i);
                    if (mStripper.isInsetsState(arg)) {
                        Object stripped = mStripper.stripNavigationBar(arg);
                        if (stripped != null && stripped != arg) {
                            if (modifiedArgs == null) {
                                modifiedArgs = args.toArray();
                            }
                            modifiedArgs[i] = stripped;
                            logFirstStrip(chain.getExecutable().getDeclaringClass().getSimpleName(), pkg);
                        }
                    }
                }
            } catch (Throwable t) {
                logInsetsError(t);
            }
            return modifiedArgs != null ? chain.proceed(modifiedArgs) : chain.proceed();
        };

        for (String className : itemClasses) {
            Class<?> cls = findClassOrNull(className);
            if (cls == null) continue;
            int ctors = hookAllConstructors(cls, itemHook);
            int obtains = hookAllMethods(cls, "obtain", itemHook);
            mXposed.log(Log.INFO, LOG_TAG, "hooked " + cls.getSimpleName()
                    + " ctors(" + ctors + "), obtain(" + obtains + ")");
        }
    }

    private void hookActivityManagerService() {
        Class<?> amsClass = findClassOrNull("com.android.server.am.ActivityManagerService");
        if (amsClass == null) {
            mXposed.log(Log.ERROR, LOG_TAG, "ActivityManagerService class not found");
            return;
        }
        Method onTransact = findMethodInHierarchy(
                amsClass, "onTransact", int.class, Parcel.class, Parcel.class, int.class);
        if (onTransact == null) {
            mXposed.log(Log.ERROR, LOG_TAG, "ActivityManagerService.onTransact not found");
            return;
        }

        mXposed.hook(onTransact).intercept(chain -> {
            int code = (int) chain.getArg(0);
            if (code != NavbarSpoofIpc.TRANSACTION_CODE) {
                return chain.proceed();
            }

            Object ams = chain.getThisObject();
            int callingUid = Binder.getCallingUid();
            if (!isCallerAuthorized(ams, callingUid)) {
                // fall through to stock AMS.onTransact so unauthorized callers
                // cannot probe or detect the endpoint.
                return chain.proceed();
            }

            Parcel data = (Parcel) chain.getArg(1);
            Parcel reply = (Parcel) chain.getArg(2);
            handleIpc(ams, callingUid, data, reply);
            return true;
        });
        mXposed.log(Log.INFO, LOG_TAG, "hooked ActivityManagerService.onTransact()");
    }

    private String getTargetPackageForWindow(Object windowState) throws IllegalAccessException {
        if (windowState == null || mAttrsField == null) return null;
        WindowManager.LayoutParams attrs =
                (WindowManager.LayoutParams) mAttrsField.get(windowState);
        return getTargetPackageFromAttrs(attrs);
    }

    private String findTargetPackageForWmsCall(Object wms, List<Object> args)
            throws IllegalAccessException {
        if (args == null) return null;
        boolean sawAttrs = false;
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof WindowManager.LayoutParams) {
                sawAttrs = true;
                return getTargetPackageFromAttrs((WindowManager.LayoutParams) arg);
            }
        }
        // relayoutWindow often passes attrs == null when LayoutParams are unchanged,
        // resolve the WindowState from WMS.mWindowMap via the IWindow client argument
        if (!sawAttrs && wms != null && mWmsGlobalLockField != null && mWmsWindowMapField != null) {
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg instanceof IInterface) {
                    IBinder binder = ((IInterface) arg).asBinder();
                    if (binder == null) continue;
                    Object globalLock = mWmsGlobalLockField.get(wms);
                    if (globalLock == null) return null;
                    synchronized (globalLock) {
                        Object mapObj = mWmsWindowMapField.get(wms);
                        if (mapObj instanceof Map) {
                            Object win = ((Map<?, ?>) mapObj).get(binder);
                            if (win != null) {
                                return getTargetPackageForWindow(win);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private String getTargetPackageFromAttrs(WindowManager.LayoutParams attrs) {
        if (attrs == null || attrs.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
            return null;
        }
        String pkg = attrs.packageName;
        if (isProtectedPackage(pkg) || !mConfigStore.isEnabled(pkg)) {
            return null;
        }
        return pkg;
    }

    private Object findOutInsetsStateFromArgs(List<Object> args) throws IllegalAccessException {
        if (args == null) return null;
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg == null) continue;
            if (mStripper.isInsetsState(arg)) {
                return arg;
            }
            if (mRelayoutResultClass != null && mRelayoutResultInsetsField != null
                    && mRelayoutResultClass.isInstance(arg)) {
                Object state = mRelayoutResultInsetsField.get(arg);
                if (mStripper.isInsetsState(state)) {
                    return state;
                }
            }
        }
        return null;
    }

    private int hookAllConstructors(Class<?> cls, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (!ctor.isSynthetic()) {
                mXposed.hook(ctor).intercept(hooker);
                count++;
            }
        }
        return count;
    }

    private int hookAllMethods(Class<?> cls, String name, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (name.equals(m.getName())
                    && !Modifier.isAbstract(m.getModifiers())
                    && !m.isBridge()) {
                mXposed.hook(m).intercept(hooker);
                count++;
            }
        }
        return count;
    }

    private int deoptimizeMethods(Class<?> cls, String... names) {
        int count = 0;
        for (String name : names) {
            for (Method m : cls.getDeclaredMethods()) {
                if (name.equals(m.getName()) && !Modifier.isAbstract(m.getModifiers())) {
                    try {
                        if (mXposed.deoptimize(m)) {
                            count++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return count;
    }

    private void logFirstStrip(String site, String pkg) {
        if (mLoggedStripSites.add(site + ":" + pkg)) {
            mXposed.log(Log.INFO, LOG_TAG, "stripped nav insets via " + site + " for " + pkg);
        }
    }

    private void logInsetsError(Throwable t) {
        if (mLoggedInsetsError.compareAndSet(false, true)) {
            mXposed.log(Log.ERROR, LOG_TAG, "error stripping insets", t);
        }
    }

    private void handleIpc(Object ams, int callingUid, Parcel data, Parcel reply) {
        long identity = Binder.clearCallingIdentity();
        try {
            data.enforceInterface(NavbarSpoofIpc.DESCRIPTOR);
            int action = data.readInt();

            mConfigStore.ensureInitialized();
            Object wms = resolveWms(ams);
            Context context = getAmsContext(ams);

            if (action == NavbarSpoofIpc.ACTION_GET_FOREGROUND_STATE) {
                String pkg = findForegroundPackage(wms);
                String label = resolveAppLabel(context, pkg);
                boolean enabled = mConfigStore.isEnabled(pkg);

                reply.writeNoException();
                reply.writeString(pkg);
                reply.writeString(label);
                reply.writeInt(enabled ? 1 : 0);
                reply.writeInt(mConfigStore.count());
            } else if (action == NavbarSpoofIpc.ACTION_TOGGLE_FOREGROUND) {
                String toastOnTemplate = data.dataAvail() > 0 ? data.readString() : null;
                String toastOffTemplate = data.dataAvail() > 0 ? data.readString() : null;
                String toastNoApp = data.dataAvail() > 0 ? data.readString() : null;

                // ensure the module app can display its own Toast
                // without being suppressed by Android 13+ notification restrictions
                boolean appCanToast = ensureModuleNotificationsEnabled(context, callingUid);

                String pkg = findForegroundPackage(wms);
                if (pkg == null) {
                    boolean toastShown = !appCanToast && showSystemToast(ams, toastNoApp);
                    reply.writeNoException();
                    reply.writeInt(0); // success = false
                    reply.writeString(null);
                    reply.writeString(null);
                    reply.writeInt(0);
                    reply.writeInt(0);
                    reply.writeString(null);
                    reply.writeInt(toastShown ? 1 : 0);
                    return;
                }

                String label = resolveAppLabel(context, pkg);
                // persist outside WMS lock to avoid disk I/O under mGlobalLock.
                boolean newEnabled = mConfigStore.toggle(pkg);
                int notified = notifyWindowsForPackages(wms, Collections.singleton(pkg));

                collapseStatusBarPanels(context);

                boolean toastShown = false;
                if (!appCanToast) {
                    String display = (label != null && !label.isEmpty()) ? label : pkg;
                    String template = newEnabled ? toastOnTemplate : toastOffTemplate;
                    toastShown = showSystemToast(ams, formatToast(template, display));
                }

                mXposed.log(Log.INFO, LOG_TAG, "QS toggled " + pkg + " -> "
                        + (newEnabled ? "ON" : "OFF") + " (notified " + notified + " windows)");

                reply.writeNoException();
                reply.writeInt(1); // success = true
                reply.writeString(pkg);
                reply.writeString(label);
                reply.writeInt(newEnabled ? 1 : 0);
                reply.writeInt(notified);
                reply.writeString(null);
                reply.writeInt(toastShown ? 1 : 0);
            } else {
                reply.writeException(new IllegalArgumentException("Unknown action: " + action));
            }
        } catch (Throwable t) {
            mXposed.log(Log.ERROR, LOG_TAG, "IPC error", t);
            if (reply != null) {
                reply.setDataPosition(0);
                reply.writeException(new IllegalStateException(t.getMessage()));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void collapseStatusBarPanels(Context context) {
        try {
            if (context != null) {
                Object statusBar = context.getSystemService("statusbar");
                if (statusBar != null) {
                    Method collapse = findMethodInHierarchy(statusBar.getClass(), "collapsePanels");
                    if (collapse != null) {
                        collapse.invoke(statusBar);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            IBinder binder = getSystemServiceBinder("statusbar");
            if (binder == null) return;
            Class<?> stubClass = findClassOrNull("com.android.internal.statusbar.IStatusBarService$Stub");
            if (stubClass == null) return;
            Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
            Object service = asInterface.invoke(null, binder);
            if (service == null) return;
            Method collapse = findMethodInHierarchy(service.getClass(), "collapsePanels");
            if (collapse != null) {
                collapse.invoke(service);
            }
        } catch (Throwable t) {
            mXposed.log(Log.WARN, LOG_TAG, "collapsePanels failed", t);
        }
    }

    private boolean ensureModuleNotificationsEnabled(Context context, int callingUid) {
        if (callingUid < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        try {
            IBinder binder = getSystemServiceBinder("notification");
            if (binder == null) return false;
            Class<?> stubClass = findClassOrNull("android.app.INotificationManager$Stub");
            if (stubClass == null) return false;
            Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
            Object nm = asInterface.invoke(null, binder);
            if (nm == null) return false;

            Method areEnabled = findMethodInHierarchy(
                    nm.getClass(), "areNotificationsEnabledForPackage", String.class, int.class);
            if (areEnabled == null) return false;

            if (Boolean.TRUE.equals(areEnabled.invoke(nm, NavbarSpoofIpc.MODULE_PACKAGE, callingUid))) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= 33 && context != null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    if (pm != null) {
                        Method grant = findMethodInHierarchy(pm.getClass(),
                                "grantRuntimePermission", String.class, String.class, UserHandle.class);
                        if (grant != null) {
                            grant.invoke(pm, NavbarSpoofIpc.MODULE_PACKAGE,
                                    "android.permission.POST_NOTIFICATIONS",
                                    UserHandle.getUserHandleForUid(callingUid));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            Method setEnabled = findMethodInHierarchy(
                    nm.getClass(), "setNotificationsEnabledForPackage",
                    String.class, int.class, boolean.class);
            if (setEnabled != null) {
                setEnabled.invoke(nm, NavbarSpoofIpc.MODULE_PACKAGE, callingUid, true);
            }

            return Boolean.TRUE.equals(
                    areEnabled.invoke(nm, NavbarSpoofIpc.MODULE_PACKAGE, callingUid));
        } catch (Throwable t) {
            mXposed.log(Log.WARN, LOG_TAG, "ensureModuleNotificationsEnabled failed", t);
            return false;
        }
    }

    private static String formatToast(String template, String display) {
        if (template == null || template.isEmpty()) return null;
        try {
            return String.format(template, display);
        } catch (Throwable ignored) {
            return template;
        }
    }

    private IBinder getSystemServiceBinder(String serviceName) {
        try {
            Class<?> sm = findClassOrNull("android.os.ServiceManager");
            if (sm == null) return null;
            Method getService = sm.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, serviceName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean showSystemToast(Object ams, String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        Context uiContext = resolveUiContext(ams);
        Handler handler = resolveUiHandler();
        if (uiContext == null || handler == null) {
            return false;
        }
        return handler.post(() -> {
            try {
                if (mLastToast != null) {
                    mLastToast.cancel();
                }
                Toast toast = Toast.makeText(uiContext, message, Toast.LENGTH_SHORT);
                mLastToast = toast;
                toast.show();
            } catch (Throwable t) {
                mXposed.log(Log.WARN, LOG_TAG, "failed to show system toast", t);
            }
        });
    }

    private Context resolveUiContext(Object ams) {
        try {
            Object uiCtx = getFieldValue(ams, "mUiContext");
            if (uiCtx instanceof Context) {
                return (Context) uiCtx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> atClass = findClassOrNull("android.app.ActivityThread");
            if (atClass != null) {
                Method curMethod = findMethodInHierarchy(atClass, "currentActivityThread");
                Object at = curMethod != null ? curMethod.invoke(null) : null;
                Object uiCtx = invokeNoArgIfPresent(at, "getSystemUiContext");
                if (uiCtx instanceof Context) {
                    return (Context) uiCtx;
                }
            }
        } catch (Throwable ignored) {
        }
        return getAmsContext(ams);
    }

    private Handler resolveUiHandler() {
        Handler handler = mUiHandler;
        if (handler != null) return handler;
        try {
            Class<?> uiThreadClass = findClassOrNull("com.android.server.UiThread");
            if (uiThreadClass != null) {
                Method getHandler = findMethodInHierarchy(uiThreadClass, "getHandler");
                if (getHandler != null) {
                    Object h = getHandler.invoke(null);
                    if (h instanceof Handler) {
                        mUiHandler = (Handler) h;
                        return mUiHandler;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null) {
            handler = new Handler(mainLooper);
            mUiHandler = handler;
        }
        return handler;
    }

    private Object resolveWms(Object ams) {
        Object wms = mWms;
        if (wms != null) return wms;
        try {
            wms = getFieldValue(ams, "mWindowManager");
            if (wms != null) mWms = wms;
        } catch (Throwable ignored) {
        }
        return wms;
    }

    private Context getAmsContext(Object ams) {
        try {
            return (Context) getFieldValue(ams, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isCallerAuthorized(Object ams, int callingUid) {
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID) {
            return true;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Context ctx = getAmsContext(ams);
            if (ctx == null) return false;
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) return false;
            String[] pkgs = pm.getPackagesForUid(callingUid);
            if (pkgs == null) return false;
            for (String pkg : pkgs) {
                if (NavbarSpoofIpc.MODULE_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Finds the foreground app package underneath the notification shade.
     * Uses DisplayContent.mFocusedApp first (not stolen by NotificationShade),
     * with a top-down visible app-window scan fallback.
     */
    private String findForegroundPackage(Object wms) {
        if (wms == null) return null;
        try {
            Object globalLock = getWmsGlobalLock(wms);
            if (globalLock == null) return null;
            synchronized (globalLock) {
                Object root = getWmsRoot(wms);
                if (root == null) return null;

                Object dc = invokeNoArgIfPresent(root, "getTopFocusedDisplayContent");
                if (dc == null) {
                    dc = invokeNoArgIfPresent(root, "getDefaultDisplay");
                }
                if (dc == null) return null;

                // 1. DisplayContent.mFocusedApp -> ActivityRecord.packageName
                try {
                    Object focusedApp = getFieldValue(dc, "mFocusedApp");
                    if (focusedApp != null) {
                        String pkg = (String) getFieldValue(focusedApp, "packageName");
                        if (!isProtectedPackage(pkg)) {
                            return pkg;
                        }
                    }
                } catch (Throwable ignored) {
                }

                // 2. Fallback: scan top-down for first visible application window
                String[] found = {null};
                Consumer<Object> visitor = win -> {
                    if (found[0] != null) return;
                    try {
                        if (mRemovedField != null && mRemovedField.getBoolean(win)) return;
                        if (mViewVisibilityField != null
                                && mViewVisibilityField.getInt(win) != View.VISIBLE) {
                            return;
                        }
                        WindowManager.LayoutParams attrs =
                                (WindowManager.LayoutParams) mAttrsField.get(win);
                        if (attrs == null) return;
                        if (attrs.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                                || attrs.type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
                            return;
                        }
                        if (!isProtectedPackage(attrs.packageName)) {
                            found[0] = attrs.packageName;
                        }
                    } catch (Throwable ignored) {
                    }
                };
                invokeForAllWindows(dc, visitor);
                return found[0];
            }
        } catch (Throwable t) {
            mXposed.log(Log.ERROR, LOG_TAG, "findForegroundPackage failed", t);
            return null;
        }
    }

    private int notifyWindowsForPackages(Object wms, Set<String> targetPackages) {
        if (wms == null || targetPackages == null || targetPackages.isEmpty()
                || mNotifyInsetsChanged == null || mAttrsField == null) {
            return 0;
        }
        try {
            Object globalLock = getWmsGlobalLock(wms);
            if (globalLock == null) return 0;
            synchronized (globalLock) {
                Object root = getWmsRoot(wms);
                if (root == null) return 0;

                int[] count = {0};
                Consumer<Object> visitor = win -> {
                    try {
                        if (mRemovedField != null && mRemovedField.getBoolean(win)) return;
                        WindowManager.LayoutParams attrs =
                                (WindowManager.LayoutParams) mAttrsField.get(win);
                        if (attrs == null
                                || attrs.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
                            return;
                        }
                        if (attrs.packageName != null && targetPackages.contains(attrs.packageName)) {
                            mNotifyInsetsChanged.invoke(win);
                            count[0]++;
                        }
                    } catch (Throwable ignored) {
                    }
                };
                invokeForAllWindows(root, visitor);
                return count[0];
            }
        } catch (Throwable t) {
            mXposed.log(Log.ERROR, LOG_TAG, "notifyWindowsForPackages failed", t);
            return 0;
        }
    }

    private Object getWmsGlobalLock(Object wms) throws IllegalAccessException {
        return mWmsGlobalLockField != null
                ? mWmsGlobalLockField.get(wms)
                : getFieldValue(wms, "mGlobalLock");
    }

    private Object getWmsRoot(Object wms) throws IllegalAccessException {
        return mWmsRootField != null
                ? mWmsRootField.get(wms)
                : getFieldValue(wms, "mRoot");
    }

    private Class<?> findClassOrNull(String name) {
        try {
            return Class.forName(name, false, mClassLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object getFieldValue(Object target, String name) throws IllegalAccessException {
        if (target == null) return null;
        Field f = findFieldInHierarchy(target.getClass(), name);
        return f != null ? f.get(target) : null;
    }

    private static Object invokeNoArgIfPresent(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = findMethodInHierarchy(target.getClass(), methodName);
            return m != null ? m.invoke(target) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeForAllWindows(Object container, Consumer<Object> visitor)
            throws Exception {
        Method m = findMethodInHierarchy(
                container.getClass(), "forAllWindows", Consumer.class, boolean.class);
        if (m != null) {
            m.invoke(container, visitor, true);
        }
    }

    private static String resolveAppLabel(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return packageName;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : packageName;
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    private static boolean isProtectedPackage(String pkg) {
        return pkg == null
                || pkg.isEmpty()
                || "android".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || NavbarSpoofIpc.MODULE_PACKAGE.equals(pkg);
    }
}
