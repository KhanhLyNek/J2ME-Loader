package javax.microedition.shell;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.event.CanvasEvent;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.Constants;

/**
 * Per-MIDlet draggable chat head. Tapping opens a small interactive OVERLAY,
 * not the full Activity. Game frames are sampled at low frequency only while
 * the mini window is visible and the screen is awake. No extra game instance,
 * MediaProjection permission, or continuously running rendering thread.
 *
 * The overlay deliberately never becomes a second SurfaceView/GL context.
 * That would cost significantly more battery, particularly with screen off.
 */
final class SessionBubble {
    private static final long PREVIEW_INTERVAL_MS = 240L;

    private final Service service;
    private final WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> heldKeys = new HashSet<>();

    private View bubble;
    private View mini;
    private ImageView preview;
    private Bitmap thumbnail;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams miniParams;
    private String gameTitle;
    private String gamePath;
    private int slot;

    private final Runnable refreshPreview = new Runnable() {
        @Override
        public void run() {
            if (mini == null || preview == null || !isScreenAwake()) return;
            Canvas canvas = gameCanvas();
            if (canvas != null && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
                int w = Math.min(240, Math.max(1, canvas.getWidth()));
                int h = Math.max(1, Math.round(w * canvas.getHeight()
                        / (float) canvas.getWidth()));
                if (h > 300) {
                    w = Math.max(1, Math.round(w * (300f / h)));
                    h = 300;
                }
                if (thumbnail == null || thumbnail.getWidth() != w
                        || thumbnail.getHeight() != h) {
                    preview.setImageBitmap(null);
                    if (thumbnail != null) thumbnail.recycle();
                    thumbnail = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                    preview.setImageBitmap(thumbnail);
                }
                if (canvas.copyMiniFrameTo(thumbnail)) preview.invalidate();
            }
            handler.postDelayed(this, PREVIEW_INTERVAL_MS);
        }
    };

    SessionBubble(Service service) {
        this.service = service;
        manager = (WindowManager) service.getSystemService(Service.WINDOW_SERVICE);
    }

    private int dp(float value) {
        return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean isScreenAwake() {
        PowerManager pm = (PowerManager) service.getSystemService(Service.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams layout(int width, int height, int x, int y) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = x;
        params.y = y;
        return params;
    }

    private GradientDrawable shape(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    boolean show(String title, String path) {
        if (bubble != null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(service)) return false;
        if (manager == null || path == null) return false;

        gameTitle = title == null ? "J2ME" : title;
        gamePath = path;
        slot = Math.max(0, MidletSlots.currentSlot(service));
        int size = dp(55);
        bubbleParams = layout(size, size, dp(16), dp(140 + slot * 70));
        TextView view = new TextView(service);
        String label = gameTitle.trim().isEmpty() ? "J" + (slot + 1) : gameTitle.trim();
        view.setText(label.length() > 3 ? label.substring(0, 3) : label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(gameTitle + " - cửa sổ mini");
        view.setBackground(shape(Color.rgb(42, 126, 106), 32, Color.WHITE));

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
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        bubbleParams.x = Math.max(0, initialX - dx);
                        bubbleParams.y = Math.max(0, initialY + dy);
                        try {
                            manager.updateViewLayout(view, bubbleParams);
                        } catch (RuntimeException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) expand();
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            manager.addView(view, bubbleParams);
            bubble = view;
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private Canvas gameCanvas() {
        MicroActivity activity = ContextHolder.getActivity();
        if (activity == null) return null;
        Displayable displayable = activity.getCurrent();
        return displayable instanceof Canvas ? (Canvas) displayable : null;
    }

    private TextView textButton(String label, int background) {
        TextView button = new TextView(service);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(background, 7, 0));
        return button;
    }

    private void addHeaderDrag(TextView header) {
        header.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int originX, originY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        originX = miniParams.x;
                        originY = miniParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        miniParams.x = Math.max(0, originX
                                - Math.round(event.getRawX() - startX));
                        miniParams.y = Math.max(0, originY
                                + Math.round(event.getRawY() - startY));
                        try { manager.updateViewLayout(mini, miniParams); }
                        catch (RuntimeException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void addControl(LinearLayout row, String label, int code) {
        TextView button = textButton(label, Color.rgb(47, 59, 77));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
        button.setOnTouchListener((v, e) -> {
            Canvas canvas = gameCanvas();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (canvas != null && heldKeys.add(code)) canvas.postKeyPressed(code);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (heldKeys.remove(code) && canvas != null) canvas.postKeyReleased(code);
                    return true;
                default:
                    return true;
            }
        });
    }

    private LinearLayout controlRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void releaseHeldKeys() {
        Canvas canvas = gameCanvas();
        if (canvas != null) for (int code : heldKeys) canvas.postKeyReleased(code);
        heldKeys.clear();
    }

    private boolean previewTouch(View v, MotionEvent e) {
        Canvas canvas = gameCanvas();
        if (canvas == null || thumbnail == null) return true;
        float scale = Math.min(v.getWidth() / (float) thumbnail.getWidth(),
                v.getHeight() / (float) thumbnail.getHeight());
        if (scale <= 0) return true;
        float padX = (v.getWidth() - thumbnail.getWidth() * scale) / 2f;
        float padY = (v.getHeight() - thumbnail.getHeight() * scale) / 2f;
        float x = Math.max(0, Math.min(canvas.getWidth() - 1,
                (e.getX() - padX) / scale * canvas.getWidth() / thumbnail.getWidth()));
        float y = Math.max(0, Math.min(canvas.getHeight() - 1,
                (e.getY() - padY) / scale * canvas.getHeight() / thumbnail.getHeight()));
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            canvas.postMiniPointer(CanvasEvent.POINTER_PRESSED, x, y);
        } else if (action == MotionEvent.ACTION_MOVE) {
            canvas.postMiniPointer(CanvasEvent.POINTER_DRAGGED, x, y);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            canvas.postMiniPointer(CanvasEvent.POINTER_RELEASED, x, y);
        }
        return true;
    }

    private void openFullScreen() {
        releaseHeldKeys();
        Intent open = new Intent(Intent.ACTION_DEFAULT, Uri.parse(gamePath),
                service, MidletSlots.activityClass(slot));
        open.putExtra(Constants.KEY_MIDLET_NAME, gameTitle);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            service.startActivity(open);
        } catch (RuntimeException denied) {
            Toast.makeText(service, "Mở trò chơi từ thông báo nền", Toast.LENGTH_SHORT).show();
        }
    }

    private void expand() {
        if (mini != null || bubble == null) return;
        LinearLayout panel = new LinearLayout(service);
        panel.setPadding(dp(7), dp(7), dp(7), dp(7));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(shape(Color.rgb(26, 34, 47), 13, Color.rgb(90, 108, 130)));

        LinearLayout headerRow = controlRow(panel);
        TextView drag = textButton(gameTitle.length() > 17
                ? gameTitle.substring(0, 17) : gameTitle, Color.rgb(26, 34, 47));
        headerRow.addView(drag, new LinearLayout.LayoutParams(0, dp(38), 1));
        addHeaderDrag(drag);
        TextView full = textButton("⛶", Color.rgb(46, 79, 99));
        headerRow.addView(full, new LinearLayout.LayoutParams(dp(45), dp(38)));
        full.setContentDescription("Mở trò chơi toàn màn hình");
        full.setOnClickListener(v -> openFullScreen());
        TextView collapse = textButton("−", Color.rgb(87, 65, 69));
        headerRow.addView(collapse, new LinearLayout.LayoutParams(dp(45), dp(38)));
        collapse.setContentDescription("Thu gọn về bong bóng");
        collapse.setOnClickListener(v -> collapse());

        preview = new ImageView(service);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230));
        screenParams.topMargin = dp(5);
        screenParams.bottomMargin = dp(5);
        panel.addView(preview, screenParams);
        preview.setOnTouchListener(this::previewTouch);

        LinearLayout row1 = controlRow(panel);
        addControl(row1, "L", Canvas.KEY_SOFT_LEFT);
        addControl(row1, "↑", Canvas.KEY_UP);
        addControl(row1, "R", Canvas.KEY_SOFT_RIGHT);
        LinearLayout row2 = controlRow(panel);
        addControl(row2, "←", Canvas.KEY_LEFT);
        addControl(row2, "OK", Canvas.KEY_FIRE);
        addControl(row2, "→", Canvas.KEY_RIGHT);
        LinearLayout row3 = controlRow(panel);
        addControl(row3, "7", Canvas.KEY_NUM7);
        addControl(row3, "↓", Canvas.KEY_DOWN);
        addControl(row3, "9", Canvas.KEY_NUM9);

        miniParams = layout(dp(262), WindowManager.LayoutParams.WRAP_CONTENT,
                bubbleParams.x, bubbleParams.y);
        try {
            manager.addView(panel, miniParams);
            mini = panel;
            bubble.setVisibility(View.GONE);
            BackgroundGameService.setMiniPreviewVisible(true);
            onDisplayOn();
        } catch (RuntimeException denied) {
            preview = null;
            mini = null;
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            Toast.makeText(service, "Không thể hiển thị cửa sổ mini", Toast.LENGTH_SHORT).show();
        }
    }

    private void collapse() {
        releaseHeldKeys();
        BackgroundGameService.setMiniPreviewVisible(false);
        handler.removeCallbacks(refreshPreview);
        if (preview != null) preview.setImageBitmap(null);
        if (mini != null) {
            try { manager.removeView(mini); }
            catch (RuntimeException ignored) { }
        }
        mini = null;
        preview = null;
        if (thumbnail != null) thumbnail.recycle();
        thumbnail = null;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    void onDisplayOff() {
        handler.removeCallbacks(refreshPreview);
        releaseHeldKeys();
    }

    void onDisplayOn() {
        if (mini != null && isScreenAwake()) {
            handler.removeCallbacks(refreshPreview);
            handler.post(refreshPreview);
        }
    }

    void dismiss() {
        collapse();
        if (bubble != null && manager != null) {
            try { manager.removeView(bubble); }
            catch (RuntimeException ignored) { }
            bubble = null;
        }
    }
}
