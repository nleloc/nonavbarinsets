package io.github.nleloc.nonavbarinsets;

import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.WindowInsets;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * strips navigation bar (and overlapping navbar tappableElement) frames from
 * android.view.InsetsState so client windows compute Insets.NONE without ever
 * mutating shared server-side InsetsState or InsetsSource instances
 *
 * supports Android 14+ (dynamic InsetsSource types) and Android 11-13 (ITYPE_*)
 */
final class InsetsStripper {

    private static final String LOG_TAG = ConfigStore.LOG_TAG;

    private final boolean mReady;
    private final boolean mUseDynamicSources; // Android 14+ (API 34+)

    private Class<?> mInsetsStateClass;
    private Constructor<?> mCopyCtor;
    private Constructor<?> mSourceCopyCtor;
    private Method mAddSource;
    private Method mSetFrame;
    private Method mSetVisibleFrame;
    private Method mGetFrame;

    // Android 14+ (API 34+)
    private Method mSourceSize;
    private Method mSourceAt;
    private Method mSourceGetType;

    // Android 11-13 (API 30-33)
    private Method mPeekSource;
    private int[] mLegacyTargetTypes;

    InsetsStripper(XposedInterface xposed, ClassLoader classLoader) {
        boolean ready = false;
        boolean dynamic = false;
        try {
            mInsetsStateClass = Class.forName("android.view.InsetsState", false, classLoader);
            Class<?> insetsSourceClass = Class.forName("android.view.InsetsSource", false, classLoader);

            mCopyCtor = mInsetsStateClass.getDeclaredConstructor(mInsetsStateClass, boolean.class);
            mCopyCtor.setAccessible(true);

            mSourceCopyCtor = insetsSourceClass.getDeclaredConstructor(insetsSourceClass);
            mSourceCopyCtor.setAccessible(true);

            mAddSource = mInsetsStateClass.getDeclaredMethod("addSource", insetsSourceClass);
            mAddSource.setAccessible(true);

            mGetFrame = insetsSourceClass.getDeclaredMethod("getFrame");
            mGetFrame.setAccessible(true);

            mSetFrame = insetsSourceClass.getDeclaredMethod("setFrame",
                    int.class, int.class, int.class, int.class);
            mSetFrame.setAccessible(true);

            mSetVisibleFrame = insetsSourceClass.getDeclaredMethod("setVisibleFrame", Rect.class);
            mSetVisibleFrame.setAccessible(true);

            if (hasMethod(mInsetsStateClass, "sourceSize")
                    && hasMethod(mInsetsStateClass, "sourceAt", int.class)) {
                mSourceSize = mInsetsStateClass.getDeclaredMethod("sourceSize");
                mSourceSize.setAccessible(true);
                mSourceAt = mInsetsStateClass.getDeclaredMethod("sourceAt", int.class);
                mSourceAt.setAccessible(true);
                mSourceGetType = insetsSourceClass.getDeclaredMethod("getType");
                mSourceGetType.setAccessible(true);
                dynamic = true;
                ready = true;
            } else {
                mPeekSource = mInsetsStateClass.getDeclaredMethod("peekSource", int.class);
                mPeekSource.setAccessible(true);
                mLegacyTargetTypes = resolveLegacyTypes(mInsetsStateClass);
                dynamic = false;
                ready = mLegacyTargetTypes.length > 0;
            }
            xposed.log(Log.INFO, LOG_TAG, "InsetsStripper ready (dynamic=" + dynamic
                    + ", sdk=" + Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            xposed.log(Log.ERROR, LOG_TAG, "InsetsStripper init failed", t);
        }
        mReady = ready;
        mUseDynamicSources = dynamic;
    }

    boolean isReady() {
        return mReady;
    }

    boolean isInsetsState(Object obj) {
        return obj != null && mInsetsStateClass != null && mInsetsStateClass.isInstance(obj);
    }

    /**
     * returns a deep-copied InsetsState with navigation bar frames zeroed, or the
     * original object untouched if there is nothing to strip.
     */
    Object stripNavigationBar(Object originalState) throws Exception {
        if (!mReady || originalState == null) return originalState;
        return mUseDynamicSources
                ? stripDynamicCopy(originalState)
                : stripLegacyCopy(originalState);
    }

    /**
     * replaces navigation bar InsetsSource entries inside a dedicated client-bound
     * output InsetsState (such as outInsetsState / mLastReportedInsetsState) using
     * freshly cloned InsetsSource copies so shared server sources are never mutated,
     * while preserving mSeq and any OEM-specific fields on targetState
     *
     * @return true if at least one non-empty navbar/tappable source was stripped
     */
    boolean stripNavigationBarInPlace(Object targetState) throws Exception {
        if (!mReady || targetState == null) return false;
        return mUseDynamicSources
                ? stripDynamicInPlace(targetState)
                : stripLegacyInPlace(targetState);
    }

    private Object stripDynamicCopy(Object originalState) throws Exception {
        List<Rect> navFrames = collectNonEmptyNavFrames(originalState);
        if (navFrames == null) {
            return originalState;
        }

        int navType = WindowInsets.Type.navigationBars();
        int tapType = WindowInsets.Type.tappableElement();
        Object copy = mCopyCtor.newInstance(originalState, true);
        int copyCount = (int) mSourceSize.invoke(copy);
        for (int i = 0; i < copyCount; i++) {
            Object src = mSourceAt.invoke(copy, i);
            if (src == null) continue;
            int type = (int) mSourceGetType.invoke(src);
            if (type == navType) {
                zeroSource(src);
            } else if (type == tapType) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty() && intersectsAny(frame, navFrames)) {
                    zeroSource(src);
                }
            }
        }
        return copy;
    }

    private boolean stripDynamicInPlace(Object targetState) throws Exception {
        List<Rect> navFrames = collectNonEmptyNavFrames(targetState);
        if (navFrames == null) {
            return false;
        }

        int navType = WindowInsets.Type.navigationBars();
        int tapType = WindowInsets.Type.tappableElement();
        int count = (int) mSourceSize.invoke(targetState);
        List<Object> replacements = new ArrayList<>(2);
        for (int i = 0; i < count; i++) {
            Object src = mSourceAt.invoke(targetState, i);
            if (src == null) continue;
            int type = (int) mSourceGetType.invoke(src);
            if (type == navType) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty()) {
                    Object cloned = mSourceCopyCtor.newInstance(src);
                    zeroSource(cloned);
                    replacements.add(cloned);
                }
            } else if (type == tapType) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty() && intersectsAny(frame, navFrames)) {
                    Object cloned = mSourceCopyCtor.newInstance(src);
                    zeroSource(cloned);
                    replacements.add(cloned);
                }
            }
        }
        for (int i = 0; i < replacements.size(); i++) {
            mAddSource.invoke(targetState, replacements.get(i));
        }
        return !replacements.isEmpty();
    }

    private List<Rect> collectNonEmptyNavFrames(Object state) throws Exception {
        int navType = WindowInsets.Type.navigationBars();
        int count = (int) mSourceSize.invoke(state);
        List<Rect> navFrames = null;
        for (int i = 0; i < count; i++) {
            Object src = mSourceAt.invoke(state, i);
            if (src == null) continue;
            int type = (int) mSourceGetType.invoke(src);
            if (type == navType) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty()) {
                    if (navFrames == null) navFrames = new ArrayList<>(2);
                    navFrames.add(new Rect(frame));
                }
            }
        }
        return navFrames;
    }

    private Object stripLegacyCopy(Object originalState) throws Exception {
        if (!hasNonEmptyLegacySource(originalState)) {
            return originalState;
        }
        Object copy = mCopyCtor.newInstance(originalState, true);
        for (int itype : mLegacyTargetTypes) {
            Object src = mPeekSource.invoke(copy, itype);
            if (src != null) {
                zeroSource(src);
            }
        }
        return copy;
    }

    private boolean stripLegacyInPlace(Object targetState) throws Exception {
        if (!hasNonEmptyLegacySource(targetState)) {
            return false;
        }
        boolean stripped = false;
        for (int itype : mLegacyTargetTypes) {
            Object src = mPeekSource.invoke(targetState, itype);
            if (src != null) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty()) {
                    Object cloned = mSourceCopyCtor.newInstance(src);
                    zeroSource(cloned);
                    mAddSource.invoke(targetState, cloned);
                    stripped = true;
                }
            }
        }
        return stripped;
    }

    private boolean hasNonEmptyLegacySource(Object state) throws Exception {
        for (int itype : mLegacyTargetTypes) {
            Object src = mPeekSource.invoke(state, itype);
            if (src != null) {
                Rect frame = (Rect) mGetFrame.invoke(src);
                if (frame != null && !frame.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void zeroSource(Object insetsSource) throws Exception {
        mSetFrame.invoke(insetsSource, 0, 0, 0, 0);
        mSetVisibleFrame.invoke(insetsSource, new Object[]{null});
    }

    private static boolean intersectsAny(Rect rect, List<Rect> targets) {
        for (int i = 0; i < targets.size(); i++) {
            if (Rect.intersects(rect, targets.get(i))) return true;
        }
        return false;
    }

    private static boolean hasMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            cls.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static int[] resolveLegacyTypes(Class<?> insetsStateClass) {
        // Deliberately excludes ITYPE_TOP_TAPPABLE_ELEMENT (status bar).
        String[] names = {
                "ITYPE_NAVIGATION_BAR",
                "ITYPE_EXTRA_NAVIGATION_BAR",
                "ITYPE_LOCAL_NAVIGATION_BAR_1",
                "ITYPE_LOCAL_NAVIGATION_BAR_2",
                "ITYPE_BOTTOM_TAPPABLE_ELEMENT",
                "ITYPE_LEFT_TAPPABLE_ELEMENT",
                "ITYPE_RIGHT_TAPPABLE_ELEMENT"
        };
        List<Integer> found = new ArrayList<>(names.length);
        for (String name : names) {
            try {
                Field f = insetsStateClass.getDeclaredField(name);
                f.setAccessible(true);
                found.add(f.getInt(null));
            } catch (Throwable ignored) {
            }
        }
        int[] out = new int[found.size()];
        for (int i = 0; i < found.size(); i++) {
            out[i] = found.get(i);
        }
        return out;
    }
}
