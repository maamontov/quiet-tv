package local.quiet.tv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System-bound, event-driven feed. Only lightweight active notifications stay in memory. */
public final class NotificationFeed extends NotificationListenerService {
    static final class Entry {
        final String key, app, title, body;
        final long time;
        final PendingIntent action;
        Entry(String key, String app, String title, String body, long time, PendingIntent action) {
            this.key = key; this.app = app; this.title = title; this.body = body;
            this.time = time; this.action = action;
        }
    }

    private static final ArrayList<Runnable> observers = new ArrayList<>();
    private static List<Entry> current = Collections.emptyList();
    private static NotificationFeed bound;
    private static boolean connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final HashMap<String, Entry> entries = new HashMap<>();
    private final HashMap<String, String> labels = new HashMap<>();
    private int epoch;
    private boolean destroyed;

    static List<Entry> snapshot() { return current; }
    static void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    static void forget(Runnable observer) { observers.remove(observer); }
    private static void changed() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }

    static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, NotificationFeed.class);
        if (Build.VERSION.SDK_INT >= 27) {
            return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled != null) for (String name : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    static void reconnect(Context context) {
        if (!connected && hasAccess(context)) requestRebind(new ComponentName(context, NotificationFeed.class));
    }

    @Override public void onCreate() { super.onCreate(); bound = this; }

    @Override public void onListenerConnected() {
        connected = true;
        final int version = ++epoch;
        changed();
        final StatusBarNotification[] active;
        try { active = getActiveNotifications(); }
        catch (SecurityException revoked) { disconnect(); return; }
        worker.execute(() -> {
            entries.clear();
            if (active != null) for (StatusBarNotification item : active) put(item);
            publish(version);
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification item) {
        final int version = epoch;
        worker.execute(() -> { put(item); publish(version); });
    }

    @Override public void onNotificationRemoved(StatusBarNotification item) {
        final int version = epoch;
        worker.execute(() -> { entries.remove(item.getKey()); publish(version); });
    }

    private void put(StatusBarNotification item) {
        Notification notification = item.getNotification();
        // Persistent service/media status is not an incoming message for the home screen.
        // Group summaries are represented by their individual messages instead.
        if (item.isOngoing() || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            entries.remove(item.getKey()); return;
        }
        String app = labels.get(item.getPackageName());
        if (app == null) {
            try {
                PackageManager pm = getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(item.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException missing) { app = "Приложение"; }
            labels.put(item.getPackageName(), app);
        }
        String title = clean(notification.extras.getCharSequence(Notification.EXTRA_TITLE), 250);
        String body = clean(notification.extras.getCharSequence(Notification.EXTRA_TEXT), 4000);
        if (body.isEmpty()) body = clean(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT), 4000);
        if (body.isEmpty()) {
            CharSequence[] lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length != 0) body = clean(lines[lines.length - 1], 4000);
        }
        entries.put(item.getKey(), new Entry(item.getKey(), app, title.isEmpty() ? app : title,
                body, item.getPostTime(), notification.contentIntent));
    }

    private static String clean(CharSequence value, int limit) {
        if (value == null) return "";
        String text = value.toString().trim();
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private void publish(int version) {
        ArrayList<Entry> snapshot = new ArrayList<>(entries.values());
        snapshot.sort((a, b) -> {
            int order = Long.compare(b.time, a.time);
            return order == 0 ? a.key.compareTo(b.key) : order;
        });
        List<Entry> immutable = Collections.unmodifiableList(snapshot);
        main.post(() -> {
            if (!destroyed && bound == this && version == epoch && connected && !current.equals(immutable)) {
                current = immutable; changed();
            }
        });
    }

    private void disconnect() {
        ++epoch;
        if (bound == this) { connected = false; current = Collections.emptyList(); changed(); }
    }
    @Override public void onListenerDisconnected() { disconnect(); }
    @Override public void onDestroy() {
        destroyed = true; disconnect();
        if (bound == this) bound = null;
        worker.shutdownNow();
        super.onDestroy();
    }
}
