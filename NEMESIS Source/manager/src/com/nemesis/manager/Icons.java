package com.nemesis.manager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

/** Package favicons (metadata 1.2). No favicon, or one that will not decode, shows the
 *  generic app icon. */
final class Icons {
    private static final int EDGE = 96;
    // shortcut: cached by path for the process life, so a favicon replaced by an update shows after a restart.
    private static final Map<String, Bitmap> BY_PATH = new HashMap<String, Bitmap>();
    private static final Map<String, String> BY_ID = new HashMap<String, String>();

    private Icons() {
    }

    static void set(ImageView v, String path) {
        Bitmap b = path == null || path.length() == 0 ? null : get(path);
        if (b != null) v.setImageBitmap(b);
        else v.setImageResource(android.R.drawable.sym_def_app_icon);
    }

    /** Queue jobs only know the id: the favicon last seen for it. */
    static synchronized void remember(String id, String path) {
        if (id != null && id.length() > 0 && path != null && path.length() > 0) BY_ID.put(id, path);
    }

    static synchronized String forId(String id) {
        String p = BY_ID.get(id);
        return p == null ? "" : p;
    }

    private static synchronized Bitmap get(String path) {
        if (BY_PATH.containsKey(path)) return BY_PATH.get(path);
        Bitmap b = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= EDGE && o.outHeight / (s * 2) >= EDGE) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            b = BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            b = null;
        }
        BY_PATH.put(path, b);
        return b;
    }
}
