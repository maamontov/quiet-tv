package local.quiet.tv;

import android.app.Activity;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final int WHITE = TvStyle.WHITE;
    private static final int MUTED = TvStyle.MUTED;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<AppCatalog.Entry> all = new ArrayList<>();
    private final ArrayList<AppCatalog.Entry> visible = new ArrayList<>();
    private final ArrayList<AppTile> tiles = new ArrayList<>();
    private final ArrayList<View> tools = new ArrayList<>();
    private AppCatalog catalog;
    private SharedPreferences preferences;
    private LinearLayout rows;
    private FrameLayout root;
    private TvStyle.Rail scroll;
    private NotificationsPanel notifications;
    private LinearLayout hero;
    private LinearLayout heroCurrent, heroPrevious;
    private TextView heroTitle, position;
    private TextView previousTitle;
    private String selectedId;
    private String displayedId;
    private AppCatalog.Entry pendingHero;
    private boolean heroAnimating;
    private boolean active;
    private final Runnable heroUpdate = this::updateHero;
    private int consumedKeyUp = -1;
    private int columns;
    private int generation;
    private boolean destroyed;
    private boolean loading;
    private boolean reload;
    private boolean receiverRegistered;
    private boolean moving;
    private String movingId;
    private ArrayList<AppCatalog.Entry> beforeMove;
    private String lastFocused;
    private boolean restoreLastFocus;
    private Dialog activeMenu;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshApps(); }
    };
    private final BroadcastReceiver systemDialogs = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("homekey".equals(intent.getStringExtra("reason"))) returnToFirst();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("home", MODE_PRIVATE);
        catalog = new AppCatalog(this);
        if (state != null) lastFocused = state.getString("focus");
        createScreen();
    }

    private void createScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff101010);
        root.setClipChildren(false);
        getWindow().setBackgroundDrawable(null);
        int height = getResources().getConfiguration().screenHeightDp;
        int tileHeight = Math.round(height / 3f);
        int railHeight = tileHeight + 54;
        int tileTop = (height - tileHeight) / 2;
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(WHITE);
        clock.setTextSize(23);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(-2, -2);
        clockParams.rightMargin = dp(16);
        header.addView(clock, clockParams);
        addTool(header, "Входы", TvStyle.INPUT, this::openInputs);
        addTool(header, "Настройки", TvStyle.SETTINGS, this::showSettings);
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP);
        headerParams.setMargins(dp(52), dp(28), dp(52), 0);
        root.addView(header, headerParams);

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        FrameLayout heroText = new FrameLayout(this);
        heroPrevious = heroBody();
        previousTitle = (TextView) heroPrevious.getChildAt(0);
        heroPrevious.setAlpha(0);
        heroText.addView(heroPrevious, new FrameLayout.LayoutParams(-1, -1));
        heroCurrent = heroBody();
        heroTitle = (TextView) heroCurrent.getChildAt(0);
        heroText.addView(heroCurrent, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams heroTextParams = new LinearLayout.LayoutParams(-1, dp(46));
        hero.addView(heroText, heroTextParams);
        FrameLayout.LayoutParams heroParams = new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP);
        heroParams.setMargins(dp(56), dp(tileTop - 64), dp(180), 0);
        root.addView(hero, heroParams);

        position = text("", 12, MUTED);
        position.setGravity(Gravity.RIGHT);
        position.setLetterSpacing(.14f);
        FrameLayout.LayoutParams positionParams = new FrameLayout.LayoutParams(dp(100), dp(24), Gravity.RIGHT | Gravity.TOP);
        positionParams.setMargins(0, dp(tileTop - 48), dp(56), 0);
        root.addView(position, positionParams);

        scroll = new TvStyle.Rail(this);
        scroll.setPadding(dp(56), dp(34), dp(56), dp(20));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        scroll.addView(rows, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(-1, dp(railHeight), Gravity.TOP);
        railParams.topMargin = dp(tileTop - 34);
        root.addView(scroll, railParams);

        notifications = new NotificationsPanel(this, () -> focusTile(lastFocused));
        FrameLayout.LayoutParams notificationParams = new FrameLayout.LayoutParams(
                dp(Math.min(584, getResources().getConfiguration().screenWidthDp - 112)), dp(64),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        notificationParams.bottomMargin = dp(32);
        root.addView(notifications.button, notificationParams);

        setContentView(root);
        hideSystemBars();
    }

    private LinearLayout heroBody() {
        LinearLayout body = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("", 32, WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setLetterSpacing(-.02f);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(46)));
        return body;
    }

    private void addTool(LinearLayout header, String label, int symbol, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.setId(View.generateViewId());
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(new TvStyle.Surface(this, 24, false));
        TvStyle.Symbol icon = new TvStyle.Symbol(this, symbol);
        button.addView(icon, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        button.setOnClickListener(v -> action.run());
        button.setOnFocusChangeListener((v, focused) -> {
            icon.tint(focused ? 0xff242424 : WHITE);
            button.setBackground(new TvStyle.Surface(this, 24, focused));
            button.animate().cancel();
            button.animate().scaleX(focused ? 1.055f : 1).scaleY(focused ? 1.055f : 1)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).setDuration(340).start();
        });
        button.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusTile(lastFocused);
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.leftMargin = dp(12);
        header.addView(button, params);
        tools.add(button);
    }

    @Override protected void onStart() {
        super.onStart();
        notifications.start();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        IntentFilter close = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packages, filter, Context.RECEIVER_EXPORTED);
            registerReceiver(systemDialogs, close, Context.RECEIVER_EXPORTED);
        } else { registerReceiver(packages, filter); registerReceiver(systemDialogs, close); }
        receiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        hideSystemBars();
        refreshApps();
        if (restoreLastFocus && !moving) {
            focusTile(lastFocused);
            restoreLastFocus = false;
        }
        main.post(heroUpdate);
    }

    @Override protected void onStop() {
        active = false;
        notifications.stop();
        if (activeMenu != null) activeMenu.dismiss();
        main.removeCallbacks(heroUpdate);
        heroCurrent.animate().cancel(); heroPrevious.animate().cancel();
        heroAnimating = false; heroCurrent.setAlpha(1); heroPrevious.setAlpha(0);
        if (receiverRegistered) {
            unregisterReceiver(packages); unregisterReceiver(systemDialogs); receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("focus", lastFocused);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        returnToFirst();
    }

    private void returnToFirst() {
        notifications.dismiss();
        if (activeMenu != null) activeMenu.dismiss();
        if (moving) finishMove(false);
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
        if (!tiles.isEmpty()) scroll.reveal(tiles.get(0), true);
    }

    private void refreshApps() {
        if (destroyed) return;
        if (loading || moving) { reload = true; return; }
        loading = true;
        worker.execute(() -> {
            List<AppCatalog.Entry> loaded;
            try { loaded = catalog.load(); }
            catch (RuntimeException failure) {
                main.post(() -> { loading = false; if (!destroyed) toast("Не удалось обновить приложения"); });
                return;
            }
            main.post(() -> {
                loading = false;
                if (destroyed) return;
                if (moving) { reload = true; return; }
                boolean changed = loaded.size() != all.size();
                if (!changed) for (int i = 0; i < loaded.size(); i++) {
                    if (!loaded.get(i).cacheKey.equals(all.get(i).cacheKey)) { changed = true; break; }
                }
                if (changed || tiles.isEmpty()) {
                    all.clear(); all.addAll(loaded);
                    arrangeVisible();
                    renderGrid(lastFocused);
                }
                if (reload) { reload = false; refreshApps(); }
            });
        });
    }

    private List<String> savedOrder() {
        ArrayList<String> order = new ArrayList<>();
        try {
            JSONArray json = new JSONArray(preferences.getString("order", "[]"));
            for (int i = 0; i < json.length(); i++) order.add(json.getString(i));
        } catch (JSONException ignored) { }
        return order;
    }

    private void arrangeVisible() {
        Set<String> hidden = preferences.getStringSet("hidden", Collections.emptySet());
        List<String> order = savedOrder();
        visible.clear();
        for (AppCatalog.Entry entry : all) if (!hidden.contains(entry.id())) visible.add(entry);
        // A stable sort keeps discovery order for newly installed applications.
        visible.sort((a, b) -> {
            int x = order.indexOf(a.id()), y = order.indexOf(b.id());
            return Integer.compare(x < 0 ? Integer.MAX_VALUE : x, y < 0 ? Integer.MAX_VALUE : y);
        });
    }

    private void renderGrid(String focusId) {
        int renderGeneration = ++generation;
        HashMap<String, Integer> previousPositions = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) previousPositions.put(tiles.get(i).entry.id(), i);
        rows.removeAllViews();
        tiles.clear();
        int tileWidth = Math.round(getResources().getConfiguration().screenHeightDp / 3f * 16 / 9);
        columns = Math.max(1, visible.size());
        for (int i = 0; i < visible.size(); i++) {
            AppCatalog.Entry entry = visible.get(i);
            AppTile tile = new AppTile(entry, tileWidth);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(tileWidth), -2);
            if (i != 0) tp.leftMargin = dp(32);
            rows.addView(tile, tp);
            tiles.add(tile);
            Integer previous = previousPositions.get(entry.id());
            if (moving && previous != null && previous != i) {
                tile.setTranslationX(dp((previous - i) * (tileWidth + 32)));
                tile.animate().translationX(0).setDuration(380).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }
            AppCatalog.Picture cached = catalog.cachedPicture(entry);
            if (cached != null) { tile.setPicture(cached); continue; }
            worker.execute(() -> {
                try {
                    AppCatalog.Picture picture = catalog.picture(entry);
                    main.post(() -> {
                        if (!destroyed && generation == renderGeneration) tile.setPicture(picture);
                    });
                } catch (RuntimeException ignored) { /* Keep the readable label placeholder. */ }
            });
        }
        if (visible.isEmpty()) {
            TextView empty = text("Приложения скрыты. Их можно вернуть в настройках.", 18, MUTED);
            empty.setPadding(0, dp(44), dp(24), dp(40));
            rows.addView(empty);
            heroTitle.setText("Ваш экран");
            selectedId = null; displayedId = null; pendingHero = null;
            main.removeCallbacks(heroUpdate);
            heroCurrent.animate().cancel(); heroPrevious.animate().cancel();
            heroAnimating = false; heroCurrent.setAlpha(1); heroPrevious.setAlpha(0);
            position.setText("");
            tools.get(1).requestFocus();
        } else {
            for (int i = 0; i < tiles.size(); i++) {
                AppTile tile = tiles.get(i);
                tile.setNextFocusLeftId(tiles.get(GridNavigation.neighbor(i, -1, 0, columns, tiles.size())).getId());
                tile.setNextFocusRightId(tiles.get(GridNavigation.neighbor(i, 1, 0, columns, tiles.size())).getId());
                tile.setNextFocusDownId(notifications.button.getId());
                tile.setNextFocusUpId(tools.get(0).getId());
            }
            tools.get(0).setNextFocusDownId(tiles.get(0).getId());
            tools.get(1).setNextFocusDownId(tiles.get(Math.min(columns - 1, tiles.size() - 1)).getId());
            focusTile(focusId);
        }
    }

    private void focusTile(String id) {
        if (tiles.isEmpty()) return;
        for (AppTile tile : tiles) if (tile.entry.id().equals(id)) { tile.requestFocus(); return; }
        tiles.get(0).requestFocus();
    }

    private final class AppTile extends LinearLayout {
        final AppCatalog.Entry entry;
        final ImageView image;
        final TextView placeholder;
        final FrameLayout card;
        final View border;
        int accent = 0xffd6c6ad;
        final TvStyle.FocusGlow glow;
        AppTile(AppCatalog.Entry entry, int width) {
            super(MainActivity.this);
            this.entry = entry;
            setId(View.generateViewId());
            setOrientation(VERTICAL);
            setFocusable(true);
            setClickable(true);
            setClipChildren(false);
            setClipToPadding(false);
            glow = new TvStyle.FocusGlow(MainActivity.this);
            setBackground(glow);
            setContentDescription(entry.label);
            card = new FrameLayout(MainActivity.this);
            card.setBackground(shape(0xff292929, 14, 0, 0));
            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new FrameLayout.LayoutParams(-1, -1));
            placeholder = text(entry.label, 22, WHITE);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(2);
            placeholder.setPadding(dp(10), 0, dp(10), 0);
            card.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            border = new View(MainActivity.this);
            card.addView(border, new FrameLayout.LayoutParams(-1, -1));
            addView(card, new LinearLayout.LayoutParams(-1, dp(Math.round(width * 9f / 16))));
            setOnFocusChangeListener((view, focused) -> {
                animate().cancel();
                animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f)
                        .translationY(0).translationX(0).setDuration(420)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
                glow.focus(focused);
                updateBorder(focused);
                if (focused) {
                    lastFocused = entry.id();
                    selectEntry(entry);
                    scroll.post(() -> { if (isFocused() && !destroyed) scroll.reveal(this, false); });
                }
            });
            addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (isFocused() && (oldRight == oldLeft || oldLeft != left)) scroll.reveal(this, true);
            });
            setOnClickListener(view -> launch(entry));
            setOnLongClickListener(view -> { showAppMenu(entry); return true; });
            setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT
                        && key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) tools.get(0).requestFocus();
                    else if (key == KeyEvent.KEYCODE_DPAD_DOWN) notifications.button.requestFocus();
                    else {
                        int index = tiles.indexOf(this);
                        int next = GridNavigation.neighbor(index, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, 0, columns, tiles.size());
                        tiles.get(next).requestFocus();
                    }
                }
                return true;
            });
        }
        private void updateBorder(boolean focused) {
            int outline = Color.rgb((Color.red(accent) + 765) / 4,
                    (Color.green(accent) + 765) / 4, (Color.blue(accent) + 765) / 4);
            border.setBackground(focused ? shape(Color.TRANSPARENT, 14, outline, 1.5f) : null);
        }
        void setPicture(AppCatalog.Picture picture) {
            image.setImageBitmap(picture.bitmap);
            accent = picture.accent;
            glow.setTint(accent);
            updateBorder(isFocused());
            placeholder.setVisibility(View.GONE);
            card.setBackground(null);
        }
        @Override protected void onDetachedFromWindow() {
            glow.stop();
            super.onDetachedFromWindow();
        }
    }

    private void selectEntry(AppCatalog.Entry entry) {
        int index = visible.indexOf(entry);
        position.setText(String.format(java.util.Locale.ROOT, "%02d  /  %02d", index + 1, visible.size()));
        pendingHero = entry;
        if (entry.id().equals(selectedId)) return;
        selectedId = entry.id();
        main.removeCallbacks(heroUpdate);
        if (displayedId == null) {
            heroTitle.setText(entry.label);
            displayedId = entry.id();
        } else main.postDelayed(heroUpdate, 140);
    }

    private void updateHero() {
        if (destroyed || !active || pendingHero == null || heroAnimating || pendingHero.id().equals(displayedId)) return;
        previousTitle.setText(heroTitle.getText());
        heroTitle.setText(pendingHero.label);
        displayedId = pendingHero.id();
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        heroAnimating = true;
        heroPrevious.setAlpha(1); heroCurrent.setAlpha(0);
        heroPrevious.animate().alpha(0).setDuration(400).setInterpolator(new DecelerateInterpolator()).start();
        heroCurrent.animate().alpha(1).setDuration(440).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    heroAnimating = false;
                    if (active && !destroyed) main.postDelayed(heroUpdate, 140);
                }).start();
    }

    private void launch(AppCatalog.Entry entry) {
        restoreLastFocus = true;
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        open(intent);
    }

    private void showAppMenu(AppCatalog.Entry entry) {
        showMenu(entry.label, "Настройте ленту приложений",
                new String[]{"Переместить", "Скрыть"}, new int[]{TvStyle.MOVE, TvStyle.HIDE}, item -> {
                    if (item == 0) {
                        moving = true; movingId = entry.id(); beforeMove = new ArrayList<>(visible);
                        focusTile(movingId);
                    } else {
                        Set<String> hidden = new HashSet<>(preferences.getStringSet("hidden", Collections.emptySet()));
                        hidden.add(entry.id()); preferences.edit().putStringSet("hidden", hidden).apply();
                        arrangeVisible(); renderGrid(null);
                    }
                });
    }

    private void finishMove(boolean save) {
        if (!moving) return;
        if (save) {
            JSONArray order = new JSONArray();
            for (AppCatalog.Entry entry : visible) order.put(entry.id());
            preferences.edit().putString("order", order.toString()).apply();
        } else { visible.clear(); visible.addAll(beforeMove); renderGrid(movingId); }
        moving = false; beforeMove = null;
        if (reload) { reload = false; refreshApps(); }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && key == consumedKeyUp) {
            consumedKeyUp = -1; return true;
        }
        if (!moving) return super.dispatchKeyEvent(event);
        boolean handled = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) { consumedKeyUp = key; finishMove(true); }
            } else if (key == KeyEvent.KEYCODE_BACK) { consumedKeyUp = key; finishMove(false); }
            else {
                int index = 0;
                for (int i = 0; i < visible.size(); i++) if (visible.get(i).id().equals(movingId)) { index = i; break; }
                int dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                int next = GridNavigation.neighbor(index, dx, dy, columns, visible.size());
                if (next != index) { Collections.swap(visible, index, next); renderGrid(movingId); }
            }
        }
        return true;
    }

    private void showSettings() {
        showMenu("Настройки", "Quiet TV",
                new String[]{"Настройки телевизора", "Вернуть скрытые приложения", "Сделать главным экраном"},
                new int[]{TvStyle.SETTINGS, TvStyle.RESTORE, TvStyle.HOME}, item -> {
                            if (item == 0) open(new Intent(Settings.ACTION_SETTINGS));
                            if (item == 1) showHidden();
                            if (item == 2) chooseHome();
                        });
    }

    private void showHidden() {
        Set<String> hidden = preferences.getStringSet("hidden", Collections.emptySet());
        ArrayList<AppCatalog.Entry> hiddenEntries = new ArrayList<>();
        for (AppCatalog.Entry entry : all) if (hidden.contains(entry.id())) hiddenEntries.add(entry);
        if (hiddenEntries.isEmpty()) { toast("Скрытых приложений нет"); return; }
        String[] labels = new String[hiddenEntries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenEntries.get(i).label;
        showMenu("Скрытые приложения", "Выберите приложение, чтобы вернуть его в ленту", labels, null, item -> {
            Set<String> updated = new HashSet<>(hidden);
            updated.remove(hiddenEntries.get(item).id());
            preferences.edit().putStringSet("hidden", updated).apply();
            arrangeVisible(); renderGrid(hiddenEntries.get(item).id());
        });
    }

    private interface MenuAction { void run(int item); }

    private void showMenu(String title, String subtitle, String[] labels, int[] icons, MenuAction action) {
        if (activeMenu != null) activeMenu.dismiss();
        Dialog dialog = new Dialog(this);
        activeMenu = dialog;
        dialog.setOnDismissListener(ignored -> { if (activeMenu == dialog) activeMenu = null; });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(24), dp(14), dp(14));
        panel.setBackground(new TvStyle.Surface(this, 22, false, 0xff202020));
        panel.setElevation(dp(24));
        TextView heading = text(title, 25, WHITE);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(Typeface.create("sans-serif-medium", 0));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(35)));
        TextView detail = text(subtitle, 12, MUTED);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, dp(32));
        detailParams.bottomMargin = dp(12);
        panel.addView(detail, detailParams);
        ScrollView list = new ScrollView(this);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(dp(4), dp(3), dp(4), dp(3));
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setClipChildren(false);
        list.addView(choices);
        for (int i = 0; i < labels.length; i++) {
            final int item = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(15), 0);
            row.setFocusable(true); row.setClickable(true);
            row.setContentDescription(labels[i]);
            row.setBackground(new TvStyle.Surface(this, 13, false, 0xff292929));
            TvStyle.Symbol icon = new TvStyle.Symbol(this, icons == null ? TvStyle.RESTORE : icons[i]);
            row.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));
            TextView label = text(labels[i], 16, WHITE);
            label.setTypeface(Typeface.create("sans-serif-medium", 0));
            label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.leftMargin = dp(13);
            row.addView(label, textParams);
            TvStyle.Symbol arrow = new TvStyle.Symbol(this, TvStyle.CHEVRON);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
            row.setOnFocusChangeListener((view, focused) -> {
                row.setBackground(new TvStyle.Surface(this, 13, focused, 0xff292929));
                int color = focused ? 0xff242424 : WHITE;
                icon.tint(color); arrow.tint(color); label.setTextColor(color);
                row.animate().cancel();
                row.animate().scaleX(focused ? 1.012f : 1f).scaleY(focused ? 1.012f : 1f).setDuration(300).start();
            });
            row.setOnClickListener(view -> {
                dialog.dismiss();
                main.postDelayed(() -> { if (!destroyed) action.run(item); }, 140);
            });
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(-1, dp(48));
            if (i != 0) choiceParams.topMargin = dp(7);
            choices.addView(row, choiceParams);
        }
        int maxListHeight = Math.max(100, getResources().getConfiguration().screenHeightDp - 230);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(Math.min(maxListHeight, labels.length * 55 - 1))));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.48f);
        window.setWindowAnimations(R.style.MenuAnimation);
        window.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
        dialog.show();
        window.setLayout(dp(420), -2);
        window.setGravity(Gravity.CENTER);
        if (choices.getChildCount() > 0) choices.getChildAt(0).requestFocus();
    }

    private void chooseHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) { toast("Quiet TV уже выбран главным экраном"); return; }
                open(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)); return;
            }
        }
        open(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void openInputs() {
        // The Philips input picker lives in LiveTV; keep its normal TV integration.
        Intent inputs = new Intent("com.android.tv.action.VIEW_INPUTS").setPackage("com.mediatek.wwtv.tvcenter");
        try { startActivity(inputs); }
        catch (android.content.ActivityNotFoundException | SecurityException missing) {
            Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
            if (tv != null) open(tv); else toast("Используйте кнопку выбора источника на пульте");
        }
    }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException missing) {
            toast("Экран недоступен на этой версии ТВ");
            refreshApps();
        }
    }

    @Override public void onBackPressed() {
        if (moving) finishMove(false);
        else if (!tiles.isEmpty()) { tiles.get(0).requestFocus(); scroll.reveal(tiles.get(0), false); }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (catalog != null && level >= TRIM_MEMORY_RUNNING_LOW) catalog.clearPictures();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setIncludeFontPadding(false);
        return text;
    }

    private GradientDrawable shape(int color, int radius, int stroke, float width) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (width > 0) shape.setStroke(dp(width), stroke);
        return shape;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) {
        TextView bubble = text(message, 14, WHITE);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(dp(22), 0, dp(22), 0);
        bubble.setBackground(new TvStyle.Surface(this, 23, false, 0xff292929));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(83);
        root.addView(bubble, params);
        bubble.setAlpha(0); bubble.setTranslationY(-dp(7));
        bubble.animate().alpha(1).translationY(0).setDuration(300).start();
        main.postDelayed(() -> bubble.animate().alpha(0).translationY(-dp(5)).setDuration(260)
                .withEndAction(() -> root.removeView(bubble)).start(), 2300);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (activeMenu != null) activeMenu.dismiss();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
