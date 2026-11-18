/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import ru.playsoftware.j2meloader.R;

/**
 * Foreground service that runs inside the {@code :midlet} process and keeps it alive
 * (CPU awake, process priority raised) while the screen is off. Required on Android 14+
 * (typed foreground service) and robust against Android 16 background restrictions.
 */
public class KeepAliveService extends Service {
	private static final String TAG = KeepAliveService.class.getSimpleName();
	private static final String CHANNEL_ID = "midlet_background_run";
	private static final int NOTIFICATION_ID = 0x4A32;
	private static final String ACTION_EXIT = "ru.playsoftware.j2meloader.action.EXIT_MIDLET";
	private static final String EXTRA_TITLE = "title";
	private static final String EXTRA_OPEN_INTENT = "open_intent";
	/** Safety cap so a forgotten session can't drain the battery forever. */
	private static final long WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000;

	private PowerManager.WakeLock wakeLock;

	public static void start(Context context, String title, Intent activityIntent) {
		try {
			Intent open = new Intent(activityIntent).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
			PendingIntent openPending = PendingIntent.getActivity(context, 0, open, flags);
			Intent intent = new Intent(context, KeepAliveService.class)
					.putExtra(EXTRA_TITLE, title)
					.putExtra(EXTRA_OPEN_INTENT, openPending);
			ContextCompat.startForegroundService(context, intent);
		} catch (RuntimeException e) {
			// e.g. ForegroundServiceStartNotAllowedException: the game must keep running regardless
			Log.w(TAG, "Can't start background service", e);
		}
	}

	public static void stop(Context context) {
		try {
			context.stopService(new Intent(context, KeepAliveService.class));
		} catch (RuntimeException e) {
			Log.w(TAG, "Can't stop background service", e);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
			MidletThread.destroyApp();
			stopSelf();
			return START_NOT_STICKY;
		}
		String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
		PendingIntent open = intent != null ? intent.getParcelableExtra(EXTRA_OPEN_INTENT) : null;
		Notification notification = buildNotification(title, open);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}
		acquireWakeLock();
		return START_NOT_STICKY;
	}

	private Notification buildNotification(@Nullable String title, @Nullable PendingIntent open) {
		NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
					getString(R.string.notification_channel_background),
					NotificationManager.IMPORTANCE_LOW);
			channel.setShowBadge(false);
			nm.createNotificationChannel(channel);
		}
		Intent exit = new Intent(this, KeepAliveService.class).setAction(ACTION_EXIT);
		PendingIntent exitPending = PendingIntent.getService(this, 1, exit,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_stat_background_run)
				.setContentTitle(title != null ? title : getString(R.string.app_name))
				.setContentText(getString(R.string.notification_background_running))
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
				.addAction(0, getString(R.string.notification_action_exit), exitPending);
		if (open != null) {
			builder.setContentIntent(open);
		}
		return builder.build();
	}

	private void acquireWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) {
			return;
		}
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2MELoader:midlet");
		wakeLock.setReferenceCounted(false);
		wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
	}

	@Override
	public void onDestroy() {
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
		}
		wakeLock = null;
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
