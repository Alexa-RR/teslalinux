package android.view.accessibility;

import android.os.Bundle;
import android.os.IBinder;
import java.util.Collections;
import java.util.List;

/**
 * teslalinux: AOSP's client for querying accessibility nodes of other windows through the
 * accessibility manager service. ATL has no accessibility services and no such service process,
 * so no connection id is ever registered: every query reports the no-connection result that AOSP
 * returns when the caller's connection is gone (null node, false action, empty lists).
 * @hide
 */
public final class AccessibilityInteractionClient {
    private static final AccessibilityInteractionClient sInstance = new AccessibilityInteractionClient();

    private AccessibilityInteractionClient() {
    }

    public static AccessibilityInteractionClient getInstance() {
        return sInstance;
    }

    /** The connection registered for the id; none is ever registered here. */
    public static Object getConnection(int connectionId) {
        return null;
    }

    public AccessibilityNodeInfo findFocus(int connectionId, int accessibilityWindowId, long accessibilityNodeId, int focusType) {
        return null;
    }

    public AccessibilityNodeInfo focusSearch(int connectionId, int accessibilityWindowId, long accessibilityNodeId, int direction) {
        return null;
    }

    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, boolean bypassCache, int prefetchFlags, Bundle arguments) {
        return null;
    }

    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(int connectionId, IBinder leashToken,
            long accessibilityNodeId, boolean bypassCache, int prefetchFlags, Bundle arguments) {
        return null;
    }

    public boolean performAccessibilityAction(int connectionId, int accessibilityWindowId, long accessibilityNodeId,
            int action, Bundle arguments) {
        return false;
    }

    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, String text) {
        return Collections.emptyList();
    }

    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, String viewId) {
        return Collections.emptyList();
    }

    public AccessibilityWindowInfo getWindow(int connectionId, int accessibilityWindowId) {
        return null;
    }

    public AccessibilityWindowInfo getWindow(int connectionId, int accessibilityWindowId, boolean bypassCache) {
        return null;
    }
}
