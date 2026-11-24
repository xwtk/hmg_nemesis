package porter;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * port.py's res_ids() without aapt: "type/name" -> "0x7fTTEEEE" from resources.arsc, the names aapt
 * prints as "spec resource" lines (an entry with a value in at least one config of package 0x7f).
 */
final class Arsc {
    private Arsc() {}

    static Map<String, String> resIds(File apk) throws IOException {
        ZipFile z = new ZipFile(apk);
        try {
            ZipEntry e = z.getEntry("resources.arsc");
            if (e == null)
                throw new IOException(apk + ": no resources.arsc");
            return resIds(Py.readBytes(z.getInputStream(e)));
        } finally {
            z.close();
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static int u32(byte[] b, int o) {
        return u16(b, o) | u16(b, o + 2) << 16;
    }

    static String[] pool(byte[] b, int o) {
        if (u16(b, o) != 0x0001)
            throw new IllegalArgumentException("not a string pool at " + o);
        int hdr = u16(b, o + 2), n = u32(b, o + 8), flags = u32(b, o + 16), start = u32(b, o + 20);
        boolean utf8 = (flags & 0x100) != 0;
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            int p = o + start + u32(b, o + hdr + 4 * i);
            if (utf8) {
                p += (b[p] & 0x80) != 0 ? 2 : 1; // UTF-16 length, unused
                int len = b[p] & 0xff;
                if ((len & 0x80) != 0) {
                    len = (len & 0x7f) << 8 | (b[p + 1] & 0xff);
                    p += 2;
                } else
                    p += 1;
                out[i] = new String(b, p, len, Py.UTF8);
            } else {
                int len = u16(b, p);
                if ((len & 0x8000) != 0) {
                    len = (len & 0x7fff) << 16 | u16(b, p + 2);
                    p += 4;
                } else
                    p += 2;
                char[] c = new char[len];
                for (int k = 0; k < len; k++)
                    c[k] = (char) u16(b, p + 2 * k);
                out[i] = new String(c);
            }
        }
        return out;
    }

    static Map<String, String> resIds(byte[] b) {
        if (u16(b, 0) != 0x0002)
            throw new IllegalArgumentException("not a resource table");
        // resource id -> name, ids ascending: the order aapt prints them in
        TreeMap<Long, String> names = new TreeMap<Long, String>();
        int o = u16(b, 2);
        while (o < b.length) {
            int type = u16(b, o), size = u32(b, o + 4);
            if (type == 0x0200 && u32(b, o + 8) == 0x7f)
                pkg(b, o, size, names);
            o += size;
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<Long, String> e : names.entrySet())
            out.put(e.getValue(), String.format("0x%08x", e.getKey()));
        return out;
    }

    private static void pkg(byte[] b, int o, int size, TreeMap<Long, String> names) {
        int hdr = u16(b, o + 2);
        String[] types = pool(b, o + u32(b, o + 268)), keys = pool(b, o + u32(b, o + 276));
        for (int c = o + hdr; c < o + size; c += u32(b, c + 4)) {
            if (u16(b, c) != 0x0201)
                continue;
            int id = b[c + 8] & 0xff, n = u32(b, c + 12), start = u32(b, c + 16), chdr = u16(b, c + 2);
            for (int i = 0; i < n; i++) {
                int off = u32(b, c + chdr + 4 * i);
                if (off == -1)
                    continue;
                int key = u32(b, c + start + off + 4);
                long rid = 0x7f000000L | (long) id << 16 | i;
                if (!names.containsKey(rid))
                    names.put(rid, types[id - 1] + "/" + keys[key]);
            }
        }
    }
}
