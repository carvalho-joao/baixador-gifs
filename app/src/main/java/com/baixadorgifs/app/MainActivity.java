package com.baixadorgifs.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Baixador de GIFs: uma WebView que carrega a interface em assets/index.html
 * a partir de https://app.local/ e uma ponte nativa ("Android") para:
 *  - buscar os dados do post (sem bloqueio de CORS),
 *  - repassar o vídeo do Twitter na mesma origem (para converter em .gif),
 *  - salvar MP4 e GIF na galeria e compartilhar.
 */
public class MainActivity extends Activity {

    static final String HOST = "app.local";
    static final String BASE = "https://" + HOST + "/";
    static final String API = "https://api.fxtwitter.com/2/status/";
    static final String API_V1 = "https://api.fxtwitter.com/i/status/";
    static final String FOLDER = "GIFs Twitter";
    static final String UA = "BaixadorGifs/1.0 (Android)";

    private WebView web;
    private boolean pageReady = false;
    private String pendingShared = null;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#15171b"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " BaixadorGifs/1.0");

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());

        handleIntent(getIntent());
        web.loadUrl(BASE + "index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Texto vindo do menu "Compartilhar" de outro app (ex.: link do post no X). */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return;
            if (pageReady) js("window.receiveShared && window.receiveShared(" + JSONObject.quote(text) + ")");
            else pendingShared = text;
        }
    }

    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void done(String cb, boolean ok, String value) {
        js("window.nativeDone && window.nativeDone(" + JSONObject.quote(cb) + "," + ok + "," + JSONObject.quote(value == null ? "" : value) + ")");
    }

    // ------------------------------------------------------------------ rede

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    private static boolean isTwitterMedia(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String h = u.getHost();
        return "https".equals(u.getScheme()) && h != null && (h.equals("twimg.com") || h.endsWith(".twimg.com"));
    }

    private static WebResourceResponse text(int code, String reason, String mime, String body) {
        if (code < 200 || code > 599 || (code >= 300 && code < 400)) code = 502;
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", code, reason, h,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private WebResourceResponse apiPost(String id) {
        if (id == null || !id.matches("\\d{2,20}")) return text(400, "Bad Request", "application/json", "{\"code\":400}");
        String[] urls = {API + id, API_V1 + id};
        int last = 502;
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = open(u);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = in == null ? "" : readAll(in);
                if (code == 200) return text(200, "OK", "application/json", body);
                last = code;
                if (code == 404 || code == 401) return text(code, "Error", "application/json", body.isEmpty() ? "{\"code\":" + code + "}" : body);
            } catch (IOException e) {
                last = 502;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return text(last, "Error", "application/json", "{\"code\":" + last + "}");
    }

    private WebResourceResponse mediaProxy(String url) {
        if (!isTwitterMedia(url)) return text(403, "Forbidden", "text/plain", "forbidden");
        try {
            HttpURLConnection c = open(url);
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return text(code, "Error", "text/plain", "error " + code);
            }
            String type = c.getContentType();
            if (type == null || type.isEmpty()) type = "video/mp4";
            if (type.contains(";")) type = type.substring(0, type.indexOf(';')).trim();
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            long len = c.getContentLengthLong();
            if (len > 0) h.put("Content-Length", String.valueOf(len));
            return new WebResourceResponse(type, null, 200, "OK", h, c.getInputStream());
        } catch (IOException e) {
            return text(502, "Bad Gateway", "text/plain", "error");
        }
    }

    private WebResourceResponse asset(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        String name = path.substring(1);
        if (name.contains("..")) return text(404, "Not Found", "text/plain", "not found");
        String mime = "application/octet-stream";
        if (name.endsWith(".html")) mime = "text/html";
        else if (name.endsWith(".js")) mime = "application/javascript";
        else if (name.endsWith(".css")) mime = "text/css";
        else if (name.endsWith(".woff2")) mime = "font/woff2";
        else if (name.endsWith(".png")) mime = "image/png";
        else if (name.endsWith(".svg")) mime = "image/svg+xml";
        try {
            InputStream in = getAssets().open(name);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (IOException e) {
            return text(404, "Not Found", "text/plain", "not found");
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            byte[] buf = new byte[16384];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            if (path.startsWith("/api/post/")) return apiPost(u.getLastPathSegment());
            if (path.equals("/media")) return mediaProxy(u.getQueryParameter("u"));
            return asset(path);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            if (pendingShared != null) {
                String t = pendingShared;
                pendingShared = null;
                js("window.receiveShared && window.receiveShared(" + JSONObject.quote(t) + ")");
            }
        }
    }

    // ------------------------------------------------------------- salvar

    private static String safeName(String name, String ext) {
        if (name == null || name.trim().isEmpty()) name = "twitter";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase().endsWith("." + ext)) name = name + "." + ext;
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    private boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Grava na galeria (Filmes ou Imagens / GIFs Twitter) e devolve o content:// do arquivo. */
    private Uri saveToGallery(InputStream in, String name, String mime, boolean video) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri col = video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = cr.insert(col, v);
            if (item == null) throw new IOException("insert");
            try (OutputStream out = cr.openOutputStream(item)) {
                if (out == null) throw new IOException("open");
                copy(in, out);
            } catch (IOException e) {
                cr.delete(item, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return item;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), FOLDER);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs");
        File f = new File(dir, name);
        int i = 1;
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        while (f.exists()) f = new File(dir, base + "-" + (i++) + ext);
        try (OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        final Uri[] result = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime},
                (path, uri) -> { result[0] = uri; latch.countDown(); });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return result[0];
    }

    private class Bridge {

        @JavascriptInterface
        public String takeShared() {
            String t = pendingShared;
            pendingShared = null;
            return t == null ? "" : t;
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void copyText(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", text));
            });
        }

        @JavascriptInterface
        public void saveUrl(final String url, final String name, final String cb) {
            if (!isTwitterMedia(url)) { done(cb, false, "Link de vídeo inválido."); return; }
            if (needsLegacyPermission()) { askPermission(cb); return; }
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = open(url);
                    if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
                    try (InputStream in = c.getInputStream()) {
                        Uri u = saveToGallery(in, safeName(name, "mp4"), "video/mp4", true);
                        done(cb, true, u == null ? "" : u.toString());
                    }
                } catch (Exception e) {
                    done(cb, false, "Não consegui baixar o vídeo. Verifique a internet e tente de novo.");
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void saveBase64(final String b64, final String name, final String cb) {
            if (needsLegacyPermission()) { askPermission(cb); return; }
            pool.execute(() -> {
                try {
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    Uri u = saveToGallery(new ByteArrayInputStream(data), safeName(name, "gif"), "image/gif", false);
                    done(cb, true, u == null ? "" : u.toString());
                } catch (Exception e) {
                    done(cb, false, "Não consegui salvar o GIF.");
                }
            });
        }

        @JavascriptInterface
        public void share(final String uri, final String mime) {
            ui.post(() -> {
                try {
                    Uri u = Uri.parse(uri);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, u);
                    send.setClipData(ClipData.newRawUri("", u));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Compartilhar"));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            ui.post(() -> {
                try {
                    Uri u = Uri.parse(url);
                    if ("https".equals(u.getScheme())) startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
            });
        }
    }

    private void askPermission(String cb) {
        ui.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        });
        done(cb, false, "Permita o acesso aos arquivos e toque de novo.");
    }
}
