package android.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * teslalinux: the annotation types and tracing entry points of AOSP ViewDebug. Framework classes
 * annotate fields and getters for the hierarchy viewer; the viewer-side helpers of AOSP (dumping a
 * hierarchy over a socket to the tool) need the system view server, which does not exist here, so
 * the traces go to the log.
 */
public class ViewDebug {
    @Deprecated
    public static final boolean TRACE_HIERARCHY = false;
    @Deprecated
    public static final boolean TRACE_RECYCLER = false;
    public static final boolean DEBUG_DRAG = false;
    public static final boolean DEBUG_POSITIONING = false;

    @Target({ElementType.FIELD, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ExportedProperty {
        boolean resolveId() default false;
        IntToString[] mapping() default { };
        IntToString[] indexMapping() default { };
        FlagToString[] flagMapping() default { };
        boolean deepExport() default false;
        String prefix() default "";
        String category() default "";
        boolean formatToHexString() default false;
        boolean hasAdjacentMapping() default false;
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface IntToString {
        int from();
        String to();
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FlagToString {
        int mask();
        int equals();
        String name();
        boolean outputIf() default true;
    }

    @Target({ElementType.FIELD, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CapturedViewProperty {
        boolean retrieveReturn() default false;
    }

    public enum HierarchyTraceType {
        INVALIDATE, INVALIDATE_CHILD, INVALIDATE_CHILD_IN_PARENT, REQUEST_LAYOUT, ON_LAYOUT, ON_MEASURE, DRAW, BUILD_CACHE
    }

    public enum RecyclerTraceType {
        NEW_VIEW, BIND_VIEW, RECYCLE_FROM_ACTIVE_HEAP, RECYCLE_FROM_SCRAP_HEAP, MOVE_TO_SCRAP_HEAP, MOVE_FROM_ACTIVE_TO_SCRAP_HEAP
    }

    public static void trace(View view, HierarchyTraceType type) {
        android.util.Log.d("ViewDebug", type + " " + view);
    }

    public static void trace(View view, RecyclerTraceType type, int... parameters) {
        android.util.Log.d("ViewDebug", type + " " + view + " " + java.util.Arrays.toString(parameters));
    }

    public static void dumpCapturedView(String tag, Object view) {
        android.util.Log.d(tag, String.valueOf(view));
    }
}
