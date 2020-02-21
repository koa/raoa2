package ch.bergturbenthal.raoa.elastic;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

class DummyX509TrustManager implements X509TrustManager {
  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {}

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {}

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return null;
  }
}
