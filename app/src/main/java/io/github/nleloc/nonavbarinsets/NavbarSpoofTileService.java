package io.github.nleloc.nonavbarinsets;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * quick settings tile to inspect and toggle navbar insets spoofing
 * for focusing app
 */
public class NavbarSpoofTileService extends TileService {

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Toast mLastToast;

    @Override
    public void onStartListening() {
        super.onStartListening();
        mExecutor.execute(() -> {
            NavbarSpoofIpc.StateResult state = NavbarSpoofIpc.getForegroundState();
            mMainHandler.post(() -> applyStateToTile(state));
        });
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            showToast(getString(R.string.toast_unlock_required));
            return;
        }

        String toastOnTemplate = getString(R.string.toast_toggled_on);
        String toastOffTemplate = getString(R.string.toast_toggled_off);
        String toastNoApp = getString(R.string.toast_no_app);

        mExecutor.execute(() -> {
            NavbarSpoofIpc.ToggleResult result = NavbarSpoofIpc.toggleForeground(
                    toastOnTemplate, toastOffTemplate, toastNoApp);
            mMainHandler.post(() -> handleToggleResult(result));
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void applyStateToTile(NavbarSpoofIpc.StateResult state) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setLabel(getString(R.string.tile_label));
        if (!state.hookActive) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.tile_subtitle_inactive));
        } else if (state.packageName == null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(getString(R.string.tile_subtitle_no_app));
        } else {
            tile.setState(state.enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            String display = state.appLabel != null ? state.appLabel : state.packageName;
            tile.setSubtitle(display);
        }
        tile.updateTile();
    }

    private void handleToggleResult(NavbarSpoofIpc.ToggleResult result) {
        Tile tile = getQsTile();
        if (!result.hookActive) {
            if (tile != null) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setSubtitle(getString(R.string.tile_subtitle_inactive));
                tile.updateTile();
            }
            showToast(getString(R.string.toast_hook_inactive));
            return;
        }

        if (!result.success || result.packageName == null) {
            if (tile != null) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.tile_subtitle_no_app));
                tile.updateTile();
            }
            if (!result.toastShown) {
                showToast(getString(R.string.toast_no_app));
            }
            return;
        }

        String display = result.appLabel != null ? result.appLabel : result.packageName;
        if (tile != null) {
            tile.setState(result.newEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(display);
            tile.updateTile();
        }

        if (!result.toastShown) {
            String msg = getString(
                    result.newEnabled ? R.string.toast_toggled_on : R.string.toast_toggled_off,
                    display);
            showToast(msg);
        }
    }

    private void showToast(String message) {
        if (mLastToast != null) {
            mLastToast.cancel();
        }
        Toast toast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT);
        mLastToast = toast;
        toast.show();
    }
}
