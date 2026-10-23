package local.quiet.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Package queries and image decoding run on the activity's single worker thread. */
final class AppCatalog {
    static final class Entry {
        final ComponentName component;
        final String label;
        final ResolveInfo info;
        final String cacheKey;
        Entry(ResolveInfo info, PackageManager pm) {
            this.info = info;
            component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            label = info.loadLabel(pm).toString();
            long updated = 0;
            try { updated = pm.getPackageInfo(component.getPackageName(), 0).lastUpdateTime; }
            catch (PackageManager.NameNotFoundException ignored) { }
            cacheKey = component.flattenToString() + ":" + updated + ":" + label;
        }
        String id() { return component.getPackageName(); }
    }

    static final class Picture {
        final Bitmap bitmap;
        final int accent;
        Picture(Bitmap bitmap, int accent) { this.bitmap = bitmap; this.accent = accent; }
    }

    private final PackageManager pm;
    private final String ownPackage;
    private final LruCache<String, Picture> pictures = new LruCache<String, Picture>(8192) {
        @Override protected int sizeOf(String key, Picture value) { return value.bitmap.getByteCount() / 1024; }
    };
    AppCatalog(Context context) { pm = context.getPackageManager(); ownPackage = context.getPackageName(); }

    List<Entry> load() {
        HashMap<String, Entry> entries = new HashMap<>();
        // Prefer the TV activity when a package also exposes a phone launcher activity.
        for (String category : Arrays.asList(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(ownPackage) || pkg.equals("com.android.tv.settings")
                        || pkg.equals("com.google.android.tvlauncher") || entries.containsKey(pkg)
                        || !info.activityInfo.exported || !info.activityInfo.enabled) continue;
                entries.put(pkg, new Entry(info, pm));
            }
        }
        ArrayList<Entry> result = new ArrayList<>(entries.values());
        List<String> first = Arrays.asList("com.google.android.youtube.tv", "ru.kinopoisk.tv",
                "mobi.zona", "rtb.mobile.android", "com.vk.tv");
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort((a, b) -> {
            int x = first.indexOf(a.id()), y = first.indexOf(b.id());
            if (x >= 0 || y >= 0) return Integer.compare(x < 0 ? 100 : x, y < 0 ? 100 : y);
            int labelOrder = collator.compare(a.label, b.label);
            return labelOrder != 0 ? labelOrder : a.id().compareTo(b.id());
        });
        return result;
    }

    Picture picture(Entry entry) {
        Picture cached = pictures.get(entry.cacheKey);
        if (cached != null) return cached;
        Drawable drawable = null;
        try { drawable = entry.info.activityInfo.loadBanner(pm); }
        catch (RuntimeException ignored) { }
        boolean banner = drawable != null;
        if (drawable == null) drawable = entry.info.loadIcon(pm);
        Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(41, 41, 41));
        if (drawable != null) {
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            float scale = banner ? Math.max(640f / width, 360f / height) : 224f / Math.max(width, height);
            int w = Math.round(width * scale), h = Math.round(height * scale);
            drawable.setBounds((640 - w) / 2, (360 - h) / 2, (640 + w) / 2, (360 + h) / 2);
            drawable.draw(canvas);
        }
        bitmap.setHasAlpha(false);
        Bitmap rounded = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(rounded).drawRoundRect(0, 0, 640, 360, 28, 28, paint);
        Picture picture = new Picture(rounded, dominantColor(bitmap));
        pictures.put(entry.cacheKey, picture);
        return picture;
    }

    // A small hue histogram avoids grey backgrounds and white wordmarks dominating the glow.
    // This runs once on the worker, never during focus changes or drawing.
    private static int dominantColor(Bitmap bitmap) {
        double[] weights = new double[24], red = new double[24], green = new double[24], blue = new double[24];
        float[] hsv = new float[3];
        for (int y = 12; y < bitmap.getHeight(); y += 24) {
            for (int x = 12; x < bitmap.getWidth(); x += 24) {
                int color = bitmap.getPixel(x, y);
                Color.colorToHSV(color, hsv);
                if (hsv[1] < .22f || hsv[2] < .15f) continue;
                int bucket = Math.min(23, (int) (hsv[0] / 15));
                double weight = hsv[1] * (.3 + hsv[2]);
                weights[bucket] += weight;
                red[bucket] += Color.red(color) * weight;
                green[bucket] += Color.green(color) * weight;
                blue[bucket] += Color.blue(color) * weight;
            }
        }
        int best = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        if (weights[best] == 0) return 0xffc3b8a7;
        int color = Color.rgb((int) (red[best] / weights[best]), (int) (green[best] / weights[best]),
                (int) (blue[best] / weights[best]));
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(.85f, hsv[1]);
        hsv[2] = Math.max(.65f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    void clearPictures() { pictures.evictAll(); }
    Picture cachedPicture(Entry entry) { return pictures.get(entry.cacheKey); }
}
