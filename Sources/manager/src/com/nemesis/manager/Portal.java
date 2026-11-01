package com.nemesis.manager;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** xwtk.Portal over HTTPS. Endpoints are described in NEMESIS-API.md. */
final class Portal {
    /** Placeholder: nothing resolves here until the real host is filled in. */
    private static final String NET_PREFS = "network";
    private static final String KEY_TELEMATICS = "use_telematics";
    static final String BASE_URL = "https://portal.xwtk.cloud/nemesis/v1";
    static final String PATH_UPDATE = "/update/latest";
    static final String PATH_CATALOG = "/store/catalog";
    static final String PATH_PACKAGE = "/store/packages/";
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 30000;
    private static final int MAX_JSON = 2 * 1024 * 1024;

    static final class Update {
        String version;
        String url;
        String sha256;
    }

    private static SSLSocketFactory factory;

    private Portal() {
    }

    /** Newer update for this unit, or null when it is up to date. Throws when unreachable. */
    static Update latestUpdate(Context c, String current, String os, String model) throws Exception {
        JSONObject o = new JSONObject(getText(c, BASE_URL + PATH_UPDATE
                + "?current=" + enc(current) + "&os=" + enc(os) + "&model=" + enc(model)));
        if (!o.optBoolean("update", false)) return null;
        Update u = new Update();
        u.version = o.getString("version");
        u.url = o.getString("url");
        u.sha256 = o.getString("sha256");
        if (UpdateRule.compare(u.version, current) <= 0) return null;
        return u;
    }

    static List<StoreList.Entry> catalog(Context c) throws Exception {
        JSONObject o = new JSONObject(getText(c, BASE_URL + PATH_CATALOG));
        JSONArray a = o.getJSONArray("packages");
        List<StoreList.Entry> out = new ArrayList<StoreList.Entry>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            StoreList.Entry e = new StoreList.Entry();
            e.id = p.getString("id");
            e.name = p.optString("name", e.id);
            e.shortDesc = p.optString("short-desc", "");
            e.published = p.optString("published", "");
            out.add(e);
        }
        return out;
    }

    /** The descriptor-by-id endpoint: os, models, depends, package-version, url, sha256. */
    static JSONObject descriptor(Context c, String id) throws Exception {
        return new JSONObject(getText(c, BASE_URL + PATH_PACKAGE + enc(id)));
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    static boolean useTelematics(Context c) {
        return c.getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TELEMATICS, false);
    }

    static void setUseTelematics(Context c, boolean on) {
        c.getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TELEMATICS, on).commit();
    }

    /** The one gate for all of Manager's traffic: Wi-Fi or a hotspot, or Use Telematics Internet. */
    private static void checkNetwork(Context c) throws IOException {
        if (useTelematics(c)) return;
        int type = -1;
        try {
            NetworkInfo n = ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .getActiveNetworkInfo();
            if (n != null && n.isConnected()) type = n.getType();
        } catch (Throwable ignored) {
        }
        String iface = UpdateRule.defaultRouteIface(readRoutes());
        if (UpdateRule.networkAllowed(false, type, iface)) return;
        CrashLog.note("network.log", "blocked type=" + type + " iface=" + iface);
        throw new IOException("blocked by network policy");
    }

    private static String readRoutes() {
        try {
            InputStream in = new java.io.FileInputStream("/proc/net/route");
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static HttpURLConnection open(Context c, String url) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("not https: " + url);
        checkNetwork(c);
        HttpsURLConnection h = (HttpsURLConnection) new URL(url).openConnection();
        h.setSSLSocketFactory(socketFactory(c));
        h.setConnectTimeout(CONNECT_MS);
        h.setReadTimeout(READ_MS);
        h.setRequestProperty("User-Agent", "NEMESIS/" + Ui.versionName(c));
        return h;
    }

    private static String getText(Context c, String url) throws Exception {
        HttpURLConnection h = open(c, url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_JSON) throw new IOException("reply too large");
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /** Download into the Manager's private cache, so no other app can swap the
     *  file between the hash check and the install. Null when the hash is wrong. */
    static File download(Context c, String url, String sha256, String name) throws Exception {
        File dir = c.getCacheDir();
        File out = new File(dir, name);
        File tmp = new File(dir, name + ".part");
        HttpURLConnection h = open(c, url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = h.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
            if (!hex(md.digest()).equalsIgnoreCase(sha256 == null ? "" : sha256.trim())) {
                tmp.delete();
                return null;
            }
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
            // nemesis runs as root and reads the file by path.
            return out;
        } finally {
            h.disconnect();
            tmp.delete();
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 15, 16));
            sb.append(Character.forDigit(b[i] & 15, 16));
        }
        return sb.toString();
    }

    private static synchronized SSLSocketFactory socketFactory(Context c) throws Exception {
        if (factory != null) return factory;
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, new TrustManager[] { new RootsTrust(c) }, null);
        factory = new Tls12Factory(ctx.getSocketFactory());
        return factory;
    }

    private static X509TrustManager trustFor(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        TrustManager[] all = f.getTrustManagers();
        for (int i = 0; i < all.length; i++) {
            if (all[i] instanceof X509TrustManager) return (X509TrustManager) all[i];
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** The system store first, then the roots bundled in the APK: HTTPS works
     *  before cacerts-install has run, and after a stock image restores /system. */
    private static final class RootsTrust implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        RootsTrust(Context c) throws Exception {
            system = trustFor(null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] parts = readRoots(c).split("-----END CERTIFICATE-----");
            int n = 0;
            for (int i = 0; i < parts.length; i++) {
                int at = parts[i].indexOf("-----BEGIN CERTIFICATE-----");
                if (at < 0) continue;
                String pem = parts[i].substring(at) + "-----END CERTIFICATE-----\n";
                ks.setCertificateEntry("root" + n++,
                        cf.generateCertificate(new ByteArrayInputStream(pem.getBytes("US-ASCII"))));
            }
            bundled = trustFor(ks);
        }

        private static String readRoots(Context c) throws IOException {
            InputStream in = c.getResources().openRawResource(R.raw.nemesis_roots);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("US-ASCII");
            } finally {
                in.close();
            }
        }

        public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            system.checkClientTrusted(chain, auth);
        }

        public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            try {
                system.checkServerTrusted(chain, auth);
            } catch (CertificateException e) {
                bundled.checkServerTrusted(chain, auth);
            }
        }

        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }

    /** KitKat supports TLS 1.2 but leaves it off by default. */
    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12Factory(SSLSocketFactory d) {
            this.d = d;
        }

        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[] { "TLSv1.2" });
            }
            return s;
        }

        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return on(d.createSocket(s, host, port, autoClose));
        }

        public Socket createSocket(String host, int port) throws IOException {
            return on(d.createSocket(host, port));
        }

        public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException {
            return on(d.createSocket(host, port, la, lp));
        }

        public Socket createSocket(InetAddress host, int port) throws IOException {
            return on(d.createSocket(host, port));
        }

        public Socket createSocket(InetAddress host, int port, InetAddress la, int lp) throws IOException {
            return on(d.createSocket(host, port, la, lp));
        }
    }
}
