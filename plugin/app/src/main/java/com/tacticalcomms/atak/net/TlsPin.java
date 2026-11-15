package com.tacticalcomms.atak.net;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate pinning for servers addressed by IP. Such a server has no domain and
 * therefore a self-signed certificate, so instead of a certificate authority we trust
 * one exact certificate, identified by its SHA-256 fingerprint:
 * <ul>
 *   <li>fingerprint configured: only that certificate is accepted;</li>
 *   <li>none configured: the first certificate seen is remembered ("trust on first use")
 *       and every later connection must present the same one.</li>
 * </ul>
 * Servers addressed by a host name do not use this class; they get normal CA validation.
 */
public class TlsPin {

    public interface Store {
        /** The pinned fingerprint, or null / empty if none yet. */
        String get();
        /** Called (on a network thread) when a certificate is trusted for the first time. */
        void learned(String fingerprint);
    }

    private final Store store;
    private final SSLSocketFactory factory;
    private volatile boolean mismatch;

    public TlsPin(Store store) {
        this.store = store;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PinTrustManager()}, new SecureRandom());
            factory = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public SSLSocketFactory socketFactory() { return factory; }

    /** The pinned certificate is the server's identity, so its name is not checked. */
    public HostnameVerifier hostnameVerifier() {
        return new HostnameVerifier() {
            @Override public boolean verify(String hostname, SSLSession session) { return true; }
        };
    }

    /** True if the last handshake was refused because the server presented a different certificate. */
    public boolean mismatch() { return mismatch; }

    /** IPv4 / IPv6 literal (as opposed to a DNS name)? */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') >= 0) return true; // IPv6
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** Lower-case hex without separators, so "AA:BB", "aa bb" and "aabb" compare equal. */
    public static String normalize(String fp) {
        return fp == null ? "" : fp.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.US);
    }

    /** Fingerprint in the AA:BB:CC form that the server prints at start-up. */
    public static String format(String fp) {
        String n = normalize(fp).toUpperCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < n.length(); i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(n, i, i + 2);
        }
        return sb.toString();
    }

    private final class PinTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no server certificate");
            final String seen;
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
                seen = sb.toString();
            } catch (Exception e) {
                throw new CertificateException(e);
            }
            String expected = normalize(store.get());
            if (expected.isEmpty()) {
                mismatch = false;
                store.learned(seen);
            } else if (expected.equals(seen)) {
                mismatch = false;
            } else {
                mismatch = true;
                throw new CertificateException("server certificate does not match the pinned fingerprint");
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
