import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Checks ART's TLS provider the way apps use it (run.sh starts the servers and runs this under
 * dalvikvm): Conscrypt registered as libcore's provider 1 and default socket factory, ALPN h2 and
 * TLS 1.3 session tickets set the way OkHttp 5's Android10SocketAdapter sets them
 * (android.net.ssl.SSLSockets, SSLParameters.setApplicationProtocols), resumption sequentially
 * and from parallel connections with every session complete (OkHttp crashed on a null cipher
 * suite and null peer certificates when one session object was shared), closing the plain socket
 * from another thread waking a blocked read (OkHttp's cancel), the JCA algorithms in Conscrypt,
 * X509TrustManagerExtensions (OkHttp's certificate chain cleaner; api-impl), and with --online the
 * default trust store against a public host.
 *
 *   TlsCheck CA.pem HOST PORT_HTTP1 PORT_H2 [--online HOST]
 *
 * The servers (tls-server.py) answer "New, ..." or "Reused, ..." per connection. Prints PASS/FAIL
 * per check and "RESULT passed/total"; exits 1 on a failure.
 */
public class TlsCheck {
	static int passed, total;
	static SSLContext ctx;
	static X509TrustManager testTrust;

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : ": " + detail));
	}

	public static void main(String[] a) throws Exception {
		String ca = a[0], host = a[1];
		int http1 = Integer.parseInt(a[2]), h2 = Integer.parseInt(a[3]);
		String online = a.length > 5 && a[4].equals("--online") ? a[5] : null;

		providers();

		testTrust = trustManager(ca);
		ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[] {testTrust}, null);

		alpnLikeOkHttp(host, h2);
		sequentialResumption(host, http1, 5);
		parallelResumption(host, http1, 8, 3);
		cancelWakesReader(host, http1);
		trustManagerExtensions(ca);
		if (online != null)
			defaultTrust(online);

		System.out.println("RESULT " + passed + "/" + total);
		System.exit(passed == total ? 0 : 1);
	}

	static void providers() throws Exception {
		Provider[] ps = Security.getProviders();
		StringBuilder names = new StringBuilder();
		for (Provider p : ps)
			names.append(p.getName()).append(' ');
		check("provider 1 is Conscrypt (AndroidOpenSSL)", ps.length > 0 && ps[0].getName().equals("AndroidOpenSSL"), names.toString().trim());
		SSLContext def = SSLContext.getDefault();
		check("default SSLContext from Conscrypt", def.getProvider().getName().equals("AndroidOpenSSL"), def.getProvider().getName());
		String f = SSLSocketFactory.getDefault().getClass().getName();
		check("default SSLSocketFactory is Conscrypt's", f.startsWith("com.android.org.conscrypt."), f);
		String[][] algs = {{"MessageDigest", "SHA-256"}, {"Cipher", "AES/GCM/NoPadding"}, {"Cipher", "ChaCha20/Poly1305/NoPadding"},
		                   {"Signature", "SHA256withECDSA"}, {"KeyAgreement", "ECDH"}, {"Mac", "HmacSHA256"}};
		for (String[] alg : algs) {
			Provider p;
			if (alg[0].equals("MessageDigest"))
				p = MessageDigest.getInstance(alg[1]).getProvider();
			else if (alg[0].equals("Cipher"))
				p = Cipher.getInstance(alg[1]).getProvider();
			else if (alg[0].equals("Signature"))
				p = Signature.getInstance(alg[1]).getProvider();
			else if (alg[0].equals("KeyAgreement"))
				p = KeyAgreement.getInstance(alg[1]).getProvider();
			else
				p = Mac.getInstance(alg[1]).getProvider();
			check(alg[0] + " " + alg[1] + " from Conscrypt", p.getName().equals("AndroidOpenSSL"), p.getName());
		}
	}

	static X509TrustManager trustManager(String caPem) throws Exception {
		KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
		ks.load(null, null);
		if (caPem != null) {
			try (InputStream in = new FileInputStream(caPem)) {
				int i = 0;
				for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in))
					ks.setCertificateEntry("ca" + i++, c);
			}
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ks);
		return (X509TrustManager)tmf.getTrustManagers()[0];
	}

	/** OkHttp 5's Android10SocketAdapter: SSLSockets, tickets on, ALPN through SSLParameters */
	static SSLSocket openLikeOkHttp(SSLSocketFactory f, String host, int port) throws Exception {
		Socket raw = new Socket();
		raw.connect(new InetSocketAddress(host, port), 10000);
		SSLSocket s = (SSLSocket)f.createSocket(raw, host, port, true);
		s.setSoTimeout(10000);
		Class<?> ss = Class.forName("android.net.ssl.SSLSockets");
		if (!(Boolean)ss.getMethod("isSupportedSocket", SSLSocket.class).invoke(null, s))
			throw new IllegalStateException("SSLSockets.isSupportedSocket false for " + s.getClass().getName());
		ss.getMethod("setUseSessionTickets", SSLSocket.class, boolean.class).invoke(null, s, true);
		SSLParameters p = s.getSSLParameters();
		p.getClass().getMethod("setApplicationProtocols", String[].class).invoke(p, (Object) new String[] {"h2", "http/1.1"});
		s.setSSLParameters(p);
		return s;
	}

	static String alpn(SSLSocket s) throws Exception {
		return (String)s.getClass().getMethod("getApplicationProtocol").invoke(s);
	}

	static String request(SSLSocket s, String host) throws IOException {
		OutputStream o = s.getOutputStream();
		o.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes("ISO-8859-1"));
		o.flush();
		InputStream in = s.getInputStream();
		StringBuilder r = new StringBuilder();
		byte[] b = new byte[4096];
		int n;
		while ((n = in.read(b)) > 0 && r.length() < 65536)
			r.append(new String(b, 0, n, "ISO-8859-1"));
		return r.toString();
	}

	static String incomplete(SSLSocket s, String host) {
		SSLSession ss = s.getSession();
		StringBuilder e = new StringBuilder();
		if (ss.getCipherSuite() == null || ss.getCipherSuite().startsWith("SSL_NULL"))
			e.append("cipherSuite=").append(ss.getCipherSuite()).append(' ');
		if (ss.getProtocol() == null || ss.getProtocol().equals("NONE"))
			e.append("protocol=").append(ss.getProtocol()).append(' ');
		try {
			Certificate[] pc = ss.getPeerCertificates();
			if (pc == null || pc.length == 0)
				e.append("no peer certificates ");
			else if (!host.matches("[0-9.]+") && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss))
				e.append("hostname not verified ");
		} catch (Exception ex) {
			e.append("peer certificates: ").append(ex).append(' ');
		}
		return e.toString();
	}

	static void alpnLikeOkHttp(String host, int port) {
		try {
			SSLSocket s = openLikeOkHttp(ctx.getSocketFactory(), host, port);
			s.startHandshake();
			String p = alpn(s);
			check("ALPN h2 the way OkHttp asks (SSLSockets + SSLParameters)", "h2".equals(p),
			      "alpn=" + p + " " + s.getSession().getProtocol() + " " + s.getSession().getCipherSuite() + " " + s.getClass().getName());
			s.close();
		} catch (Exception e) {
			check("ALPN h2 the way OkHttp asks (SSLSockets + SSLParameters)", false, e.toString());
		}
	}

	static void sequentialResumption(String host, int port, int n) {
		int reused = 0;
		String err = "";
		try {
			for (int i = 0; i < n; i++) {
				SSLSocket s = openLikeOkHttp(ctx.getSocketFactory(), host, port);
				s.startHandshake();
				err += incomplete(s, host);
				if (request(s, host).contains("Reused, "))
					reused++;
				s.close();
			}
		} catch (Exception e) {
			err += e;
		}
		check("TLS 1.3 resumption, sequential", reused == n - 1 && err.isEmpty(), reused + "/" + (n - 1) + " resumed " + err);
	}

	static void parallelResumption(final String host, final int port, int threads, int rounds) throws Exception {
		final SSLSocketFactory f = ctx.getSocketFactory();
		SSLSocket first = openLikeOkHttp(f, host, port);
		first.startHandshake();
		request(first, host);
		first.close();
		final AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger(), reused = new AtomicInteger();
		final List<String> errs = Collections.synchronizedList(new ArrayList<String>());
		for (int r = 0; r < rounds; r++) {
			final CyclicBarrier go = new CyclicBarrier(threads);
			final List<SSLSocket> open = Collections.synchronizedList(new ArrayList<SSLSocket>());
			List<Thread> ts = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				Thread th = new Thread(new Runnable() {
					public void run() {
						try {
							go.await();
							SSLSocket s = openLikeOkHttp(f, host, port);
							s.startHandshake();
							open.add(s); // stays open while the others resume the same session
							String e = incomplete(s, host);
							String resp = request(s, host);
							e += incomplete(s, host); // again, after the other connections handshook
							if (resp.contains("Reused, "))
								reused.incrementAndGet();
							if (e.isEmpty())
								ok.incrementAndGet();
							else {
								bad.incrementAndGet();
								errs.add(e);
							}
						} catch (Exception ex) {
							bad.incrementAndGet();
							errs.add(ex.toString());
						}
					}
				});
				th.start();
				ts.add(th);
			}
			for (Thread th : ts)
				th.join();
			for (SSLSocket s : open)
				s.close();
		}
		int n = threads * rounds;
		check("parallel connections: every session complete", bad.get() == 0 && ok.get() == n, ok.get() + "/" + n + (errs.isEmpty() ? "" : " " + errs.get(0)));
		check("parallel connections: resumed", reused.get() * 2 >= n, reused.get() + "/" + n + " resumed");
	}

	static void cancelWakesReader(String host, int port) {
		try {
			final Socket raw = new Socket();
			raw.connect(new InetSocketAddress(host, port), 10000);
			SSLSocket s = (SSLSocket)ctx.getSocketFactory().createSocket(raw, host, port, true);
			s.setSoTimeout(10000);
			s.startHandshake();
			new Thread(new Runnable() {
				public void run() {
					try {
						Thread.sleep(300);
						raw.close();
					} catch (Exception e) {
					}
				}
			}).start();
			long t0 = System.nanoTime();
			String how;
			try {
				how = "read returned " + s.getInputStream().read(new byte[64]);
			} catch (IOException e) {
				how = e.toString();
			}
			long ms = (System.nanoTime() - t0) / 1000000;
			check("closing the plain socket wakes a blocked read (OkHttp's cancel)", ms < 2000,
			      String.format(Locale.ROOT, "%d ms, %s, %s", ms, how, s.getClass().getName()));
		} catch (Exception e) {
			check("closing the plain socket wakes a blocked read (OkHttp's cancel)", false, e.toString());
		}
	}

	static void trustManagerExtensions(String caPem) throws Exception {
		Class<?> c;
		try {
			c = Class.forName("android.net.http.X509TrustManagerExtensions");
		} catch (ClassNotFoundException e) {
			System.out.println("SKIP X509TrustManagerExtensions: api-impl.jar not on the class path");
			return;
		}
		List<X509Certificate> chain = new ArrayList<>();
		try (InputStream in = new FileInputStream(caPem.replace("ca.pem", "chain.pem"))) {
			Collection<? extends Certificate> cs = CertificateFactory.getInstance("X.509").generateCertificates(in);
			for (Certificate x : cs)
				chain.add((X509Certificate)x);
		}
		Method m = c.getMethod("checkServerTrusted", X509Certificate[].class, String.class, String.class);
		X509Certificate[] leafOnly = {chain.get(0)};
		try {
			Object ext = c.getConstructor(X509TrustManager.class).newInstance(testTrust);
			@SuppressWarnings("unchecked")
			List<X509Certificate> got = (List<X509Certificate>)m.invoke(ext, leafOnly, "ECDHE_ECDSA", "localhost");
			check("X509TrustManagerExtensions.checkServerTrusted: the verified chain to the anchor",
			      got.size() == 2 && got.get(1).equals(chain.get(1)), got.size() + " certificates");
		} catch (InvocationTargetException e) {
			check("X509TrustManagerExtensions.checkServerTrusted: the verified chain to the anchor", false, e.getCause().toString());
		}
		try {
			Object ext = c.getConstructor(X509TrustManager.class).newInstance(trustManager(null));
			m.invoke(ext, leafOnly, "ECDHE_ECDSA", "localhost");
			check("X509TrustManagerExtensions.checkServerTrusted: an untrusted chain is refused", false, "accepted");
		} catch (InvocationTargetException e) {
			check("X509TrustManagerExtensions.checkServerTrusted: an untrusted chain is refused",
			      e.getCause() instanceof CertificateException, e.getCause().toString());
		}
	}

	static void defaultTrust(String host) {
		try {
			SSLSocket s = openLikeOkHttp((SSLSocketFactory)SSLSocketFactory.getDefault(), host, 443);
			s.startHandshake();
			String e = incomplete(s, host);
			String p = alpn(s);
			check("default trust store (AndroidCAStore) and hostname: " + host, e.isEmpty(),
			      s.getSession().getProtocol() + " " + s.getSession().getCipherSuite() + " alpn=" + p + " " + e);
			check("ALPN h2 with " + host, "h2".equals(p), "alpn=" + p);
			s.close();
		} catch (Exception e) {
			check("default trust store (AndroidCAStore) and hostname: " + host, false, e.toString());
		}
	}
}
