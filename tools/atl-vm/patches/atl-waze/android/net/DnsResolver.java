/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.os.CancellationSignal;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dns resolver class for asynchronous dns querying (AOSP API 29).
 *
 * Android answers these through netd's resolver. Here the host libc resolver answers {@link #query}
 * (getaddrinfo via {@link InetAddress#getAllByName}) and {@link #rawQuery} is served by sending the
 * DNS message itself over UDP to the nameservers in /etc/resolv.conf (the host's configuration,
 * which is what netd would consult too), with the standard UDP/TCP-less retry over each server.
 * Results are delivered on the caller's executor, as the framework does.
 */
public final class DnsResolver {
	private static final String TAG = "DnsResolver";
	private static final DnsResolver sInstance = new DnsResolver();

	public static final int CLASS_IN = 1;
	public static final int TYPE_A = 1;
	public static final int TYPE_AAAA = 28;

	public static final int FLAG_EMPTY = 0;
	public static final int FLAG_NO_RETRY = 1 << 0;
	public static final int FLAG_NO_CACHE_STORE = 1 << 1;
	public static final int FLAG_NO_CACHE_LOOKUP = 1 << 2;

	public static final int ERROR_PARSE = 0;
	public static final int ERROR_SYSTEM = 1;

	private static final int DNS_RAW_RESPONSE = 4;
	private static final int QUERY_TIMEOUT_MS = 5000;

	/** Get instance for DnsResolver */
	public static DnsResolver getInstance() {
		return sInstance;
	}

	private DnsResolver() {
	}

	/** Base interface for answer callbacks. */
	public interface Callback<T> {
		/** Success response to {@link #query} or {@link #rawQuery}; rcode is the DNS response code. */
		void onAnswer(T answer, int rcode);

		/** Error response to {@link #query} or {@link #rawQuery}. */
		void onError(DnsException error);
	}

	/** Class to represent DNS error */
	public static class DnsException extends Exception {
		/** DNS error code as one of the ERROR_* constants. */
		public final int code;

		DnsException(int code, Throwable cause) {
			super(cause);
			this.code = code;
		}
	}

	/** Send a raw DNS query (a complete DNS message) and deliver the raw answer. */
	public void rawQuery(Network network, final byte[] query, int flags, Executor executor,
	                     final CancellationSignal cancellationSignal, final Callback<? super byte[]> callback) {
		if (cancellationSignal != null && cancellationSignal.isCanceled())
			return;
		final boolean retry = (flags & FLAG_NO_RETRY) == 0;
		executor.execute(new Runnable() {
			@Override
			public void run() {
				if (cancellationSignal != null && cancellationSignal.isCanceled())
					return;
				try {
					byte[] answer = exchange(query, retry);
					if (cancellationSignal != null && cancellationSignal.isCanceled())
						return;
					callback.onAnswer(answer, rcode(answer));
				} catch (IOException e) {
					if (cancellationSignal != null && cancellationSignal.isCanceled())
						return;
					callback.onError(new DnsException(ERROR_SYSTEM, e));
				}
			}
		});
	}

	/** Send a raw DNS query for the given name, class and type; deliver the raw answer. */
	public void rawQuery(Network network, String domain, int nsClass, int nsType, int flags, Executor executor,
	                     CancellationSignal cancellationSignal, final Callback<? super byte[]> callback) {
		byte[] query;
		try {
			query = buildQuery(domain, nsClass, nsType);
		} catch (final IllegalArgumentException e) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					callback.onError(new DnsException(ERROR_PARSE, e));
				}
			});
			return;
		}
		rawQuery(network, query, flags, executor, cancellationSignal, callback);
	}

	/** Resolve a name to its addresses (A and AAAA) and deliver the list. */
	public void query(Network network, String domain, int flags, Executor executor,
	                  CancellationSignal cancellationSignal, Callback<? super List<InetAddress>> callback) {
		resolve(domain, 0, executor, cancellationSignal, callback);
	}

	/** Resolve a name for one record type (TYPE_A or TYPE_AAAA) and deliver the list. */
	public void query(Network network, String domain, int nsType, int flags, Executor executor,
	                  CancellationSignal cancellationSignal, Callback<? super List<InetAddress>> callback) {
		if (nsType != TYPE_A && nsType != TYPE_AAAA)
			throw new IllegalArgumentException("Invalid query type: " + nsType);
		resolve(domain, nsType, executor, cancellationSignal, callback);
	}

	private void resolve(final String domain, final int nsType, Executor executor, final CancellationSignal cancellationSignal,
	                     final Callback<? super List<InetAddress>> callback) {
		if (cancellationSignal != null && cancellationSignal.isCanceled())
			return;
		executor.execute(new Runnable() {
			@Override
			public void run() {
				if (cancellationSignal != null && cancellationSignal.isCanceled())
					return;
				try {
					InetAddress[] all = InetAddress.getAllByName(domain);
					List<InetAddress> result = new ArrayList<>();
					for (InetAddress a : all) {
						if (nsType == 0 || (nsType == TYPE_A && a instanceof Inet4Address) || (nsType == TYPE_AAAA && a instanceof Inet6Address))
							result.add(a);
					}
					if (cancellationSignal != null && cancellationSignal.isCanceled())
						return;
					callback.onAnswer(result, 0);
				} catch (UnknownHostException e) {
					if (cancellationSignal != null && cancellationSignal.isCanceled())
						return;
					callback.onError(new DnsException(ERROR_SYSTEM, e));
				}
			}
		});
	}

	// ---- wire format ----------------------------------------------------------------------------

	/** A standard recursive query message for one question (RFC 1035 §4.1). */
	static byte[] buildQuery(String domain, int nsClass, int nsType) {
		if (domain == null || domain.isEmpty() || domain.length() > 253)
			throw new IllegalArgumentException("invalid domain name");
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int id = ThreadLocalRandom.current().nextInt(0x10000);
		out.write(id >> 8); out.write(id);
		out.write(0x01); out.write(0x00);     // flags: standard query, recursion desired
		out.write(0); out.write(1);           // QDCOUNT
		out.write(0); out.write(0);           // ANCOUNT
		out.write(0); out.write(0);           // NSCOUNT
		out.write(0); out.write(0);           // ARCOUNT
		for (String label : domain.split("\\.")) {
			if (label.isEmpty())
				continue;
			byte[] b = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			if (b.length > 63)
				throw new IllegalArgumentException("label too long: " + label);
			out.write(b.length);
			out.write(b, 0, b.length);
		}
		out.write(0);
		out.write(nsType >> 8); out.write(nsType);
		out.write(nsClass >> 8); out.write(nsClass);
		return out.toByteArray();
	}

	static int rcode(byte[] message) {
		return message.length >= 4 ? message[3] & 0x0f : 0;
	}

	/** The nameservers of /etc/resolv.conf, in order; the loopback resolver when there are none. */
	static List<InetSocketAddress> nameservers() {
		List<InetSocketAddress> servers = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new FileReader("/etc/resolv.conf"))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (!line.startsWith("nameserver"))
					continue;
				String[] parts = line.split("\\s+");
				if (parts.length >= 2) {
					try {
						servers.add(new InetSocketAddress(InetAddress.getByName(parts[1]), 53));
					} catch (UnknownHostException ignored) {
					}
				}
			}
		} catch (IOException ignored) {
		}
		if (servers.isEmpty())
			servers.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), 53));
		return servers;
	}

	/** UDP exchange with each nameserver in turn (one retry per server when allowed). */
	static byte[] exchange(byte[] query, boolean retry) throws IOException {
		IOException last = null;
		for (InetSocketAddress server : nameservers()) {
			for (int attempt = 0; attempt < (retry ? 2 : 1); attempt++) {
				try (DatagramSocket socket = new DatagramSocket()) {
					socket.setSoTimeout(QUERY_TIMEOUT_MS);
					socket.send(new DatagramPacket(query, query.length, server));
					byte[] buf = new byte[4096];
					DatagramPacket reply = new DatagramPacket(buf, buf.length);
					while (true) {
						socket.receive(reply);
						if (reply.getLength() >= 2 && buf[0] == query[0] && buf[1] == query[1])
							return Arrays.copyOf(buf, reply.getLength());
					}
				} catch (SocketTimeoutException e) {
					last = e;
				}
			}
		}
		throw last != null ? last : new IOException("no nameserver answered");
	}
}
