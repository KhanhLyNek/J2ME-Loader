package javax.microedition.shell;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import ru.playsoftware.j2meloader.util.Constants;

/**
 * One optional overlay per running emulator process. Nothing is rendered from
 * the MIDlet onto this view: keeping it a tiny TextView avoids frame copies.
 * Without the user-granted overlay permission the service notification remains
 * the supported way to return to a minimized game.
 */
final class SessionBubble {
    private final Service service;
    private final WindowManager manager;
    private View bubble;

    SessionBubble(Service service) {
        this.service = service;
        manager = (WindowManager) service.getSystemService(Service.WINDOW_SERVICE);
    }

    boolean show(String title, String path) {
        if (bubble != null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(service)) {
            return false;
        }
        if (manager == null || path == null) return false;
        int slot = Math.max(0, MidletSlots.currentSlot(service));
        final float density = service.getResources().getDisplayMetrics().density;
        int diameter = (int) (55 * density);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                diameter, diameter, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = (int) (16 * density);
        params.y = (int) ((140 + slot * 70) * density);

        TextView view = new TextView(service);
        String label = title == null || title.trim().isEmpty()
                ? "J" + (slot + 1) : title.trim();
        view.setText(label.length() > 3 ? label.substring(0, 3) : label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(title == null ? "J2ME session" : title);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(42, 126, 106));
        bg.setCornerRadius(diameter / 2f);
        bg.setStroke((int) (2 * density), Color.WHITE);
        view.setBackground(bg);

        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int initialX, initialY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        initialX = params.x;
                        initialY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) > 10 * density || Math.abs(dy) > 10 * density) {
                            moved = true;
                        }
                        // Gravity.END means positive x moves left.
                        params.x = Math.max(0, initialX - dx);
                        params.y = Math.max(0, initialY + dy);
                        try {
                            manager.updateViewLayout(view, params);
                        } catch (RuntimeException ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Intent open = new Intent(Intent.ACTION_DEFAULT,
                                    Uri.parse(path), service,
                                    MidletSlots.activityClass(slot));
                            open.putExtra(Constants.KEY_MIDLET_NAME, title);
                            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            try {
                                service.startActivity(open);
                            } catch (RuntimeException ignored) {
                                // On restricted OEMs the service notification opens
                                // the same Activity through a PendingIntent.
                            }
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            manager.addView(view, params);
            bubble = view;
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    void dismiss() {
        if (bubble != null && manager != null) {
            try {
                manager.removeView(bubble);
            } catch (RuntimeException ignored) {}
            bubble = null;
        }
    }
}
