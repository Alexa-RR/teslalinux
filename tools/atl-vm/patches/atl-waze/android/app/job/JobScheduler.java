/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.job;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * teslalinux: JobScheduler after AOSP's JobSchedulerService, in the app's process. A job runs when
 * its minimum latency has passed and its constraints hold, or at its override deadline regardless:
 * network (the active network must satisfy the job's NetworkRequest), charging and battery-not-low
 * (the car's Pi is always powered, so they hold), storage-not-low, device idle (the display never
 * idles while the app runs, so idle-only jobs wait for their deadline), content triggers (a change
 * notified on the URIs, after the update delay). Scheduling an id again replaces the job,
 * jobFinished reschedules with the job's backoff or its next period, cancel stops a running job with
 * onStopJob(STOP_REASON_CANCELLED_BY_APP), and enqueue feeds work items to dequeueWork.
 */
public class JobScheduler {
	private static final String TAG = "JobScheduler";

	public static final int RESULT_FAILURE = 0;
	public static final int RESULT_SUCCESS = 1;
	public static final int PENDING_JOB_REASON_UNDEFINED = 0;

	private static final long CONSTRAINT_RECHECK_MS = 30 * 1000;

	/* one scheduled job */
	static final class JobRecord implements Runnable {
		final JobInfo job;
		final String namespace;
		final long enqueueTime = SystemClock.elapsedRealtime();
		final ArrayList<JobWorkItem> pendingWork = new ArrayList<>();
		final ArrayList<JobWorkItem> executingWork = new ArrayList<>();
		int nextWorkId = 1;
		int numFailures;
		long earliestRunTime;
		long latestRunTime;
		boolean running;
		boolean cancelled;
		JobParameters params;
		JobService service;
		final LinkedHashSet<Uri> changedUris = new LinkedHashSet<>();
		final LinkedHashSet<String> changedAuthorities = new LinkedHashSet<>();
		long firstChangeTime;
		final ArrayList<ContentObserver> observers = new ArrayList<>();

		JobRecord(JobInfo job, String namespace) {
			this.job = job;
			this.namespace = namespace;
			long now = SystemClock.elapsedRealtime();
			if (job.isPeriodic()) {
				earliestRunTime = now + job.getIntervalMillis() - job.getFlexMillis();
				latestRunTime = now + job.getIntervalMillis();
			} else {
				earliestRunTime = job.hasEarlyConstraint() ? now + job.getMinLatencyMillis() : now;
				latestRunTime = job.hasLateConstraint() ? now + job.getMaxExecutionDelayMillis() : Long.MAX_VALUE;
			}
		}

		JobWorkItem dequeueWork() {
			synchronized (sLock) {
				if (pendingWork.isEmpty())
					return null;
				JobWorkItem w = pendingWork.remove(0);
				w.mDeliveryCount++;
				executingWork.add(w);
				return w;
			}
		}

		boolean completeWork(JobWorkItem work) {
			boolean finish;
			synchronized (sLock) {
				if (!executingWork.remove(work))
					return false;
				finish = pendingWork.isEmpty() && executingWork.isEmpty();
			}
			if (finish && running) {
				/* AOSP: the job ends once its work queue is drained and completed */
				jobFinished(this, false);
			}
			return true;
		}

		@Override
		public void run() {
			maybeRun();
		}

		void schedule(long delayMs) {
			sHandler.removeCallbacks(this);
			sHandler.postDelayed(this, Math.max(0, delayMs));
		}

		void maybeRun() {
			if (cancelled || running)
				return;
			long now = SystemClock.elapsedRealtime();
			if (now < earliestRunTime) {
				schedule(earliestRunTime - now);
				return;
			}
			boolean deadline = now >= latestRunTime;
			Network network = null;
			boolean ready = true;
			if (job.getRequiredNetwork() != null) {
				network = satisfyingNetwork(job);
				ready = network != null;
			}
			if (job.isRequireDeviceIdle())
				ready = false;
			if (job.getTriggerContentUris() != null) {
				synchronized (sLock) {
					if (changedUris.isEmpty()) {
						ready = false;
					} else {
						long update = job.getTriggerContentUpdateDelay() >= 0 ? job.getTriggerContentUpdateDelay() : 10000;
						long max = job.getTriggerContentMaxDelay() >= 0 ? job.getTriggerContentMaxDelay() : 2 * 60 * 1000;
						if (now - firstChangeTime < Math.min(update, max))
							ready = false;
					}
				}
			}
			if (!ready && !deadline) {
				long next = CONSTRAINT_RECHECK_MS;
				if (latestRunTime != Long.MAX_VALUE)
					next = Math.min(next, latestRunTime - now);
				if (job.getTriggerContentUris() == null || !changedUris.isEmpty())
					schedule(next);
				else if (latestRunTime != Long.MAX_VALUE)
					schedule(latestRunTime - now);
				return;
			}
			start(network, deadline && !ready);
		}

		void start(Network network, boolean deadlineExpired) {
			Uri[] uris = null;
			String[] authorities = null;
			synchronized (sLock) {
				if (!changedUris.isEmpty()) {
					uris = changedUris.toArray(new Uri[0]);
					authorities = changedAuthorities.toArray(new String[0]);
					changedUris.clear();
					changedAuthorities.clear();
				}
			}
			try {
				service = serviceFor(job);
			} catch (ReflectiveOperationException e) {
				Slog.e(TAG, "cannot start job service for " + job, e);
				return;
			}
			params = new JobParameters(this, uris, authorities, network, deadlineExpired);
			running = true;
			boolean result = service.onStartJob(params);
			Slog.i(TAG, "onStartJob(" + job + ") returned " + result);
			if (!result && running) {
				/* AOSP: false means the job is done */
				jobFinished(this, false);
			}
		}

		void stop(int reason) {
			if (!running || service == null)
				return;
			params.mStopReason = reason;
			running = false;
			boolean reschedule = service.onStopJob(params);
			synchronized (sLock) {
				/* AOSP: work delivered but not completed goes back to the queue */
				pendingWork.addAll(0, executingWork);
				executingWork.clear();
			}
			if (reschedule && !cancelled)
				reschedule(true);
		}

		void reschedule(boolean failure) {
			long now = SystemClock.elapsedRealtime();
			if (failure) {
				numFailures++;
				long backoff = job.getBackoffPolicy() == JobInfo.BACKOFF_POLICY_LINEAR
				    ? job.getInitialBackoffMillis() * numFailures
				    : job.getInitialBackoffMillis() * (long) Math.pow(2, numFailures - 1);
				backoff = Math.min(backoff, JobInfo.MAX_BACKOFF_DELAY_MILLIS);
				earliestRunTime = now + backoff;
				latestRunTime = Long.MAX_VALUE;
			} else {
				numFailures = 0;
				earliestRunTime = now + job.getIntervalMillis() - job.getFlexMillis();
				latestRunTime = now + job.getIntervalMillis();
			}
			schedule(earliestRunTime - now);
		}
	}

	private static final Object sLock = new Object();
	private static final Handler sHandler = new Handler(Looper.getMainLooper());
	/* namespace -> job id -> record */
	private static final Map<String, Map<Integer, JobRecord>> sJobs = new HashMap<>();
	private static final IdentityHashMap<Class<? extends JobService>, JobService> sServices = new IdentityHashMap<>();

	private final Context context;
	private final String mNamespace;

	public JobScheduler(Context context) {
		this(context, null);
	}

	private JobScheduler(Context context, String namespace) {
		this.context = context;
		this.mNamespace = namespace;
	}

	/** AOSP (API 34): a JobScheduler whose job ids live in their own namespace. */
	public JobScheduler forNamespace(String namespace) {
		if (namespace == null)
			throw new NullPointerException("namespace cannot be null");
		if (namespace.trim().isEmpty())
			throw new IllegalArgumentException("namespace cannot be empty");
		return new JobScheduler(context, namespace.trim());
	}

	public String getNamespace() {
		return mNamespace;
	}

	private Map<Integer, JobRecord> jobsLocked() {
		Map<Integer, JobRecord> m = sJobs.get(mNamespace);
		if (m == null)
			sJobs.put(mNamespace, m = new HashMap<>());
		return m;
	}

	public int schedule(JobInfo job) {
		return scheduleImpl(job, null);
	}

	/** AOSP: schedules the job if it is not pending yet (an equal pending job keeps its queue) and adds the work. */
	public int enqueue(JobInfo job, JobWorkItem work) {
		if (work == null)
			throw new NullPointerException("work cannot be null");
		return scheduleImpl(job, work);
	}

	private int scheduleImpl(JobInfo job, JobWorkItem work) {
		Slog.i(TAG, "schedule(" + job + (work != null ? ", " + work : "") + ")");
		JobRecord record;
		synchronized (sLock) {
			Map<Integer, JobRecord> jobs = jobsLocked();
			JobRecord old = jobs.get(job.getId());
			if (work != null && old != null && old.job.equals(job) && !old.cancelled) {
				work.mWorkId = old.nextWorkId++;
				old.pendingWork.add(work);
				if (!old.running)
					old.schedule(0);
				return RESULT_SUCCESS;
			}
			if (old != null)
				cancelRecordLocked(old);
			record = new JobRecord(job, mNamespace);
			if (work != null) {
				work.mWorkId = record.nextWorkId++;
				record.pendingWork.add(work);
			}
			jobs.put(job.getId(), record);
		}
		observeContent(record);
		record.schedule(record.earliestRunTime - SystemClock.elapsedRealtime());
		return RESULT_SUCCESS;
	}

	private void observeContent(final JobRecord record) {
		JobInfo.TriggerContentUri[] uris = record.job.getTriggerContentUris();
		if (uris == null)
			return;
		for (final JobInfo.TriggerContentUri t : uris) {
			ContentObserver observer = new ContentObserver(sHandler) {
				@Override
				public void onChange(boolean selfChange, Uri uri) {
					synchronized (sLock) {
						if (record.changedUris.isEmpty())
							record.firstChangeTime = SystemClock.elapsedRealtime();
						Uri changed = uri != null ? uri : t.getUri();
						record.changedUris.add(changed);
						if (changed.getAuthority() != null)
							record.changedAuthorities.add(changed.getAuthority());
					}
					long update = record.job.getTriggerContentUpdateDelay() >= 0 ? record.job.getTriggerContentUpdateDelay() : 10000;
					record.schedule(update);
				}
			};
			record.observers.add(observer);
			context.getContentResolver().registerContentObserver(t.getUri(), (t.getFlags() & JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS) != 0, observer);
		}
	}

	private void cancelRecordLocked(JobRecord record) {
		record.cancelled = true;
		sHandler.removeCallbacks(record);
		for (ContentObserver o : record.observers)
			context.getContentResolver().unregisterContentObserver(o);
		record.observers.clear();
		Map<Integer, JobRecord> jobs = sJobs.get(record.namespace);
		if (jobs != null && jobs.get(record.job.getId()) == record)
			jobs.remove(record.job.getId());
		if (record.running) {
			final JobRecord r = record;
			sHandler.post(new Runnable() {
				@Override
				public void run() {
					r.stop(JobParameters.STOP_REASON_CANCELLED_BY_APP);
				}
			});
		}
	}

	public void cancel(int jobId) {
		synchronized (sLock) {
			JobRecord record = jobsLocked().get(jobId);
			if (record != null)
				cancelRecordLocked(record);
		}
	}

	/** AOSP: cancels every job of this namespace. */
	public void cancelAll() {
		synchronized (sLock) {
			for (JobRecord r : new ArrayList<>(jobsLocked().values()))
				cancelRecordLocked(r);
		}
	}

	/** AOSP (API 34): cancels the jobs of every namespace. */
	public void cancelInAllNamespaces() {
		synchronized (sLock) {
			for (Map<Integer, JobRecord> jobs : new ArrayList<>(sJobs.values()))
				for (JobRecord r : new ArrayList<>(jobs.values()))
					cancelRecordLocked(r);
		}
	}

	public List<JobInfo> getAllPendingJobs() {
		synchronized (sLock) {
			List<JobInfo> out = new ArrayList<>();
			for (JobRecord r : jobsLocked().values())
				out.add(r.job);
			return out;
		}
	}

	public Map<String, List<JobInfo>> getPendingJobsInAllNamespaces() {
		synchronized (sLock) {
			Map<String, List<JobInfo>> out = new HashMap<>();
			for (Map.Entry<String, Map<Integer, JobRecord>> e : sJobs.entrySet()) {
				List<JobInfo> list = new ArrayList<>();
				for (JobRecord r : e.getValue().values())
					list.add(r.job);
				out.put(e.getKey(), list);
			}
			return out;
		}
	}

	public JobInfo getPendingJob(int jobId) {
		synchronized (sLock) {
			JobRecord r = jobsLocked().get(jobId);
			return r != null ? r.job : null;
		}
	}

	public int getPendingJobReason(int jobId) {
		return PENDING_JOB_REASON_UNDEFINED;
	}

	/** AOSP: whether the app may schedule user-initiated jobs (RUN_USER_INITIATED_JOBS). */
	public boolean canRunUserInitiatedJobs() {
		return context.checkSelfPermission("android.permission.RUN_USER_INITIATED_JOBS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
	}

	/** JobService.jobFinished: the job ends; a reschedule request backs off, a periodic job waits for its next period. */
	static void jobFinished(JobRecord record, boolean wantsReschedule) {
		if (!record.running)
			return;
		record.running = false;
		synchronized (sLock) {
			record.executingWork.clear();
		}
		Slog.i(TAG, "jobFinished(" + record.job + ", reschedule=" + wantsReschedule + ")");
		if (record.cancelled)
			return;
		if (wantsReschedule) {
			record.reschedule(true);
		} else if (record.job.isPeriodic()) {
			record.reschedule(false);
		} else if (!record.pendingWork.isEmpty()) {
			record.schedule(0);
		} else {
			synchronized (sLock) {
				Map<Integer, JobRecord> jobs = sJobs.get(record.namespace);
				if (jobs != null && jobs.get(record.job.getId()) == record)
					jobs.remove(record.job.getId());
				record.cancelled = true;
			}
		}
	}

	private static Network satisfyingNetwork(JobInfo job) {
		Context ctx = ATLLoadedApp.getPrimaryApplication().getApplication();
		ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm == null)
			return null;
		Network network = cm.getActiveNetwork();
		if (network == null)
			return null;
		NetworkCapabilities caps = cm.getNetworkCapabilities(network);
		if (caps == null)
			return null;
		return job.getRequiredNetwork().canBeSatisfiedBy(caps) ? network : null;
	}

	private static JobService serviceFor(JobInfo job) throws ReflectiveOperationException {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		Class<? extends JobService> cls = app.loadClass(job.getService().getClassName()).asSubclass(JobService.class);
		synchronized (sServices) {
			JobService service = sServices.get(cls);
			if (service == null) {
				service = cls.getConstructor().newInstance();
				service.attachBaseContext(app.createContext(null, null, app.pkg.applicationInfo.theme));
				service.onCreate();
				sServices.put(cls, service);
			}
			return service;
		}
	}
}
