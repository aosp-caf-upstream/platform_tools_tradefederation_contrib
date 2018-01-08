// Copyright 2017 Google Inc. All Rights Reserved.
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the list of packages on the phone and sets all packages to be 'last used' 24 hrs ago.
 * Writes to /data/system/package-usage.list and deletes it at teardown.
 */
public class SetPackagesRecentlyUsed extends BaseTargetPreparer implements ITargetCleaner {

    private static final String LINE_PREFIX = "package:";
    private static final String PACKAGE_USAGE_FILE = "/data/system/package-usage.list";

    @Option(
        name = "package-recently-used-time",
        description = "Time since package last used",
        isTimeVal = true
    )
    private long mRecentTimeMillis = TimeUnit.DAYS.toMillis(1);

    @Option(
            name = "package-recently-used-name",
            description = "Package(s) to set. If none specified, then all are set."
    )
    private List<String> mPackages = new ArrayList<>();

    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        long deviceTimeMillis = TimeUnit.SECONDS.toMillis(device.getDeviceDate());
        long deviceRecentMillis = deviceTimeMillis - mRecentTimeMillis;
        StringBuilder builder = new StringBuilder();
        builder.append("PACKAGE_USAGE__VERSION_1\n");
        for (String p : getPackagesToSet(device)) {
            if (p.startsWith(LINE_PREFIX)) {
                builder.append(p.substring(LINE_PREFIX.length()));
                builder.append(" ");
                builder.append(Long.toString(deviceRecentMillis));
                builder.append(" 0 0 0 0 0 0 0\n");
            }
        }
        device.pushString(builder.toString(), PACKAGE_USAGE_FILE);
    }

    private List<String> getPackagesToSet(ITestDevice device) throws DeviceNotAvailableException {
        if (mPackages.isEmpty()) {
            String[] packages = device.executeShellCommand("cmd package list package").split("\n");
            return Arrays.asList(packages);
        } else {
            return mPackages;
        }
    }

    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        device.executeShellCommand("rm " + PACKAGE_USAGE_FILE);
    }
}
