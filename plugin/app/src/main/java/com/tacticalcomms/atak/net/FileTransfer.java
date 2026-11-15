package com.tacticalcomms.atak.net;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Upload / download of attachments through the server's HTTP file API. */
public class FileTransfer {

    public interface Callback {
        void onProgress(long done, long total);
        void onDone(JSONObject result, File file);
        void onError(String error);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public void upload(final String base, final String token, final TlsPin tls, final File file, final Callback cb) {
        pool.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    String name = URLEncoder.encode(file.getName(), "UTF-8");
                    c = open(new URL(base + "/files?name=" + name), tls);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setFixedLengthStreamingMode(file.length());
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    String mime = URLConnection.guessContentTypeFromName(file.getName());
                    c.setRequestProperty("Content-Type", mime != null ? mime : "application/octet-stream");
                    try (InputStream in = new FileInputStream(file); OutputStream out = c.getOutputStream()) {
                        copy(in, out, file.length(), cb);
                    }
                    int code = c.getResponseCode();
                    if (code != 200) {
                        cb.onError(code == 413 ? "file too large" : "upload failed (HTTP " + code + ")");
                        return;
                    }
                    cb.onDone(new JSONObject(readAll(c.getInputStream())), file);
                } catch (Exception e) {
                    cb.onError("upload failed: " + e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    public void download(final String base, final String token, final TlsPin tls, final String fileId,
                         final File dest, final Callback cb) {
        pool.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                File tmp = new File(dest.getPath() + ".part");
                try {
                    c = open(new URL(base + "/files/" + fileId), tls);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setRequestProperty("Authorization", "Bearer " + token);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        cb.onError(code == 404 ? "file expired on server" : "download failed (HTTP " + code + ")");
                        return;
                    }
                    File dir = dest.getParentFile();
                    if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        copy(in, out, c.getContentLength(), cb);
                    }
                    if (!tmp.renameTo(dest)) throw new IOException("cannot write " + dest);
                    cb.onDone(null, dest);
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    cb.onError("download failed: " + e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    public void shutdown() { pool.shutdownNow(); }

    /** Applies the same certificate pin as the WebSocket when the server is addressed by IP. */
    private static HttpURLConnection open(URL url, TlsPin tls) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (tls != null && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(tls.socketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier(tls.hostnameVerifier());
        }
        return c;
    }

    private static void copy(InputStream in, OutputStream out, long total, Callback cb) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long done = 0, lastReport = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (done - lastReport > 256 * 1024) {
                lastReport = done;
                cb.onProgress(done, total);
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
