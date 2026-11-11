/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.view.View;

public class WebChromeClient {

	/**
	 * Tell the host application the current progress of loading a page.
	 * @param view The WebView that initiated the callback.
	 * @param newProgress Current page loading progress, represented by
	 *                    an integer between 0 and 100.
	 */
	public void onProgressChanged(WebView view, int newProgress) {}

	/**
	 * Notify the host application of a change in the document title.
	 */
	public void onReceivedTitle(WebView view, String title) {}

	/**
	 * Notify the host application of a new favicon for the current page.
	 */
	public void onReceivedIcon(WebView view, Bitmap icon) {}

	/**
	 * Notify the host application of the url for an apple-touch-icon.
	 */
	public void onReceivedTouchIconUrl(WebView view, String url,
	                                   boolean precomposed) {}

	/**
	 * A callback interface used by the host application to notify
	 * the current page that its custom view has been dismissed.
	 */
	public interface CustomViewCallback {
		/**
		 * Invoked when the host application dismisses the
		 * custom view.
		 */
		public void onCustomViewHidden();
	}

	/**
	 * Notify the host application that the current page has entered full screen mode. After this
	 * call, web content will no longer be rendered in the WebView, but will instead be rendered
	 * in {@code view}. The host application should add this View to a Window which is configured
	 * with {@link android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN} flag in order to
	 * actually display this web content full screen.
	 */
	public void onShowCustomView(View view, CustomViewCallback callback) {};

	/**
	 * Notify the host application that the current page would
	 * like to show a custom View in a particular orientation.
	 * @deprecated This method supports the obsolete plugin mechanism,
	 * and will not be invoked in future
	 */
	@Deprecated
	public void onShowCustomView(View view, int requestedOrientation,
	                             CustomViewCallback callback) {};

	/**
	 * Notify the host application that the current page has exited full screen mode. The host
	 * application must hide the custom View (the View which was previously passed to {@link
	 * #onShowCustomView(View, CustomViewCallback) onShowCustomView()}). After this call, web
	 * content will render in the original WebView again.
	 */
	public void onHideCustomView() {}

	/**
	 * Request the host application to create a new window. If the host
	 * application chooses to honor this request, it should return {@code true} from
	 * this method, create a new WebView to host the window, insert it into the
	 * View system and send the supplied resultMsg message to its target with
	 * the new WebView as an argument. If the host application chooses not to
	 * honor the request, it should return {@code false} from this method. The default
	 * implementation of this method does nothing and hence returns {@code false}.
	 *
	 * @param view The WebView from which the request for a new window
	 *             originated.
	 * @param isDialog {@code true} if the new window should be a dialog, rather than
	 *                 a full-size window.
	 * @param isUserGesture {@code true} if the request was initiated by a user gesture,
	 *                      such as the user clicking a link.
	 * @param resultMsg The message to send when once a new WebView has been
	 *                  created. resultMsg.obj is a
	 *                  {@link WebView.WebViewTransport} object. This should be
	 *                  used to transport the new WebView, by calling
	 *                  {@link WebView.WebViewTransport#setWebView(WebView)
	 *                  WebView.WebViewTransport.setWebView(WebView)}.
	 * @return This method should return {@code true} if the host application will
	 *         create a new window, in which case resultMsg should be sent to
	 *         its target. Otherwise, this method should return {@code false}.
	 */
	public boolean onCreateWindow(WebView view, boolean isDialog,
	                              boolean isUserGesture, Message resultMsg) {
		return false;
	}

	/**
	 * Request display and focus for this WebView. This may happen due to
	 * another WebView opening a link in this WebView and requesting that this
	 * WebView be displayed.
	 */
	public void onRequestFocus(WebView view) {}

	/**
	 * Notify the host application to close the given WebView and remove it
	 * from the view system if necessary. At this point, WebCore has stopped
	 * any loading in this window and has removed any cross-scripting ability
	 * in javascript.
	 */
	public void onCloseWindow(WebView window) {}

	/**
	 * Notify the host application that the web page wants to display a
	 * JavaScript {@code alert()} dialog.
	 * <p>The default behavior if this method returns {@code false} or is not
	 * overridden is to show a dialog containing the alert message and suspend
	 * JavaScript execution until the dialog is dismissed.
	 * <p>To show a custom dialog, the app should return {@code true} from this
	 * method, in which case the default dialog will not be shown and JavaScript
	 * execution will be suspended. The app should call
	 * {@code JsResult.confirm()} when the custom dialog is dismissed such that
	 * JavaScript execution can be resumed.
	 */
	public boolean onJsAlert(WebView view, String url, String message,
	                         JsResult result) {
		return false;
	}

	/**
	 * Notify the host application that the web page wants to display a
	 * JavaScript {@code confirm()} dialog.
	 */
	public boolean onJsConfirm(WebView view, String url, String message,
	                           JsResult result) {
		return false;
	}

	/**
	 * Notify the host application that the web page wants to display a
	 * JavaScript {@code prompt()} dialog.
	 */
	public boolean onJsPrompt(WebView view, String url, String message,
	                          String defaultValue, JsPromptResult result) {
		return false;
	}

	/**
	 * Notify the host application that the web page wants to confirm navigation
	 * from JavaScript {@code onbeforeunload}.
	 */
	public boolean onJsBeforeUnload(WebView view, String url, String message,
	                                JsResult result) {
		return false;
	}

	/**
	 * Tell the client that the quota has been exceeded for the Web SQL Database
	 * API for a particular origin and request a new quota.
	 * @deprecated This method is no longer called; WebView now uses the HTML5 / JavaScript Quota
	 * Management API.
	 */
	@Deprecated
	public void onExceededDatabaseQuota(String url, String databaseIdentifier,
	                                    long quota, long estimatedDatabaseSize, long totalQuota,
	                                    WebStorage.QuotaUpdater quotaUpdater) {
		// This default implementation passes the current quota back to WebCore.
		// WebCore will interpret this that new quota was declined.
		quotaUpdater.updateQuota(quota);
	}

	/**
	 * Notify the host application that the web content from the specified origin is attempting to
	 * use the Geolocation API, but no permission state is currently set for that origin. The host
	 * application should invoke the specified callback with the desired permission state.
	 */
	public void onGeolocationPermissionsShowPrompt(String origin,
	                                               GeolocationPermissions.Callback callback) {}

	/**
	 * Notify the host application that a request for Geolocation permissions,
	 * made with a previous call to
	 * {@link #onGeolocationPermissionsShowPrompt(String,GeolocationPermissions.Callback) onGeolocationPermissionsShowPrompt()}
	 * has been canceled. Any related UI should therefore be hidden.
	 */
	public void onGeolocationPermissionsHidePrompt() {}

	/**
	 * Notify the host application that web content is requesting permission to
	 * access the specified resources and the permission currently isn't granted
	 * or denied. The host application must invoke {@link PermissionRequest#grant(String[])}
	 * or {@link PermissionRequest#deny()}.
	 *
	 * If this method isn't overridden, the permission is denied.
	 */
	public void onPermissionRequest(PermissionRequest request) {
		request.deny();
	}

	/**
	 * Notify the host application that the given permission request
	 * has been canceled. Any related UI should therefore be hidden.
	 */
	public void onPermissionRequestCanceled(PermissionRequest request) {}

	/**
	 * Tell the client that a JavaScript execution timeout has occured. And the
	 * client may decide whether or not to interrupt the execution. If the
	 * client returns {@code true}, the JavaScript will be interrupted. If the client
	 * returns {@code false}, the execution will continue. Note that in the case of
	 * continuing execution, the timeout counter will be reset, and the callback
	 * will continue to occur if the script does not finish at the next check
	 * point.
	 * @deprecated This method is no longer supported and will not be invoked.
	 */
	@Deprecated
	public boolean onJsTimeout() {
		return true;
	}

	/**
	 * Report a JavaScript error message to the host application. The ChromeClient
	 * should override this to process the log message as they see fit.
	 * @deprecated Use {@link #onConsoleMessage(ConsoleMessage) onConsoleMessage(ConsoleMessage)}
	 *      instead.
	 */
	@Deprecated
	public void onConsoleMessage(String message, int lineNumber, String sourceID) {}

	/**
	 * Report a JavaScript console message to the host application. The ChromeClient
	 * should override this to process the log message as they see fit.
	 * @return {@code true} if the message is handled by the client.
	 */
	public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
		// Call the old version of this function for backwards compatability.
		onConsoleMessage(consoleMessage.message(), consoleMessage.lineNumber(),
		                 consoleMessage.sourceId());
		return false;
	}

	/**
	 * When not playing, video elements are represented by a 'poster' image. The
	 * image to use can be specified by the poster attribute of the video tag in
	 * HTML.  If the attribute is absent, then a default poster will be used. This
	 * method allows the ChromeClient to provide that default image.
	 *
	 * @return Bitmap The image to use as a default poster, or {@code null} if no such image is
	 * available.
	 */
	public Bitmap getDefaultVideoPoster() {
		return null;
	}

	/**
	 * Obtains a View to be displayed while buffering of full screen video is taking
	 * place. The host application can override this method to provide a View
	 * containing a spinner or similar.
	 *
	 * @return View The View to be displayed whilst the video is loading.
	 */
	public View getVideoLoadingProgressView() {
		return null;
	}

	/** Obtains a list of all visited history items, used for link coloring
	 */
	public void getVisitedHistory(ValueCallback<String[]> callback) {
	}

	/**
	 * Tell the client to show a file chooser.
	 *
	 * This is called to handle HTML forms with 'file' input type, in response to the
	 * user pressing the "Select File" button.
	 * To cancel the request, call <code>filePathCallback.onReceiveValue(null)</code> and
	 * return {@code true}.
	 *
	 * @param webView The WebView instance that is initiating the request.
	 * @param filePathCallback Invoke this callback to supply the list of paths to files to upload,
	 *                         or {@code null} to cancel. Must only be called if the
	 *                         {@link #onShowFileChooser} implementation returns {@code true}.
	 * @param fileChooserParams Describes the mode of file chooser to be opened, and options to be
	 *                          used with it.
	 * @return {@code true} if filePathCallback will be invoked, {@code false} to use default
	 *         handling.
	 */
	public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
	                                 FileChooserParams fileChooserParams) {
		return false;
	}

	/**
	 * Parameters used in the {@link #onShowFileChooser} method.
	 */
	public static abstract class FileChooserParams {
		/** Open single file. Requires that the file exists before allowing the user to pick it. */
		public static final int MODE_OPEN = 0;
		/** Like Open but allows multiple files to be selected. */
		public static final int MODE_OPEN_MULTIPLE = 1;
		/** Like Open but allows a folder to be selected. The implementation should enumerate
		    all files selected by this operation.
		    This feature is not supported at the moment.
		    @hide */
		public static final int MODE_OPEN_FOLDER = 2;
		/**  Allows picking a nonexistent file and saving it. */
		public static final int MODE_SAVE = 3;

		/**
		 * Parse the result returned by the file picker activity. This method should be used with
		 * {@link #createIntent}. Refer to {@link #createIntent} for how to use it.
		 *
		 * @param resultCode the integer result code returned by the file picker activity.
		 * @param data the intent returned by the file picker activity.
		 * @return the Uris of selected file(s) or {@code null} if the resultCode indicates
		 *         activity canceled or any other error.
		 */
		public static Uri[] parseResult(int resultCode, Intent data) {
			if (resultCode != android.app.Activity.RESULT_OK || data == null)
				return null;
			android.content.ClipData clip = data.getClipData();
			if (clip != null && clip.getItemCount() > 0) {
				Uri[] uris = new Uri[clip.getItemCount()];
				for (int i = 0; i < uris.length; i++)
					uris[i] = clip.getItemAt(i).getUri();
				return uris;
			}
			Uri uri = data.getData();
			return uri != null ? new Uri[] {uri} : null;
		}

		/**
		 * Returns file chooser mode.
		 */
		public abstract int getMode();

		/**
		 * Returns an array of acceptable MIME types. The returned MIME type
		 * could be partial such as audio/*. The array will be empty if no
		 * acceptable types are specified.
		 */
		public abstract String[] getAcceptTypes();

		/**
		 * Returns preference for a live media captured value (e.g. Camera, Microphone).
		 * True indicates capture is enabled, {@code false} disabled.
		 *
		 * Use <code>getAcceptTypes</code> to determine suitable capture devices.
		 */
		public abstract boolean isCaptureEnabled();

		/**
		 * Returns the title to use for this file selector. If {@code null} a default title should
		 * be used.
		 */
		public abstract CharSequence getTitle();

		/**
		 * The file name of a default selection if specified, or {@code null}.
		 */
		public abstract String getFilenameHint();

		/**
		 * Creates an intent that would start a file picker for file selection.
		 * The Intent supports choosing files from simple file sources available
		 * on the device. Some advanced sources (for example, live media capture)
		 * may not be supported and applications wishing to support these sources
		 * or more advanced file operations should build their own Intent.
		 *
		 * @return an Intent that supports basic file chooser sources.
		 */
		public abstract Intent createIntent();
	}

	/**
	 * Tell the client to open a file chooser.
	 * @param uploadFile A ValueCallback to set the URI of the file to upload.
	 *      onReceiveValue must be called to wake up the thread.a
	 * @param acceptType The value of the 'accept' attribute of the input tag
	 *         associated with this file picker.
	 * @param capture The value of the 'capture' attribute of the input tag
	 *         associated with this file picker.
	 *
	 * @deprecated Use {@link #onShowFileChooser} instead.
	 * @hide This method was not published in any SDK version.
	 */
	@Deprecated
	public void openFileChooser(ValueCallback<Uri> uploadFile, String acceptType, String capture) {
		uploadFile.onReceiveValue(null);
	}

	/** teslalinux: a WebKitFileChooserRequest as FileChooserParams (Chromium's FileChooserParamsImpl). */
	static final class FileChooserParamsImpl extends FileChooserParams {
		private final String[] mAcceptTypes;
		private final boolean mMultiple;

		FileChooserParamsImpl(String[] acceptTypes, boolean multiple) {
			mAcceptTypes = acceptTypes != null ? acceptTypes : new String[0];
			mMultiple = multiple;
		}

		@Override
		public int getMode() {
			return mMultiple ? MODE_OPEN_MULTIPLE : MODE_OPEN;
		}

		@Override
		public String[] getAcceptTypes() {
			return mAcceptTypes.clone();
		}

		/* WebKitGTK's file chooser request does not carry the input's capture attribute, so capture is
		 * reported as not requested (as Chromium does for an input without capture). */
		@Override
		public boolean isCaptureEnabled() {
			return false;
		}

		@Override
		public CharSequence getTitle() {
			return null;
		}

		@Override
		public String getFilenameHint() {
			return null;
		}

		@Override
		public Intent createIntent() {
			String mimeType = "*/*";
			if (mAcceptTypes.length > 0 && mAcceptTypes[0] != null && !mAcceptTypes[0].isEmpty())
				mimeType = mAcceptTypes[0];
			Intent i = new Intent(Intent.ACTION_GET_CONTENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType(mimeType);
			if (mMultiple)
				i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
			return i;
		}
	}
}
