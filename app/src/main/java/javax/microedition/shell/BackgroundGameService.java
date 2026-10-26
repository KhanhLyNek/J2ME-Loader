/*
 * Background MIDlet session.
 *
 * The session is explicitly opted into in settings and starts while the
 * MicroActivity is still in the foreground. It shares the :midlet process,
 * never starts a MIDlet by itself, and cannot resurrect a terminated game.
 */
package javax.microedition.shell;

import static ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME;
import static ru.playsoftware.j2meloader.util.Constants.PREF_BACKGROUND_GAME_ECO;
import static ru.playsoftware.j2meloader.util.Constants.PREF_BACKGROUND_GAME_ENABLED;
import static ru.playsoftware.j2meloader.util.Constants.PREF_BACKGROUND_GAME_MINUTES;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;

/**
 * Foreground, user-visible lifetime for a running game.
 *
 * The regular foreground service does not hold a CPU wakelock. A timed,
 * non-reference-counted PARTIAL_WAKE_LOCK is acquired only while the screen
 * is actually off, and only when the user has not selected Eco mode.
 */
public class BackgroundGameService extends Service {
    private static final String TAG = "J2ME-Background";
    private static final String CHANNEL_ID = "j2me_background_game_v1";
    private static final String ACTION_START = "javax.microedition.shell.BACKGROUND_START";
    private static final String ACTION_STOP = "javax.microedition.shell.BACKGROUND_STOP";
    private static final String ACTION_MINIMIZE = "javax.microedition.shell.BACKGROUND_MINIMIZE";
    private static final String EXTRA_TITLE = "game_title";
    private static final String EXTRA_PATH = "game_path";
    private static final int NOTIFICATION_ID = 30814;
    private static final int TIMEOUT_NOTIFICATION_ID = 30815;
    private static final int REQUEST_STOP = 30816;
    private static final int REQUEST_RETURN = 30817;

    private static volatile BackgroundGameService activeInstance;
    private static volatile boolean screenOff;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onTimeout = () -> finishSession(true);
    private PowerManager powerManager;
    private PowerManager.WakeLock cpuLock;
    private boolean receiverRegistered;
    private boolean enabled;
    private boolean eco;
    private boolean floating;
    private SessionBubble sessionBubble;
    private long maxScreenOffMillis;
    private long screenOffStartedAt;
    private String gameTitle;
    private String gamePath;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                onScreenOn();
            }
        }
    };

    public static void startForGame(Context activity, String title, String path) {
        if (!BuildConfig.FULL_EMULATOR) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!preferences.getBoolean(PREF_BACKGROUND_GAME_ENABLED, false)) {
            return;
        }
        Intent start = new Intent(activity, MidletSlots.serviceForCurrentProcess(activity));
        start.setAction(ACTION_START);
        start.putExtra(EXTRA_TITLE, title);
        start.putExtra(EXTRA_PATH, path);
        try {
            ContextCompat.startForegroundService(activity, start);
        } catch (RuntimeException ex) {
            // Includes ForegroundServiceStartNotAllowedException and OEM
            // restrictions. Fall back to the ordinary MIDlet pause lifecycle.
            Log.e(TAG, "Could not start background session; MIDlet will pause normally", ex);
        }
    }

    /** An explicit foreground menu action; notification works without overlay access. */
    public static boolean minimizeForGame(Context activity, String title, String path) {
        try {
            Intent start = new Intent(activity, MidletSlots.serviceForCurrentProcess(activity));
            start.setAction(ACTION_MINIMIZE);
            start.putExtra(EXTRA_TITLE, title);
            start.putExtra(EXTRA_PATH, path);
            ContextCompat.startForegroundService(activity, start);
            return true;
        } catch (RuntimeException failure) {
            Log.e(TAG, "Unable to minimize MIDlet; keeping its Activity visible", failure);
            return false;
        }
    }

    public static void stopForGame(Context activity) {
        activity.stopService(new Intent(activity, MidletSlots.serviceForCurrentProcess(activity)));
    }

    /** Hide the bubble as soon as the user returns to this game. */
    public static void onGameVisible() {
        BackgroundGameService service = activeInstance;
        if (service != null) {
            service.floating = false;
            service.removeBubble();
        }
    }

    public static boolean isFloatingMinimized() {
        BackgroundGameService service = activeInstance;
        return service != null && service.enabled && service.floating;
    }

    public static boolean keepsMidletRunning() {
        BackgroundGameService service = activeInstance;
        return service != null && service.enabled;
    }

    public static boolean shouldSkipScreenOutput() {
        BackgroundGameService service = activeInstance;
        MicroActivity activity = javax.microedition.util.ContextHolder.getActivity();
        return service != null && service.enabled
                && (screenOff || (service.floating
                && (activity == null || !activity.isVisible())));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            // Stopping one session must never disable the other slots.
            finishSession(false);
            return START_NOT_STICKY;
        }
        boolean minimize = ACTION_MINIMIZE.equals(intent.getAction());
        if (!ACTION_START.equals(intent.getAction()) && !minimize) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!minimize && !preferences.getBoolean(PREF_BACKGROUND_GAME_ENABLED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        gameTitle = intent.getStringExtra(EXTRA_TITLE);
        gamePath = intent.getStringExtra(EXTRA_PATH);
        floating = floating || minimize;
        eco = preferences.getBoolean(PREF_BACKGROUND_GAME_ECO, false);
        int minutes = 60;
        try {
            minutes = Integer.parseInt(
                    preferences.getString(PREF_BACKGROUND_GAME_MINUTES, "60"));
        } catch (NumberFormatException | ClassCastException error) {
            Log.w(TAG, "Invalid background session duration; using one hour", error);
        }
        minutes = Math.max(15, Math.min(minutes, 240));
        maxScreenOffMillis = minutes * 60_000L;

        makeNotificationChannel();
        try {
            Notification notification = makeNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId(), notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(notificationId(), notification);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Foreground service denied; falling back to normal MIDlet pausing", e);
            // The Activity may already have returned to the library.
            // Without a visible foreground service the MIDlet must not
            // continue indefinitely as an invisible background process.
            if (minimize) MidletThread.pauseApp();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            // System-originated broadcasts; no third-party app messages.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            receiverRegistered = true;
        }
        enabled = true;
        activeInstance = this;
        if (powerManager != null && !powerManager.isInteractive()) {
            onScreenOff();
        } else {
            onScreenOn();
        }
        // Race-safe: if the Activity paused while the service was starting,
        // resume the MIDlet only after the foreground service is active.
        // A hidden game must not resume just because a service starts while
        // the screen is awake, unless minimization was explicitly requested.
        if (floating || (powerManager != null && !powerManager.isInteractive())) {
            MidletThread.resumeForBackground();
        }
        if (floating) showBubble();
        return START_NOT_STICKY;
    }

    private int notificationId() {
        return NOTIFICATION_ID + Math.max(0, MidletSlots.currentSlot(this)) * 10;
    }

    private int timeoutNotificationId() {
        return TIMEOUT_NOTIFICATION_ID + Math.max(0, MidletSlots.currentSlot(this)) * 10;
    }

    private void showBubble() {
        if (sessionBubble == null) sessionBubble = new SessionBubble(this);
        sessionBubble.show(gameTitle, gamePath);
    }

    private void removeBubble() {
        if (sessionBubble != null) sessionBubble.dismiss();
    }

    private void makeNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.bg_game_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.bg_game_enable_summary));
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification makeNotification() {
        Intent openGame;
        if (gamePath != null && !gamePath.isEmpty()) {
            openGame = new Intent(Intent.ACTION_DEFAULT, Uri.parse(gamePath),
                    this, MidletSlots.activityForCurrentProcess(this));
            openGame.putExtra(KEY_MIDLET_NAME, gameTitle);
            openGame.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            openGame = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (openGame == null) {
                openGame = new Intent(this, MicroActivity.class);
            }
        }
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent returnIntent = PendingIntent.getActivity(
                this, REQUEST_RETURN + Math.max(0, MidletSlots.currentSlot(this)) * 10,
                openGame, pendingFlags);

        Intent stopIntent = new Intent(this, MidletSlots.serviceForCurrentProcess(this));
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this, REQUEST_STOP + Math.max(0, MidletSlots.currentSlot(this)) * 10,
                stopIntent, pendingFlags);

        String title = gameTitle != null && !gameTitle.trim().isEmpty()
                ? gameTitle : getString(R.string.bg_game_notification_title);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(getString(R.string.bg_game_notification_text))
                .setContentIntent(returnIntent)
                .addAction(android.R.drawable.ic_media_pause,
                        getString(R.string.bg_game_notification_stop), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void onScreenOff() {
        if (!enabled || screenOff) {
            return;
        }
        screenOff = true;
        screenOffStartedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(onTimeout);
        handler.postDelayed(onTimeout, maxScreenOffMillis);
        if (!eco && powerManager != null) {
            try {
                if (cpuLock == null) {
                    cpuLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            getPackageName() + ":MIDletScreenOff");
                    cpuLock.setReferenceCounted(false);
                }
                cpuLock.acquire(maxScreenOffMillis);
            } catch (RuntimeException error) {
                Log.e(TAG, "Cannot acquire CPU wake lock; continuing in Eco mode", error);
            }
        }
        MidletThread.resumeForBackground();
        Log.i(TAG, "Screen off: game running in " + (eco ? "Eco" : "CPU awake")
                + " mode, limit " + (maxScreenOffMillis / 60000L) + " min");
    }

    private void onScreenOn() {
        screenOff = false;
        screenOffStartedAt = 0L;
        handler.removeCallbacks(onTimeout);
        releaseCpuLock();
        // Do not burn CPU in a game hidden behind the lock screen or
        // another app after the user wakes the display.
        MicroActivity activity = javax.microedition.util.ContextHolder.getActivity();
        if (!floating && activity != null && !activity.isVisible()) {
            MidletThread.pauseApp();
        }
    }

    private void releaseCpuLock() {
        if (cpuLock != null && cpuLock.isHeld()) {
            try {
                cpuLock.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Wake lock release failed", error);
            }
        }
    }

    private void finishSession(boolean expired) {
        if (!enabled) {
            stopSelf();
            return;
        }
        if (expired) {
            Log.i(TAG, "Background session expired after " +
                    (SystemClock.elapsedRealtime() - screenOffStartedAt) + " ms");
        }
        enabled = false;
        floating = false;
        removeBubble();
        activeInstance = null;
        onScreenOn();
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        // A stopped/expired background session must never keep the game
        // running invisibly without an active user-visible notification.
        MicroActivity activity = javax.microedition.util.ContextHolder.getActivity();
        if (activity == null || !activity.isVisible()) {
            MidletThread.pauseApp();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
        if (expired) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                Notification done = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(getString(R.string.bg_game_timeout_title))
                        .setContentText(getString(R.string.bg_game_timeout_text))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();
                try {
                    manager.notify(timeoutNotificationId(), done);
                } catch (SecurityException denied) {
                    Log.w(TAG, "Couldn't show timeout notification", denied);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        enabled = false;
        floating = false;
        removeBubble();
        activeInstance = null;
        onScreenOn();
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        // Ensures no MIDlet continues after a forced service stop.
        MicroActivity activity = javax.microedition.util.ContextHolder.getActivity();
        if (activity == null || !activity.isVisible()) {
            MidletThread.pauseApp();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
