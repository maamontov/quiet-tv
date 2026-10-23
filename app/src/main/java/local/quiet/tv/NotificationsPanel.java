package local.quiet.tv;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** TV remote UI for the latest active notification and the complete current list. */
final class NotificationsPanel {
    final LinearLayout button;
    private final Activity activity;
    private final TextView title, summary, count;
    private final TvStyle.Symbol bell, chevron;
    private final Runnable observer = this::refresh;
    private Dialog dialog;
    private LinearLayout choices;
    private ScrollView scroll;
    private String rendered;
    private boolean started;

    NotificationsPanel(Activity activity, Runnable returnToApps) {
        this.activity = activity;
        button = new LinearLayout(activity);
        button.setId(View.generateViewId());
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setFocusable(true); button.setClickable(true);
        button.setBackground(new TvStyle.Surface(activity, 24, false, 0xff202020));
        bell = new TvStyle.Symbol(activity, TvStyle.BELL);
        button.addView(bell, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        title = label("", 16, TvStyle.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", 0));
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        summary = label("", 12, TvStyle.MUTED);
        summary.setSingleLine(true); summary.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title); text.addView(summary);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMargins(dp(15), 0, dp(16), 0);
        button.addView(text, textParams);
        count = label("", 13, TvStyle.MUTED);
        button.addView(count);
        chevron = new TvStyle.Symbol(activity, TvStyle.CHEVRON);
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(15), dp(15));
        arrow.leftMargin = dp(12);
        button.addView(chevron, arrow);
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(new TvStyle.Surface(activity, 24, focused, 0xff202020));
            int foreground = focused ? 0xff242424 : TvStyle.WHITE;
            int secondary = focused ? 0xff666666 : TvStyle.MUTED;
            bell.tint(foreground); chevron.tint(foreground); title.setTextColor(foreground);
            summary.setTextColor(secondary); count.setTextColor(secondary);
            button.animate().cancel();
            button.animate().scaleX(focused ? 1.025f : 1).scaleY(focused ? 1.025f : 1)
                    .setDuration(340).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        });
        button.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN
                    && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) returnToApps.run();
            return true;
        });
        button.setOnClickListener(view -> show());
        refresh();
    }

    void start() {
        if (started) return;
        started = true; NotificationFeed.observe(observer);
        NotificationFeed.reconnect(activity); refresh();
    }
    void stop() { started = false; NotificationFeed.forget(observer); dismiss(); }
    void dismiss() { if (dialog != null) dialog.dismiss(); }

    private void refresh() {
        List<NotificationFeed.Entry> entries = NotificationFeed.snapshot();
        boolean access = NotificationFeed.hasAccess(activity);
        if (!access) {
            title.setText("Уведомления"); summary.setText("Разрешить доступ к уведомлениям");
        } else if (entries.isEmpty()) {
            title.setText("Уведомлений пока нет"); summary.setText("Здесь появится последнее уведомление");
        } else {
            NotificationFeed.Entry latest = entries.get(0);
            title.setText(latest.title);
            summary.setText(latest.app + (latest.body.isEmpty() ? "" : " · " + latest.body.replace('\n', ' ')));
        }
        count.setText(entries.isEmpty() ? "" : Integer.toString(entries.size()));
        button.setContentDescription("Уведомления");
        if (dialog != null && dialog.isShowing()) renderList(entries, access);
    }

    private void show() {
        dismiss();
        final Dialog opened = new Dialog(activity);
        dialog = opened;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(22), dp(22), dp(22));
        panel.setBackground(new TvStyle.Surface(activity, 22, false, 0xff202020));
        TextView heading = label("Уведомления", 25, TvStyle.WHITE);
        heading.setPadding(dp(4), 0, 0, dp(18));
        panel.addView(heading);
        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(3), dp(3), dp(3), dp(3));
        choices = new LinearLayout(activity);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setClipChildren(false);
        scroll.addView(choices);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.48f); window.setWindowAnimations(R.style.MenuAnimation);
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        dialog.setOnDismissListener(ignored -> {
            if (dialog == opened) { dialog = null; choices = null; scroll = null; rendered = null; }
        });
        dialog.show();
        window.setLayout(dp(Math.min(600, activity.getResources().getConfiguration().screenWidthDp - 80)),
                dp(Math.min(390, activity.getResources().getConfiguration().screenHeightDp - 100)));
        window.setGravity(Gravity.CENTER);
        rendered = null; refresh();
    }

    private void renderList(List<NotificationFeed.Entry> entries, boolean access) {
        StringBuilder signature = new StringBuilder(Boolean.toString(access));
        for (NotificationFeed.Entry entry : entries) signature.append(entry.key).append('\0').append(entry.title)
                .append('\0').append(entry.body).append('\0').append(entry.time / 60000).append(entry.action != null);
        if (signature.toString().equals(rendered)) return;
        rendered = signature.toString();
        View focused = choices.findFocus();
        Object focusedKey = focused == null ? null : focused.getTag();
        int oldScroll = scroll.getScrollY();
        choices.removeAllViews();
        if (entries.isEmpty() || !access) {
            TextView empty = label(access ? "Новых уведомлений нет" : "Разрешить доступ к уведомлениям", 17, TvStyle.WHITE);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(18), dp(28), dp(18), dp(28));
            styleRow(empty, null);
            if (!access) empty.setOnClickListener(view -> openAccess());
            choices.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else for (NotificationFeed.Entry entry : entries) {
            LinearLayout row = new LinearLayout(activity);
            row.setTag(entry.key); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            TextView meta = label(entry.app + " · " + DateUtils.formatDateTime(activity, entry.time, DateUtils.FORMAT_SHOW_TIME), 11, TvStyle.MUTED);
            TextView name = label(entry.title, 17, TvStyle.WHITE);
            name.setTypeface(Typeface.create("sans-serif-medium", 0));
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(meta); row.addView(name);
            TextView body = label(entry.body, 13, TvStyle.MUTED);
            body.setMaxLines(3); body.setEllipsize(TextUtils.TruncateAt.END);
            if (!entry.body.isEmpty()) row.addView(body);
            styleRow(row, new TextView[]{meta, name, body});
            if (entry.action != null) row.setOnClickListener(view -> activate(entry.key));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            if (choices.getChildCount() != 0) params.topMargin = dp(9);
            choices.addView(row, params);
        }
        View target = choices.findViewWithTag(focusedKey);
        if (target == null) target = choices.getChildAt(0);
        if (target != null) target.requestFocus();
        if (focusedKey != null && choices.findViewWithTag(focusedKey) != null) scroll.scrollTo(0, oldScroll);
    }

    private void styleRow(View row, TextView[] labels) {
        row.setFocusable(true);
        row.setBackground(new TvStyle.Surface(activity, 13, false, 0xff292929));
        row.setOnFocusChangeListener((view, focused) -> {
            row.setBackground(new TvStyle.Surface(activity, 13, focused, 0xff292929));
            if (labels == null) ((TextView) row).setTextColor(focused ? 0xff242424 : TvStyle.WHITE);
            else for (int i = 0; i < labels.length; i++) labels[i].setTextColor(focused ? 0xff242424 : i == 1 ? TvStyle.WHITE : TvStyle.MUTED);
        });
    }

    private void activate(String key) {
        for (NotificationFeed.Entry entry : NotificationFeed.snapshot()) if (entry.key.equals(key) && entry.action != null) {
            try { entry.action.send(); dismiss(); }
            catch (PendingIntent.CanceledException gone) { refresh(); }
            return;
        }
    }
    private void openAccess() {
        try { activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException missing) { /* Keep access instructions visible. */ }
    }
    private int dp(float value) { return TvStyle.dp(activity, value); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        return view;
    }
}
