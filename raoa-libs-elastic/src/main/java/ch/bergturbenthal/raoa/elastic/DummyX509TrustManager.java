package ch.bergturbenthal.raoa.elastic;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

class DummyX509TrustManager extends X509ExtendedTrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return null;
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
    }
}
