package android.provider;

import android.content.Context;
import android.content.Intent;

/**
 * teslalinux: the public constants and helpers of AOSP's Browser contract. Bookmarks and history
 * content providers belong to a browser app, which ATL does not ship; the intent extras and the
 * share helper are what framework classes (URLSpan) use.
 */
public class Browser {
    /** AOSP: the application id a browser uses to reuse a tab for a sender. */
    public static final String EXTRA_APPLICATION_ID = "com.android.browser.application_id";
    /** AOSP: extra request headers (a Bundle of header name to value). */
    public static final String EXTRA_HEADERS = "com.android.browser.headers";
    /** AOSP: open the URL in a new tab. */
    public static final String EXTRA_CREATE_NEW_TAB = "create_new_tab";
    /** AOSP: initial zoom level of the page. */
    public static final String INITIAL_ZOOM_LEVEL = "browser.initialZoomLevel";

    /** AOSP: sends the string to the user's chosen target as text (a share chooser). */
    public static final void sendString(Context context, String string) {
        sendString(context, string, context.getString(com.android.internal.R.string.sendText));
    }

    /** @hide */
    public static final void sendString(Context c, String stringToSend, String chooserDialogTitle) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, stringToSend);
        try {
            Intent i = Intent.createChooser(send, chooserDialogTitle);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (android.content.ActivityNotFoundException ex) {
            // if no app handles it, do nothing (AOSP)
        }
    }
}
