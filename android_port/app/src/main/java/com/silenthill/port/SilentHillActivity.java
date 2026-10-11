package com.silenthill.port;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SilentHillActivity extends SDLActivity {

    private static final String TAG = "SilentHill";

    @Override
    protected String[] getLibraries() {
        // c++_shared has to be loaded before anything that links the STL, and
        // libmain.so is the whole game (pc_port builds as a shared object on
        // Android instead of an executable).
        return new String[] {
            "c++_shared",
            "SDL2",
            "main"
        };
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        // Before super.onCreate, which is what starts SDL and therefore
        // SDL_main. main() chdir()s to this same directory expecting the data
        // to already be in place.
        publishDataRoot();
        unpackBundledAssets();
        publishDiscDropDir();
        super.onCreate(savedInstanceState);
    }

    /* ---- Cabinet freeze diagnostics and recovery (see PsyX_render.cpp) ----
     *
     * At exactly five minutes of inactivity the cabinet's firmware does
     * something to the display that abandons this app's window buffers
     * without a surfaceDestroyed or an onPause: EGL still considers the
     * surface valid and bound, yet eglSwapBuffers fails with EGL_BAD_SURFACE
     * on every frame from then on, while the panel keeps showing the last
     * frame it was given. The game cannot read logcat on that firmware, so
     * native code asks here instead. */

    private static PowerManager.WakeLock sScreenLock;

    /* User-activity poke, every minute while the game is on screen.
     *
     * The cabinet parks its display pipeline at exactly five minutes without
     * input: SurfaceFlinger stops composing and stops delivering vsync, the
     * panel holds its last frame, and the framework is never told (it still
     * reports the display ON, our window valid and visible). Once parked,
     * nothing an app does brings it back -- a rebuilt surface cannot even be
     * laid out without vsync. So the timer must never expire. A wake lock
     * acquired with ACQUIRE_CAUSES_WAKEUP is the one call an ordinary app may
     * make that counts as user activity to PowerManager; a one-second timed
     * acquire every sixty seconds is a periodic nudge.
     *
     * That alone did not stop it: the cabinet still froze at 300 s with the
     * last poke 18 s old, so the five-minute timer is not PowerManager's.
     * Real input does reset it, so the poke also injects a Scroll Lock press
     * into our own window, which needs no permission and which nothing in
     * the game binds (the controls panel refuses it). Instrumentation may not
     * run on the main thread, hence the worker. */
    private static final android.os.Handler sPokeHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private static PowerManager.WakeLock sPokeLock;
    private static long sLastPokeMs;
    private static volatile String sInjectResult = "never";
    private static android.os.Handler sInjectHandler;
    private static final Runnable sInject = new Runnable() {
        public void run() {
            try {
                new android.app.Instrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SCROLL_LOCK);
                sInjectResult = "ok";
            } catch (Throwable t) {
                sInjectResult = t.getClass().getSimpleName();
            }
        }
    };
    private static final Runnable sPoke = new Runnable() {
        public void run() {
            try {
                if (sPokeLock != null) {
                    sPokeLock.acquire(1000);
                    sLastPokeMs = android.os.SystemClock.uptimeMillis();
                }
                if (sInjectHandler == null) {
                    android.os.HandlerThread ht = new android.os.HandlerThread("SilentHill:inject");
                    ht.start();
                    sInjectHandler = new android.os.Handler(ht.getLooper());
                }
                sInjectHandler.post(sInject);
            } catch (Throwable t) {
                Log.w(TAG, "poke: " + t);
            }
            sPokeHandler.postDelayed(this, 60000);
        }
    };

    /** Everything Android will tell us about the display and our window. */
    public static String shDisplayState() {
        StringBuilder sb = new StringBuilder();
        try {
            Context ctx = getContext();
            sb.append("sdk=").append(Build.VERSION.SDK_INT)
              .append(" device=").append(Build.MANUFACTURER).append('/')
              .append(Build.MODEL).append('/').append(Build.DEVICE);
            if (ctx != null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null)
                    sb.append(" interactive=").append(pm.isInteractive());
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Display d = wm.getDefaultDisplay();
                    if (d != null)
                        sb.append(" displayState=").append(d.getState());   /* 1 OFF, 2 ON, 3 DOZE, 4 DOZE_SUSPEND, 6 ON_SUSPEND */
                }
            }
            sb.append(" resumed=").append(mIsResumedCalled).append(" focus=").append(mHasFocus);
            if (mSurface != null) {
                android.view.SurfaceHolder h = mSurface.getHolder();
                boolean valid = h != null && h.getSurface() != null && h.getSurface().isValid();
                sb.append(" surfaceValid=").append(valid)
                  .append(" shown=").append(mSurface.isShown())
                  .append(" winVis=").append(mSurface.getWindowVisibility());
                android.graphics.Rect fr = (h != null) ? h.getSurfaceFrame() : null;
                sb.append(" surfaceFrame=").append(fr != null ? fr.width() + "x" + fr.height() : "?")
                  .append(" view=").append(mSurface.getWidth()).append('x').append(mSurface.getHeight());
            }
            if (ctx instanceof android.app.Activity) {
                android.view.Window w = ((android.app.Activity) ctx).getWindow();
                if (w != null) {
                    View dv = w.getDecorView();
                    sb.append(" keepScreenOn=")
                      .append((w.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
                      .append(" decor=").append(dv.getWidth()).append('x').append(dv.getHeight());
                }
            }
            sb.append(" wakeLock=").append(sScreenLock != null && sScreenLock.isHeld())
              .append(" lastPokeAgo=").append(sLastPokeMs == 0 ? -1 : (android.os.SystemClock.uptimeMillis() - sLastPokeMs) / 1000).append("s")
              .append(" inject=").append(sInjectResult);
            if (ctx != null) {
                android.content.ContentResolver cr = ctx.getContentResolver();
                sb.append(" screenOffTimeout=").append(android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1))
                  .append(" stayOnPlugged=").append(android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1))
                  .append(" screensaver=").append(android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", -1))
                  .append("/sleep").append(android.provider.Settings.Secure.getInt(cr, "screensaver_activate_on_sleep", -1))
                  .append("/dock").append(android.provider.Settings.Secure.getInt(cr, "screensaver_activate_on_dock", -1));
            }
        } catch (Throwable t) {
            sb.append(" err=").append(t);
        }
        return sb.toString();
    }

    /**
     * Force the SurfaceView to drop and rebuild its window. Hiding the view
     * destroys the surface, showing it again creates a fresh one, and SDL's
     * ordinary surfaceDestroyed/surfaceCreated path takes it from there --
     * the only route to a new native window once the old one is abandoned.
     * The second step is delayed so the view system does not fold the two
     * visibility changes into nothing.
     */
    public static void shRecreateSurface() {
        final Context ctx = getContext();
        if (!(ctx instanceof SilentHillActivity) || mSurface == null)
            return;
        final SilentHillActivity act = (SilentHillActivity) ctx;
        act.runOnUiThread(new Runnable() {
            public void run() {
                final View v = mSurface;
                if (v == null)
                    return;
                Log.w(TAG, "presentation lost; recreating the SDL surface");
                v.setVisibility(View.GONE);
                v.postDelayed(new Runnable() {
                    public void run() { v.setVisibility(View.VISIBLE); }
                }, 150);
            }
        });
    }

    /* A wake lock alongside the keep-screen-on window flag. The flag is what
     * SDL already sets and the firmware evidently does not honour it; a lock
     * is a different request through a different path, and it costs nothing
     * to hold while the game is on screen. Deprecated API, still functional. */
    @Override
    protected void onResume() {
        super.onResume();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && sScreenLock == null) {
                sScreenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "SilentHill:screen");
                sScreenLock.setReferenceCounted(false);
            }
            if (sScreenLock != null && !sScreenLock.isHeld())
                sScreenLock.acquire();
            if (pm != null && sPokeLock == null) {
                sPokeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                    "SilentHill:poke");
                sPokeLock.setReferenceCounted(false);
            }
            sPokeHandler.removeCallbacks(sPoke);
            sPokeHandler.postDelayed(sPoke, 60000);
            Log.i(TAG, "screen wake lock held=" + (sScreenLock != null && sScreenLock.isHeld()));
        } catch (Throwable t) {
            Log.w(TAG, "wake lock: " + t);
        }
    }

    @Override
    protected void onPause() {
        sPokeHandler.removeCallbacks(sPoke);
        try {
            if (sScreenLock != null && sScreenLock.isHeld())
                sScreenLock.release();
        } catch (Throwable t) {
            /* nothing to do */
        }
        super.onPause();
    }

    /**
     * Creates the folder users drop their disc image into, and hands the path
     * to native code (BuildDiscSearchRoots reads SH_DISC_DROP_DIR).
     *
     * From Android 11 on, Android/data -- where the app's files dir and its
     * gamedata/ live -- cannot be opened by file-manager apps, so a user with
     * no adb and no built-in Files app cannot deliver a disc image at all.
     * Android/media/<package> is exempt from that restriction and still needs
     * no permission, which makes it the one location both sides can reach.
     *
     * Must run before super.onCreate: that starts SDL and therefore SDL_main,
     * and the environment has to be in place before native code reads it.
     */
    /**
     * The working directory the game anchors every relative path to -- the disc
     * image, gamedata/, config.cfg, the memory cards, the log. main() reads
     * SH_DATA_ROOT and chdir()s there.
     *
     * This used to be getExternalFilesDir(null) unconditionally, which from
     * Android 11 no file manager can open: players could not deliver a disc or
     * retrieve a save, and reported the game as keeping its data somewhere they
     * could not reach. StorageLocations picks a volume the player chose, an SD
     * card by default, and SetupActivity resolves the same answer -- they used
     * to disagree, so a disc installed by setup could land where the game did
     * not look.
     */
    /**
     * RetroAchievements sign-in.
     *
     * Called from the options menu (PCK_RALOGIN) through JNI, and returns
     * immediately: SDL runs the game loop on this thread, so blocking for the
     * button would stop the very loop that has to service the dialog.
     *
     * The engine's own 2D screens cannot ask for this. They have no text input,
     * no keyboard and no masking, and a password typed into a PSX menu with a
     * D-pad would be its own kind of hostile -- so this one row leaves the
     * game's UI entirely, the way the launcher does on desktop.
     */
    public static void showRetroAchievementsLogin() {
        final Context ctx = getContext();
        if (!(ctx instanceof SilentHillActivity)) {
            Log.w(TAG, "no activity for the sign-in dialog");
            return;
        }

        final SilentHillActivity act = (SilentHillActivity) ctx;

        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                final EditText user = new EditText(act);
                user.setHint("RetroAchievements username");
                user.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                user.setSingleLine(true);

                final EditText pass = new EditText(act);
                pass.setHint("Password");
                pass.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                pass.setSingleLine(true);

                LinearLayout box = new LinearLayout(act);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad, pad, 0);
                box.addView(user);
                box.addView(pass);

                new AlertDialog.Builder(act)
                    .setTitle("RetroAchievements")
                    .setMessage("Your password is exchanged once for a token and is "
                              + "never stored. Softcore only.")
                    .setView(box)
                    .setPositiveButton("Sign in", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            nativeRaLogin(user.getText().toString(),
                                          pass.getText().toString());
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });
    }

    /** Hands the credentials to Pc_Ra_BeginPasswordLogin, which does the rest. */
    private static native void nativeRaLogin(String username, String password);

    private void publishDataRoot() {
        File root = StorageLocations.resolve(this);
        if (root == null) {
            Log.w(TAG, "no data root resolved; falling back to the SDL default");
            return;
        }

        try {
            Os.setenv("SH_DATA_ROOT", root.getAbsolutePath(), true);
            /* The in-game options row shows this. The path alone does not say
             * which volume it is, and only this side knows. */
            Os.setenv("SH_DATA_LABEL", StorageLocations.labelFor(this, root), true);
            Log.i(TAG, "data root: " + root.getAbsolutePath());
        } catch (ErrnoException e) {
            Log.w(TAG, "setenv SH_DATA_ROOT failed: " + e.getMessage());
        }
    }

    private void publishDiscDropDir() {
        /* Same place the data lives. Two different answers here is what let a
         * disc image sit in a directory the game never searched. */
        File drop = StorageLocations.resolve(this);

        if (drop == null) {
            Log.w(TAG, "no user-visible dir available; log/config stay in the app's files dir.");
            return;
        }

        try {
            Os.setenv("SH_DISC_DROP_DIR", drop.getAbsolutePath(), true);
            Log.i(TAG, "disc drop dir: " + drop.getAbsolutePath());
        } catch (ErrnoException e) {
            Log.w(TAG, "setenv SH_DISC_DROP_DIR failed: " + e.getMessage());
        }
    }

    /**
     * Copies the port's own assets out of the APK and into the app's external
     * files dir, which is where native code looks for them (main() chdir()s
     * there, and fs_pc.c resolves "./gamedata" against it).
     *
     * This is only the redistributable half of the data: decal.png, the
     * OFL-licensed fonts, the language pack, UI sounds. The game's own content
     * is NOT shipped and never will be -- the user drops their own
     * "Silent Hill (USA).bin" and its extracted gamedata/ into the same
     * directory.
     */
    private void unpackBundledAssets() {
        File target = StorageLocations.resolve(this);
        if (target == null) {
            Log.e(TAG, "No data root; cannot stage bundled assets.");
            return;
        }
        try {
            copyAssetTree(getAssets(), "gamedata", target);
            copyAssetTree(getAssets(), "licenses", target);
        } catch (IOException e) {
            Log.e(TAG, "Failed staging bundled assets: " + e.getMessage());
        }
    }

    private void copyAssetTree(AssetManager am, String path, File destRoot) throws IOException {
        String[] children = am.list(path);

        if (children == null || children.length == 0) {
            copyAssetFile(am, path, new File(destRoot, path));
            return;
        }

        File dir = new File(destRoot, path);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdir failed: " + dir);
        }
        for (String child : children) {
            copyAssetTree(am, path + "/" + child, destRoot);
        }
    }

    private void copyAssetFile(AssetManager am, String assetPath, File dest) throws IOException {
        // Overwriting every launch would stomp a user's edited pl.lang, so only
        // write when the file is missing. Size is not compared: these assets do
        // not change without an app update, and the whole point is that the
        // user may legitimately replace them.
        if (dest.exists()) {
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }

        byte[] buf = new byte[16 * 1024];
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        Log.i(TAG, "staged " + assetPath);
    }
}
