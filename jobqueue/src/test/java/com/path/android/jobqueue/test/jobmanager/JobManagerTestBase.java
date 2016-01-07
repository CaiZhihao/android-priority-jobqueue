package com.path.android.jobqueue.test.jobmanager;

import android.content.Context;

import com.path.android.jobqueue.Job;
import com.path.android.jobqueue.JobHolder;
import com.path.android.jobqueue.JobManager;
import com.path.android.jobqueue.Params;
import com.path.android.jobqueue.callback.JobManagerCallbackAdapter;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.executor.JobConsumerExecutor;
import com.path.android.jobqueue.network.NetworkEventProvider;
import com.path.android.jobqueue.network.NetworkUtil;
import com.path.android.jobqueue.test.TestBase;
import com.path.android.jobqueue.test.jobs.DummyJob;
import com.path.android.jobqueue.test.timer.MockTimer;

import org.fest.reflect.core.*;

import static org.hamcrest.CoreMatchers.*;

import org.fest.reflect.method.Invoker;
import org.hamcrest.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.robolectric.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class JobManagerTestBase extends TestBase {
    List<JobManager> createdJobManagers = new ArrayList<JobManager>();
    final MockTimer mockTimer = new MockTimer();
    @Rule public TestName name = new TestName();

    @Before
    public void logInfo() {
        System.out.println("started test " + name.getMethodName() + "/" + getClass().getSimpleName());
    }

    protected JobManager createJobManager() {
        final JobManager jobManager = createJobManager(new Configuration.Builder(RuntimeEnvironment.application)
            .timer(mockTimer)
            .inTestMode());
        createdJobManagers.add(jobManager);
        return jobManager;
    }

    protected JobManager createJobManager(Configuration.Builder configurationBuilder) {
        Configuration config = configurationBuilder.inTestMode().id(UUID.randomUUID().toString())
                .build();
        if (config.timer() != mockTimer && !canUseRealTimer()) {
            throw new IllegalArgumentException("must use mock timer");
        }
        final JobManager jobManager = new JobManager(RuntimeEnvironment.application,
                config);
        createdJobManagers.add(jobManager);

        return jobManager;
    }

    @After
    public void tearDown() throws InterruptedException {
        System.out.println("tear down " + name.getMethodName() + "/" + getClass().getSimpleName());
        for (JobManager jobManager : createdJobManagers) {
            NeverEndingDummyJob.unlockAll();
            jobManager.destroy();
        }
        System.out.println("finished tear down of " + name.getMethodName() + "/" + getClass().getSimpleName());
    }

    protected static class DummyTwoLatchJob extends DummyJob {
        private final CountDownLatch waitFor;
        private final CountDownLatch trigger;
        private final CountDownLatch onRunLatch;

        protected DummyTwoLatchJob(Params params, CountDownLatch waitFor, CountDownLatch trigger) {
            super(params);
            this.waitFor = waitFor;
            this.trigger = trigger;
            onRunLatch = new CountDownLatch(1);
        }

        public void waitTillOnRun() throws InterruptedException {
            onRunLatch.await();
        }

        @Override
        public void onRun() throws Throwable {
            onRunLatch.countDown();
            waitFor.await();
            super.onRun();
            trigger.countDown();
        }
    }

    protected static class DummyLatchJob extends DummyJob {
        private final CountDownLatch latch;

        protected DummyLatchJob(Params params, CountDownLatch latch) {
            super(params);
            this.latch = latch;
        }

        @Override
        public void onRun() throws Throwable {
            super.onRun();
            latch.countDown();
        }
    }


    protected static class DummyJobWithRunCount extends DummyJob {
        public static int runCount;
        protected DummyJobWithRunCount(boolean persistent) {
            super(new Params(0).setPersistent(persistent));
        }

        @Override
        public void onRun() throws Throwable {
            runCount++;
            super.onRun();
            throw new RuntimeException("i am dummy, i throw exception when running " + runCount);
        }

        @Override
        protected boolean shouldReRunOnThrowable(Throwable throwable) {
            return true;
        }

        @Override
        protected int getRetryLimit() {
            return 5;
        }
    }

    protected static class DummyNetworkUtil implements NetworkUtil {
        private boolean hasNetwork;

        protected void setHasNetwork(boolean hasNetwork) {
            this.hasNetwork = hasNetwork;
        }

        @Override
        public boolean isConnected(Context context) {
            return hasNetwork;
        }
    }

    protected static class DummyNetworkUtilWithConnectivityEventSupport implements NetworkUtil, NetworkEventProvider {
        private boolean hasNetwork;
        private Listener listener;

        protected void setHasNetwork(boolean hasNetwork, boolean notifyListener) {
            this.hasNetwork = hasNetwork;
            if(notifyListener && listener != null) {
                listener.onNetworkChange(hasNetwork);
            }
        }

        @Override
        public boolean isConnected(Context context) {
            return hasNetwork;
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        public boolean isConnected() {
            return hasNetwork;
        }
    }

    protected static class ObjectReference {
        Object object;

        Object getObject() {
            return object;
        }

        void setObject(Object object) {
            this.object = object;
        }
    }

    protected Invoker<JobHolder> getNextJobMethod(JobManager jobManager) {
        return Reflection.method("getNextJob").withReturnType(JobHolder.class).in(jobManager);
    }

    protected Invoker<Void> getRemoveJobMethod(JobManager jobManager) {
        return Reflection.method("removeJob").withParameterTypes(JobHolder.class).in(jobManager);
    }

    protected JobConsumerExecutor getConsumerExecutor(JobManager jobManager) {
        return Reflection.field("jobConsumerExecutor").ofType(JobConsumerExecutor.class).in(jobManager).get();
    }

    protected org.fest.reflect.field.Invoker<AtomicInteger> getActiveConsumerCount(JobConsumerExecutor jobConsumerExecutor) {
        return Reflection.field("activeConsumerCount").ofType(AtomicInteger.class).in(jobConsumerExecutor);
    }

    public static class NeverEndingDummyJob extends DummyJob {
        // used for cleanup
        static List<NeverEndingDummyJob> createdJobs = new ArrayList<NeverEndingDummyJob>();
        final Object lock;
        final Semaphore semaphore;
        public NeverEndingDummyJob(Params params, Object lock, Semaphore semaphore) {
            super(params);
            this.lock = lock;
            this.semaphore = semaphore;
            createdJobs.add(this);
        }

        @Override
        public void onRun() throws Throwable {
            super.onRun();
            MatcherAssert.assertThat("job should be able to acquire a semaphore",
                    semaphore.tryAcquire(), equalTo(true));
            synchronized (lock) {
                lock.wait();
            }
            semaphore.release();
        }

        static void unlockAll() {
            for (NeverEndingDummyJob job : createdJobs) {
                synchronized (job.lock) {
                    job.lock.notifyAll();
                }
            }
        }
    }

    protected boolean canUseRealTimer() {
        return false;
    }

    protected void waitUntilAJobIsDone(final JobManager jobManager, final WaitUntilCallback callback) throws InterruptedException {
        final CountDownLatch runJob = new CountDownLatch(1);
        jobManager.addCallback(new JobManagerCallbackAdapter() {
            @Override
            public void onDone(Job job) {
                synchronized (this) {
                    super.onDone(job);
                    if (callback != null) {
                        callback.assertJob(job);
                    }
                    runJob.countDown();
                    jobManager.removeCallback(this);
                }
            }
        });
        if (callback != null) {
            callback.run();
        }
        MatcherAssert.assertThat("The job should be done", runJob.await(1, TimeUnit.MINUTES), is(true));
    }
    protected interface WaitUntilCallback {
        void run();
        void assertJob(Job job);
    }
}
