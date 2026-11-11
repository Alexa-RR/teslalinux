package android.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * teslalinux: AOSP RemoteViews semantics without the cross-process parts: a layout resource of a
 * package plus a list of actions, recorded by the setters and performed on the inflated views by
 * apply()/reapply(), as AOSP's notification shade, app widget host or media panel would. The
 * actions are the AOSP ones (ReflectionAction for setInt/setLong/setBoolean/..., the dedicated
 * actions for text, images, visibility, padding, click intents and nested RemoteViews). Nothing in
 * this runtime hosts remote views (no system UI), so they are applied only when an app does it.
 * Parceling writes the package, layout and the action count; actions hold live objects
 * (bitmaps, PendingIntents) and stay in-process.
 */
public class RemoteViews implements Parcelable {
	private final String mPackage;
	private final int mLayoutId;
	private final ArrayList<Action> mActions = new ArrayList<Action>();
	private int mViewId = View.NO_ID;

	private interface Action {
		void apply(View root) throws Exception;
	}

	public RemoteViews(String packageName, int layoutId) {
		mPackage = packageName;
		mLayoutId = layoutId;
	}

	public RemoteViews(RemoteViews src) {
		mPackage = src.mPackage;
		mLayoutId = src.mLayoutId;
		mActions.addAll(src.mActions);
		mViewId = src.mViewId;
	}

	protected RemoteViews(Parcel parcel) {
		mPackage = parcel.readString();
		mLayoutId = parcel.readInt();
		parcel.readInt();
	}

	public String getPackage() {
		return mPackage;
	}

	public int getLayoutId() {
		return mLayoutId;
	}

	public void setViewId(int viewId) {
		mViewId = viewId;
	}

	public int getViewId() {
		return mViewId;
	}

	@Override
	public RemoteViews clone() {
		return new RemoteViews(this);
	}

	private static View find(View root, int viewId) {
		View v = root.findViewById(viewId);
		return v;
	}

	/* ---- actions ---- */

	public void setViewVisibility(final int viewId, final int visibility) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v != null)
					v.setVisibility(visibility);
			}
		});
	}

	public void setTextViewText(final int viewId, final CharSequence text) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v instanceof TextView)
					((TextView) v).setText(text);
			}
		});
	}

	public void setTextViewTextSize(final int viewId, final int units, final float size) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v instanceof TextView)
					((TextView) v).setTextSize(units, size);
			}
		});
	}

	public void setTextColor(final int viewId, final int color) {
		setInt(viewId, "setTextColor", color);
	}

	public void setImageViewResource(final int viewId, final int srcId) {
		setInt(viewId, "setImageResource", srcId);
	}

	public void setImageViewBitmap(final int viewId, final Bitmap bitmap) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v instanceof ImageView)
					((ImageView) v).setImageBitmap(bitmap);
			}
		});
	}

	public void setContentDescription(final int viewId, final CharSequence contentDescription) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v != null)
					v.setContentDescription(contentDescription);
			}
		});
	}

	public void setOnClickPendingIntent(final int viewId, final PendingIntent pendingIntent) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v == null)
					return;
				if (pendingIntent == null) {
					v.setOnClickListener(null);
					return;
				}
				v.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View view) {
						try {
							pendingIntent.send();
						} catch (Exception e) {
							android.util.Log.e("RemoteViews", "Cannot send pending intent: ", e);
						}
					}
				});
			}
		});
	}

	public void setViewPadding(final int viewId, final int left, final int top, final int right, final int bottom) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v != null)
					v.setPadding(left, top, right, bottom);
			}
		});
	}

	public void setProgressBar(final int viewId, final int max, final int progress, final boolean indeterminate) {
		setBoolean(viewId, "setIndeterminate", indeterminate);
		if (!indeterminate) {
			setInt(viewId, "setMax", max);
			setInt(viewId, "setProgress", progress);
		}
	}

	public void setChronometer(int viewId, long base, String format, boolean started) {
		setLong(viewId, "setBase", base);
		setString(viewId, "setFormat", format);
		setBoolean(viewId, "setStarted", started);
	}

	public void setChronometerCountDown(int viewId, boolean isCountDown) {
		setBoolean(viewId, "setCountDown", isCountDown);
	}

	public void removeAllViews(final int viewId) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v instanceof ViewGroup)
					((ViewGroup) v).removeAllViews();
			}
		});
	}

	public void addView(final int viewId, final RemoteViews nestedView) {
		mActions.add(new Action() {
			public void apply(View root) {
				View v = find(root, viewId);
				if (v instanceof ViewGroup && nestedView != null) {
					final ViewGroup group = (ViewGroup) v;
					group.addView(nestedView.apply(group.getContext(), group));
				}
			}
		});
	}

	/* ---- AOSP ReflectionAction: a public one-argument method of the target view ---- */

	private void reflect(final int viewId, final String methodName, final Class<?> type, final Object value) {
		mActions.add(new Action() {
			public void apply(View root) throws Exception {
				View v = find(root, viewId);
				if (v == null)
					return;
				Method m = v.getClass().getMethod(methodName, type);
				m.invoke(v, value);
			}
		});
	}

	public void setBoolean(int viewId, String methodName, boolean value) {
		reflect(viewId, methodName, boolean.class, value);
	}

	public void setByte(int viewId, String methodName, byte value) {
		reflect(viewId, methodName, byte.class, value);
	}

	public void setShort(int viewId, String methodName, short value) {
		reflect(viewId, methodName, short.class, value);
	}

	public void setInt(int viewId, String methodName, int value) {
		reflect(viewId, methodName, int.class, value);
	}

	public void setLong(int viewId, String methodName, long value) {
		reflect(viewId, methodName, long.class, value);
	}

	public void setFloat(int viewId, String methodName, float value) {
		reflect(viewId, methodName, float.class, value);
	}

	public void setDouble(int viewId, String methodName, double value) {
		reflect(viewId, methodName, double.class, value);
	}

	public void setChar(int viewId, String methodName, char value) {
		reflect(viewId, methodName, char.class, value);
	}

	public void setString(int viewId, String methodName, String value) {
		reflect(viewId, methodName, String.class, value);
	}

	public void setCharSequence(int viewId, String methodName, CharSequence value) {
		reflect(viewId, methodName, CharSequence.class, value);
	}

	public void setBitmap(int viewId, String methodName, Bitmap value) {
		reflect(viewId, methodName, Bitmap.class, value);
	}

	public void setIcon(int viewId, String methodName, Icon value) {
		reflect(viewId, methodName, Icon.class, value);
	}

	/* ---- applying ---- */

	/** AOSP: inflates the layout (in the package's context) and performs the actions on it */
	public View apply(Context context, ViewGroup parent) {
		Context ctx = context;
		if (mPackage != null && !mPackage.equals(context.getPackageName())) {
			try {
				ctx = context.createPackageContext(mPackage, Context.CONTEXT_RESTRICTED);
			} catch (Exception e) {
				ctx = context;
			}
		}
		LayoutInflater inflater = LayoutInflater.from(ctx).cloneInContext(ctx);
		/* AOSP also installs a LayoutInflater.Filter allowing @RemoteView classes only; this
		 * LayoutInflater has no filter hook (cross-slice), so the layout inflates unfiltered */
		View result = inflater.inflate(mLayoutId, parent, false);
		if (mViewId != View.NO_ID)
			result.setId(mViewId);
		reapply(context, result);
		return result;
	}

	/** AOSP: performs the actions on views made by apply() */
	public void reapply(Context context, View v) {
		for (Action a : mActions) {
			try {
				a.apply(v);
			} catch (Exception e) {
				throw new ActionException(e);
			}
		}
	}

	public static class ActionException extends RuntimeException {
		public ActionException(Exception ex) {
			super(ex);
		}

		public ActionException(String message) {
			super(message);
		}
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(mPackage);
		dest.writeInt(mLayoutId);
		dest.writeInt(mActions.size());
	}

	public static final Parcelable.Creator<RemoteViews> CREATOR = new Parcelable.Creator<RemoteViews>() {
		public RemoteViews createFromParcel(Parcel parcel) {
			return new RemoteViews(parcel);
		}

		public RemoteViews[] newArray(int size) {
			return new RemoteViews[size];
		}
	};
}
