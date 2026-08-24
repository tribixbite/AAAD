package com.legs.appsforaa.carify;

import android.app.ActivityManager;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The template car surface compiled into the AndroidX runtime payload.
 *
 * Carify declares this service for ordinary phone-app clones. An isolated S25U measurement proved
 * that application category participates in custom-app discovery. The original game experiment
 * was parked-only, so production clones use maps with this navigation service. The service is both
 * the discovery entry point and the runtime bridge to the cloned Activity.
 *
 * Android Auto gives a navigation Car App a Surface. This service backs that Surface with a
 * public, own-content VirtualDisplay and launches the clone's ordinary launcher Activity onto it.
 * The Activity and this service are in the same cloned APK and therefore share a UID, so pointer
 * events stay inside the application boundary; no root, Shizuku, or INJECT_EVENTS permission is
 * required.
 */
public final class CarifyCarAppService extends CarAppService {
    private static final String TAG = "AAAD/CarifyBridge";

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // A Carify clone is a local, re-signed test artifact. Its purpose is to run against the
        // user's installed host, including DHU builds that are not in the production allowlist.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new MirrorSession();
    }

    private static final class MirrorSession extends Session {
        @NonNull
        @Override
        public Screen onCreateScreen(@NonNull Intent intent) {
            return new MirrorScreen(getCarContext());
        }
    }

    private static final class MirrorScreen extends Screen implements SurfaceCallback {
        private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor();
        private final Instrumentation instrumentation = new Instrumentation();
        private VirtualDisplay virtualDisplay;
        private volatile int displayId = Display.INVALID_DISPLAY;
        private int width;
        private int height;

        MirrorScreen(CarContext carContext) {
            super(carContext);
            getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
                if (event == Lifecycle.Event.ON_CREATE) {
                    carContext.getCarService(AppManager.class).setSurfaceCallback(this);
                } else if (event == Lifecycle.Event.ON_DESTROY) {
                    carContext.getCarService(AppManager.class).setSurfaceCallback(null);
                    releaseDisplay();
                    inputExecutor.shutdownNow();
                }
            });
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            // PAN enables touch delivery to SurfaceCallback on hosts that gate map gestures behind
            // pan mode. The phone Activity itself is the entire visual surface.
            ActionStrip mapActions = new ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build();
            return new NavigationTemplate.Builder()
                    .setMapActionStrip(mapActions)
                    .setPanModeListener(inPanMode -> { })
                    .build();
        }

        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
            Surface surface = container.getSurface();
            if (!surface.isValid()) {
                show("Android Auto supplied an invalid surface");
                return;
            }
            if (Build.VERSION.SDK_INT < 26) {
                show("Carify needs Android 8.0 or newer");
                return;
            }

            releaseDisplay();
            width = container.getWidth();
            height = container.getHeight();
            int dpi = Math.max(container.getDpi(), 120);
            DisplayManager manager = (DisplayManager) getCarContext()
                    .getSystemService(Context.DISPLAY_SERVICE);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            virtualDisplay = manager.createVirtualDisplay(
                    "Carify " + getCarContext().getPackageName(),
                    width,
                    height,
                    dpi,
                    surface,
                    flags
            );
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                releaseDisplay();
                show("Could not create the car display");
                return;
            }
            displayId = virtualDisplay.getDisplay().getDisplayId();
            launchCloneActivity();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
            releaseDisplay();
        }

        @Override
        public void onClick(float x, float y) {
            final int targetDisplay = displayId;
            if (targetDisplay == Display.INVALID_DISPLAY) return;
            inputExecutor.execute(() -> injectTap(targetDisplay, x, y));
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
            final int targetDisplay = displayId;
            if (targetDisplay == Display.INVALID_DISPLAY) return;
            float startX = width / 2f;
            float startY = height / 2f;
            float endX = clamp(startX - distanceX, 1, width - 1);
            float endY = clamp(startY - distanceY, 1, height - 1);
            inputExecutor.execute(() -> injectSwipe(targetDisplay, startX, startY, endX, endY));
        }

        @Override public void onVisibleAreaChanged(@NonNull Rect visibleArea) { }
        @Override public void onStableAreaChanged(@NonNull Rect stableArea) { }

        private void launchCloneActivity() {
            PackageManager pm = getCarContext().getPackageManager();
            Intent launcher = pm.getLaunchIntentForPackage(getCarContext().getPackageName());
            if (launcher == null || launcher.getComponent() == null) {
                show("This clone has no launcher Activity");
                return;
            }
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
            if (Build.VERSION.SDK_INT >= 29) {
                ActivityManager activityManager = (ActivityManager) getCarContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                if (!activityManager.isActivityStartAllowedOnDisplay(
                        getCarContext(), displayId, launcher)) {
                    show("Android refused the cloned app display");
                    return;
                }
            }
            try {
                getCarContext().startActivity(launcher, options.toBundle());
                Log.i(TAG, "Launched " + launcher.getComponent() + " on display " + displayId);
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not launch cloned Activity on display " + displayId, error);
                show("Could not open the cloned app: " + error.getClass().getSimpleName());
            }
        }

        private void injectTap(int targetDisplay, float x, float y) {
            if (targetDisplay != displayId) return;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = null;
            MotionEvent up = null;
            try {
                down = event(now, now, MotionEvent.ACTION_DOWN, x, y, targetDisplay);
                up = event(now, now + 40, MotionEvent.ACTION_UP, x, y, targetDisplay);
                instrumentation.sendPointerSync(down);
                instrumentation.sendPointerSync(up);
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not inject tap", error);
            } finally {
                if (down != null) down.recycle();
                if (up != null) up.recycle();
            }
        }

        private void injectSwipe(
                int targetDisplay,
                float startX,
                float startY,
                float endX,
                float endY
        ) {
            if (targetDisplay != displayId) return;
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = null;
            MotionEvent up = null;
            try {
                down = event(
                        downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, targetDisplay);
                instrumentation.sendPointerSync(down);
                for (int step = 1; step <= 5; step++) {
                    float t = step / 5f;
                    MotionEvent move = event(
                            downTime,
                            downTime + step * 16L,
                            MotionEvent.ACTION_MOVE,
                            startX + (endX - startX) * t,
                            startY + (endY - startY) * t,
                            targetDisplay
                    );
                    try {
                        instrumentation.sendPointerSync(move);
                    } finally {
                        move.recycle();
                    }
                }
                up = event(
                        downTime, downTime + 96, MotionEvent.ACTION_UP, endX, endY, targetDisplay);
                instrumentation.sendPointerSync(up);
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not inject swipe", error);
            } finally {
                if (down != null) down.recycle();
                if (up != null) up.recycle();
            }
        }

        @SuppressLint("BlockedPrivateApi")
        private static MotionEvent event(
                long downTime,
                long eventTime,
                int action,
                float x,
                float y,
                int displayId
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
                properties.id = 0;
                properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
                MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
                coords.x = x;
                coords.y = y;
                coords.pressure = 1;
                coords.size = 1;
                return MotionEvent.obtain(
                        downTime,
                        eventTime,
                        action,
                        1,
                        new MotionEvent.PointerProperties[]{properties},
                        new MotionEvent.PointerCoords[]{coords},
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        displayId,
                        0,
                        MotionEvent.CLASSIFICATION_NONE
                );
            }

            MotionEvent legacy = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            legacy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            // Display-aware obtain() became public in API 34. Android 10–13 still carry the
            // underlying InputEvent method, so use it reflectively on those releases.
            try {
                java.lang.reflect.Method setter = Class.forName("android.view.InputEvent")
                        .getDeclaredMethod("setDisplayId", int.class);
                setter.setAccessible(true);
                setter.invoke(legacy, displayId);
            } catch (ReflectiveOperationException | RuntimeException error) {
                legacy.recycle();
                Log.e(TAG, "Could not target input at display " + displayId, error);
                // Never fall back to display 0: that could tap an unrelated phone window.
                throw new IllegalStateException("Display-aware input is unavailable", error);
            }
            return legacy;
        }

        private void releaseDisplay() {
            displayId = Display.INVALID_DISPLAY;
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        }

        private void show(String message) {
            Log.e(TAG, message);
            CarToast.makeText(getCarContext(), message, CarToast.LENGTH_LONG).show();
        }

        private static float clamp(float value, float low, float high) {
            return Math.max(low, Math.min(high, value));
        }
    }
}
