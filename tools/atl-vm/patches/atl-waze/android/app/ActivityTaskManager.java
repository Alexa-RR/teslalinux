package android.app;

/**
 * teslalinux: AOSP's ActivityTaskManager is the client of the system's activity task manager
 * service. ATL runs one app in one task, so only the identifiers other framework classes use are
 * provided; the task of the running app is TASK_ID_RUNNING_APP.
 * @hide
 */
public class ActivityTaskManager {
    /** AOSP: an invalid stack id. */
    public static final int INVALID_STACK_ID = -1;
    /** AOSP: an invalid task id. */
    public static final int INVALID_TASK_ID = -1;
    /** AOSP: an invalid windowing mode. */
    public static final int INVALID_WINDOWING_MODE = -1;
    /** The id of the single task the running app lives in. */
    public static final int TASK_ID_RUNNING_APP = 1;
    /** AOSP: the default minimal size of a resizable task, in DIPs. */
    public static final int DEFAULT_MINIMAL_SPLIT_SCREEN_DISPLAY_SIZE_DP = 440;

    private ActivityTaskManager() {
    }

    /** AOSP: whether the device supports resizable tasks (multi-window); ATL shows one task. */
    public static boolean supportsMultiWindow(android.content.Context context) {
        return false;
    }

    public static boolean supportsSplitScreenMultiWindow(android.content.Context context) {
        return false;
    }

    /** AOSP: the maximum number of recent tasks the system keeps; ATL keeps the one running task. */
    public static int getMaxRecentTasksStatic() {
        return 1;
    }

    public static int getMaxNumPictureInPictureActions(android.content.Context context) {
        return 0;
    }
}
