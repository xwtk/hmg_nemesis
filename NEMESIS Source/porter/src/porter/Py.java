package porter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Python built-ins the original pipeline leans on, with Python's exact semantics. Java 7 API only. */
final class Py {
    private Py() {}

    static final Charset UTF8 = Charset.forName("UTF-8");

    /** str.isspace() for one char */
    static boolean space(char c) {
        if (c == ' ' || (c >= '\t' && c <= '\r') || (c >= 0x1c && c <= 0x1f) || c == 0x85 || c == 0xa0)
            return true;
        return c >= 0x1680 && (c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029
                || c == 0x202f || c == 0x205f || c == 0x3000);
    }

    static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && space(s.charAt(a)))
            a++;
        while (b > a && space(s.charAt(b - 1)))
            b--;
        return s.substring(a, b);
    }

    /** str.split() with no arguments */
    static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && space(s.charAt(i)))
                i++;
            int j = i;
            while (j < n && !space(s.charAt(j)))
                j++;
            if (j > i)
                out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0)
            o.write(buf, 0, n);
        return o.toByteArray();
    }

    static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readBytes(in);
        } finally {
            in.close();
        }
    }

    /** open(f, encoding="utf-8").read(): universal newlines */
    static String readText(File f) throws IOException {
        return newlines(new String(readBytes(f), UTF8));
    }

    static String newlines(String s) {
        if (s.indexOf('\r') < 0)
            return s;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    static void writeBytes(File f, byte[] b) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs())
            throw new IOException("mkdir " + p);
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(b);
        } finally {
            o.close();
        }
    }

    static void writeText(File f, String s) throws IOException {
        writeBytes(f, s.getBytes(UTF8));
    }

    static String sha1hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF8));
            StringBuilder b = new StringBuilder();
            for (byte x : d)
                b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    interface Rep {
        String rep(java.util.regex.Matcher m);
    }

    /** re.sub(p, fn, s); returns s itself when nothing matches */
    static String sub(java.util.regex.Pattern p, String s, Rep r) {
        java.util.regex.Matcher m = p.matcher(s);
        if (!m.find())
            return s;
        StringBuilder b = new StringBuilder(s.length() + 16);
        int last = 0;
        do {
            b.append(s, last, m.start()).append(r.rep(m));
            last = m.end();
        } while (m.find());
        return b.append(s, last, s.length()).toString();
    }

    static String join(String sep, List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                b.append(sep);
            b.append(parts.get(i));
        }
        return b.toString();
    }

    // ---- json.dumps(..., ensure_ascii=True) for str / int / list / dict ----

    static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c > 0x7f)
                        b.append(String.format("\\u%04x", (int) c));
                    else
                        b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** json.dump(v, f, indent=indent); keys in the map's own order (callers sort when Python did) */
    static String json(Object v, int indent) {
        StringBuilder b = new StringBuilder();
        json(b, v, indent, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void json(StringBuilder b, Object v, int ind, int lvl) {
        if (v instanceof String) {
            b.append(jstr((String) v));
        } else if (v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                b.append(i == 0 ? "" : ",");
                nl(b, ind, lvl + 1);
                json(b, l.get(i), ind, lvl + 1);
            }
            nl(b, ind, lvl);
            b.append(']');
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                b.append(first ? "" : ",");
                first = false;
                nl(b, ind, lvl + 1);
                b.append(jstr(e.getKey())).append(": ");
                json(b, e.getValue(), ind, lvl + 1);
            }
            nl(b, ind, lvl);
            b.append('}');
        } else {
            throw new IllegalArgumentException("json: " + v);
        }
    }

    private static void nl(StringBuilder b, int ind, int lvl) {
        b.append('\n');
        for (int i = 0; i < ind * lvl; i++)
            b.append(' ');
    }
}
