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

package android.net.ssl;

import java.lang.reflect.Method;

import javax.net.ssl.SSLSocket;

/**
 * Static utility methods for accessing additional functionality of supported instances of
 * {@link SSLSocket}. Sockets from the platform TLS provider will be compatible with all methods.
 *
 * teslalinux: on Android the platform provider is Conscrypt and this class forwards to
 * {@code Conscrypt.isConscrypt / setUseSessionTickets}. ATL's ART runs the wolfSSL JNI JSSE
 * provider instead, whose SSLSocket implements the same Conscrypt-style extension methods
 * ({@code setUseSessionTickets(boolean)}, {@code setAlpnProtocols(byte[])},
 * {@code getAlpnSelectedProtocol()}), which is what OkHttp and gRPC probe for after this class says
 * the socket is supported. The provider classes are not on the framework build path, so they are
 * reached reflectively.
 */
public class SSLSockets {
	private static final String PLATFORM_SOCKET_CLASS = "com.wolfssl.provider.jsse.WolfSSLSocket";

	private SSLSockets() {}

	/**
	 * Returns whether the given socket can be used with the methods in this class.
	 *
	 * @param socket the socket to check
	 * @return {@code true} if the socket is a supported platform socket
	 */
	public static boolean isSupportedSocket(SSLSocket socket) {
		if (socket == null)
			return false;
		for (Class<?> c = socket.getClass(); c != null; c = c.getSuperclass()) {
			if (PLATFORM_SOCKET_CLASS.equals(c.getName()))
				return true;
		}
		return false;
	}

	/**
	 * Enables or disables the use of session tickets.
	 *
	 * <p>This function must only be called on sockets which have been returned by
	 * {@link #isSupportedSocket(SSLSocket)} as supported.
	 *
	 * @throws IllegalArgumentException if the socket is not a supported socket
	 */
	public static void setUseSessionTickets(SSLSocket socket, boolean useSessionTickets) {
		if (!isSupportedSocket(socket)) {
			throw new IllegalArgumentException("Socket is not a supported socket.");
		}
		try {
			Method m = socket.getClass().getMethod("setUseSessionTickets", boolean.class);
			m.invoke(socket, useSessionTickets);
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Socket is not a supported socket.", e);
		}
	}
}
