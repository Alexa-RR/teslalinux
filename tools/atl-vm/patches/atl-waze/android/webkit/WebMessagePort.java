/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.webkit;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;

/**
 * The Java representation of the
 * <a href="https://html.spec.whatwg.org/multipage/comms.html#messageport">
 * HTML5 message ports.</a>
 *
 * <p>A Message port represents one endpoint of a Message Channel. In Android
 * webview, there is no separate Message Channel object. When a message channel
 * is created, both ports are tangled to each other and started, and then
 * returned in a MessagePort array, see {@link WebView#createWebMessageChannel}
 * for creating a message channel.
 *
 * <p>When a message port is first created or received via transfer, it does not
 * have a WebMessageCallback to receive web messages. The messages are queued until
 * a WebMessageCallback is set.
 */
public abstract class WebMessagePort {
	/**
	 * The listener for handling MessagePort events.
	 */
	public static abstract class WebMessageCallback {
		/**
		 * Message callback for receiving onMessage events.
		 *
		 * @param port  the WebMessagePort that the message is destined for
		 * @param message  the message from the entangled port.
		 */
		public void onMessage(WebMessagePort port, WebMessage message) {}
	}

	public WebMessagePort() {}

	/**
	 * Post a WebMessage to the entangled port.
	 *
	 * @throws IllegalStateException If message port is already transferred or closed.
	 */
	public abstract void postMessage(WebMessage message);

	/**
	 * Close the message port and free any resources associated with it.
	 */
	public abstract void close();

	/**
	 * Sets a callback to receive message events on the main thread.
	 */
	public abstract void setWebMessageCallback(WebMessageCallback callback);

	/**
	 * Sets a callback to receive message events on the handler that is provided
	 * by the application. If the handler is null the message events are received on the main
	 * thread.
	 */
	public abstract void setWebMessageCallback(WebMessageCallback callback, Handler handler);

	/** teslalinux: the other end of a port that lives in a page (WebView bridges it). */
	interface PageEndpoint {
		void postToPage(int pageId, String data, WebMessagePort[] ports);
		void closePagePort(int pageId);
	}

	/**
	 * teslalinux: one end of a channel. Its peer is either another Java port (both ends in the app)
	 * or a MessagePort in a page, reached through the WebView that holds it.
	 */
	static final class Impl extends WebMessagePort {
		private Impl mPeer;
		private PageEndpoint mPage;
		private int mPageId;
		private boolean mClosed, mTransferred;
		private WebMessageCallback mCallback;
		private Handler mHandler;
		private final ArrayList<WebMessage> mPending = new ArrayList<WebMessage>();

		static Impl[] createPair() {
			Impl a = new Impl(), b = new Impl();
			a.mPeer = b;
			b.mPeer = a;
			return new Impl[] {a, b};
		}

		static Impl forPage(PageEndpoint page, int pageId) {
			Impl p = new Impl();
			p.mPage = page;
			p.mPageId = pageId;
			return p;
		}

		@Override
		public void postMessage(WebMessage message) {
			Impl peer;
			PageEndpoint page;
			int pageId;
			synchronized (this) {
				if (mClosed)
					throw new IllegalStateException("Port is already closed");
				if (mTransferred)
					throw new IllegalStateException("Port is already transferred");
				WebMessagePort[] ports = message.getPorts();
				if (ports != null) {
					for (WebMessagePort p : ports) {
						if (p == this)
							throw new IllegalStateException("Source port cannot be transferred");
						if (p == mPeer)
							throw new IllegalStateException("Target port cannot be transferred");
						if (p instanceof Impl && (((Impl)p).mClosed || ((Impl)p).mTransferred))
							throw new IllegalStateException("Port is already closed or transferred");
					}
				}
				peer = mPeer;
				page = mPage;
				pageId = mPageId;
			}
			if (page != null)
				page.postToPage(pageId, message.getData(), message.getPorts());
			else if (peer != null)
				peer.deliver(message);
		}

		/** a message arriving at this port from its peer (or the page) */
		void deliver(final WebMessage message) {
			final WebMessageCallback callback;
			Handler handler;
			synchronized (this) {
				if (mClosed)
					return;
				if (mCallback == null) {
					mPending.add(message);
					return;
				}
				callback = mCallback;
				handler = mHandler != null ? mHandler : new Handler(Looper.getMainLooper());
			}
			handler.post(new Runnable() {
				public void run() {
					callback.onMessage(Impl.this, message);
				}
			});
		}

		@Override
		public void close() {
			Impl peer;
			PageEndpoint page;
			int pageId;
			synchronized (this) {
				if (mTransferred)
					throw new IllegalStateException("Port is already transferred");
				if (mClosed)
					return;
				mClosed = true;
				mPending.clear();
				peer = mPeer;
				page = mPage;
				pageId = mPageId;
				mPeer = null;
				mPage = null;
			}
			if (page != null)
				page.closePagePort(pageId);
			else if (peer != null) {
				synchronized (peer) {
					peer.mPeer = null;
				}
			}
		}

		@Override
		public void setWebMessageCallback(WebMessageCallback callback) {
			setWebMessageCallback(callback, null);
		}

		@Override
		public void setWebMessageCallback(WebMessageCallback callback, Handler handler) {
			ArrayList<WebMessage> pending;
			synchronized (this) {
				if (mTransferred)
					throw new IllegalStateException("Port is already transferred");
				if (mClosed)
					throw new IllegalStateException("Port is already closed");
				mCallback = callback;
				mHandler = handler;
				pending = new ArrayList<WebMessage>(mPending);
				mPending.clear();
			}
			if (callback != null) {
				for (WebMessage m : pending)
					deliver(m);
			}
		}

		/**
		 * This port is being sent to a page: the page gets a new MessagePort (pageId) whose messages
		 * now reach this port's peer, and this object can no longer be used. Returns false if the
		 * port cannot be transferred (closed, already transferred, or its peer lives in a page).
		 */
		Impl transferToPage(PageEndpoint page, int pageId) {
			Impl peer;
			synchronized (this) {
				if (mClosed || mTransferred || mPeer == null)
					return null;
				mTransferred = true;
				peer = mPeer;
				mPeer = null;
			}
			synchronized (peer) {
				peer.mPeer = null;
				peer.mPage = page;
				peer.mPageId = pageId;
			}
			return peer;
		}

		synchronized boolean bridgesPagePort(PageEndpoint page, int pageId) {
			return mPage == page && mPageId == pageId;
		}

		/** the page closed or lost the far end */
		void pageClosed() {
			synchronized (this) {
				mPage = null;
			}
		}
	}
}
