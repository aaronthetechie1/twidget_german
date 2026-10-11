package com.tjg.twidget.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Public platform contracts only: no test dependencies or artificial app keep rules. */
public final class MinifiedSmokeRunner extends Instrumentation {
    private volatile boolean resumedApp;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity.getPackageName().equals(getTargetContext().getPackageName())) resumedApp = true;
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        status.putString("id", "MinifiedSmokeRunner");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        status.putString("class", "com.tjg.twidget.core.MinifiedSmokeRunner");
        status.putString("test", "manifestWorkersAndLauncher");
        sendStatus(1, status);
        try {
            Context context = getTargetContext();
            ClassLoader loader = context.getClassLoader();
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS);
            if (info.activities != null) for (ActivityInfo item : info.activities)
                loader.loadClass(item.targetActivity == null ? item.name : item.targetActivity);
            if (info.receivers != null) for (ActivityInfo item : info.receivers) loader.loadClass(item.name);
            if (info.services != null) for (ServiceInfo item : info.services) loader.loadClass(item.name);
            if (info.providers != null) for (ProviderInfo item : info.providers) loader.loadClass(item.name);
            Class<?> parameters = loader.loadClass("androidx.work.WorkerParameters");
            String[] workers = {"widget.RefreshWorker", "update.UpdateCheckWorker", "banger.BangerScanWorker",
                "schedule.BufferPublishCheckWorker", "followers.TopFollowersScanWorker", "followers.TopFollowersBridgeSyncWorker"};
            for (String name : workers) loader.loadClass("com.tjg.twidget." + name).getConstructor(Context.class, parameters);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch == null) throw new AssertionError("Launcher alias must resolve");
            startActivitySync(launch);
            waitForIdleSync();
            if (!resumedApp) throw new AssertionError("Launcher must resume the dashboard or onboarding");
            sendStatus(0, status);
            Bundle result = new Bundle();
            result.putString("stream", "\nOK (1 test)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            status.putString("stack", stack.toString());
            sendStatus(-2, status);
            Bundle result = new Bundle();
            result.putString("stream", stack.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
