package javax.microedition.shell;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.util.List;

import ru.playsoftware.j2meloader.BuildConfig;

/**
 * Fixed, explicitly declared process slots. Each slot owns independent MIDlet
 * statics, class loaders, lifecycle, and its own foreground service.
 *
 * Android cannot create manifest process components dynamically. More slots
 * require adding matching Activity + Service declarations.
 */
public final class MidletSlots {
    private static final String TAG = "J2ME-Slots";
    public static final int COUNT = 3;

    private MidletSlots() {}

    public static final class Slot1Activity extends MicroActivity {}
    public static final class Slot2Activity extends MicroActivity {}
    public static final class Slot1Service extends BackgroundGameService {}
    public static final class Slot2Service extends BackgroundGameService {}

    public static Class<? extends MicroActivity> activityClass(int slot) {
        switch (slot) {
            case 1: return Slot1Activity.class;
            case 2: return Slot2Activity.class;
            default: return MicroActivity.class;
        }
    }

    public static Class<? extends BackgroundGameService> serviceClass(int slot) {
        switch (slot) {
            case 1: return Slot1Service.class;
            case 2: return Slot2Service.class;
            default: return BackgroundGameService.class;
        }
    }

    private static int slotForProcessName(Context context, String name) {
        String prefix = context.getPackageName() + ":midlet";
        if (prefix.equals(name)) return 0;
        if ((prefix + "_1").equals(name)) return 1;
        if ((prefix + "_2").equals(name)) return 2;
        return -1;
    }

    public static int currentSlot(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == Process.myPid()) {
                        return slotForProcessName(context, process.processName);
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Return the first free emulator process. The UI process never reuses an
     * occupied slot: doing so would overwrite the game's global MIDlet state.
     * This checks live PIDs (not stale preferences); Android may still kill a
     * process between selection and Activity startup.
     */
    public static Class<? extends MicroActivity> findFreeActivity(Context context) {
        if (!BuildConfig.FULL_EMULATOR) return MicroActivity.class;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            Log.w(TAG, "Process list unavailable; refusing to reuse a possibly busy slot");
            return null;
        }
        boolean[] occupied = new boolean[COUNT];
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            int slot = slotForProcessName(context, process.processName);
            if (slot >= 0) occupied[slot] = true;
        }
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i]) return activityClass(i);
        }
        return null;
    }

    public static Class<? extends BackgroundGameService> serviceForCurrentProcess(Context context) {
        int slot = currentSlot(context);
        if (slot < 0) throw new IllegalStateException("Not in an emulator slot process");
        return serviceClass(slot);
    }

    public static Class<? extends MicroActivity> activityForCurrentProcess(Context context) {
        int slot = currentSlot(context);
        if (slot < 0) throw new IllegalStateException("Not in an emulator slot process");
        return activityClass(slot);
    }
}
