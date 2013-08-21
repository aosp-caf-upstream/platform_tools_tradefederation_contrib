/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.media.tests;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.BugreportCollector;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;

import junit.framework.Assert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraShotToShotLatencyTest implements IDeviceTest, IRemoteTest {

    private static final Pattern MEAN_PATTERN =
            Pattern.compile("(Shot to shot latency - mean:)(\\s*)(\\d+\\.\\d*)");
    private static final Pattern STANDARD_DEVIATION_PATTERN =
            Pattern.compile("(Shot to shot latency - standard deviation:)(\\s*)(\\d+\\.\\d*)");

    private static final String TEST_CLASS_NAME = "com.android.camera.stress.ShotToShotLatency";
    private static final String TEST_PACKAGE_NAME = "com.google.android.camera.tests";
    private static final String TEST_RUNNER_NAME = "android.test.InstrumentationTestRunner";

    private static final String LATENCY_KEY_MEAN = "Shot2ShotLatencyMean";
    private static final String LATENCY_KEY_SD = "Shot2ShotLatencySD";
    private static final String TEST_RU = "CameraLatency";

    private final String mOutputPath = "mediaStressOut.txt";
    ITestDevice mTestDevice = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        Assert.assertNotNull(mTestDevice);

        IRemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(TEST_PACKAGE_NAME,
                TEST_RUNNER_NAME, mTestDevice.getIDevice());
        runner.setClassName(TEST_CLASS_NAME);

        BugreportCollector bugListener = new BugreportCollector(listener, mTestDevice);
        bugListener.addPredicate(BugreportCollector.AFTER_FAILED_TESTCASES);
        bugListener.setDescriptiveName(this.getClass().getName());
        Assert.assertTrue(mTestDevice.runInstrumentationTests(runner, bugListener));

        Map<String, String> metrics = parseOutputFile();
        reportMetrics(bugListener, TEST_RU, metrics);
        cleanupDevice();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDevice(ITestDevice device) {
        mTestDevice = device;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    /**
     * Wipes the device's external memory of test collateral from prior runs.
     * Note that all photos on the test device will be removed.
     * @throws DeviceNotAvailableException If the device is unavailable or
     *         something happened while deleting files
     */
    private void cleanupDevice() throws DeviceNotAvailableException {
        String extStore = mTestDevice.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE);
        mTestDevice.executeShellCommand(String.format("rm -r %s/DCIM", extStore));
        mTestDevice.executeShellCommand(String.format("rm %s/%s", extStore, mOutputPath));
    }

    /**
     * Parses the output file generated by the underlying instrumentation test
     * and returns the metrics to the main driver for later reporting.
     * @return The {@link Map} that contains metrics for the test.
     * @throws DeviceNotAvailableException If the device is unavailable or
     *         something happened while deleting files
     */
    private Map<String, String> parseOutputFile() throws DeviceNotAvailableException {
        BufferedReader reader = null;
        File outputFile = null;
        String lineMean = null, lineSd = null;
        Matcher m = null;
        Map<String, String> metrics = new HashMap<String, String>();

        // Read in data
        // Output file is only 2 lines and should look something like:
        // "Shot to shot latency - mean: 1234.5678901"
        // "Shot to shot latency - standard deviation: 123.45678901"
        try {
            outputFile = mTestDevice.pullFileFromExternal(mOutputPath);
            reader = new BufferedReader(new FileReader(outputFile));

            lineMean = reader.readLine();
            lineSd = reader.readLine();

            if ((lineMean == null) || (lineSd == null)) {
                CLog.e(String.format("Unable to find output data; hit EOF: \nmean:%s\nsd:%s",
                        lineMean, lineSd));
            } else {
                m = MEAN_PATTERN.matcher(lineMean);
                if (m.matches()) {
                    metrics.put(LATENCY_KEY_MEAN, m.group(3));
                } else {
                    CLog.e(String.format("Unable to find mean: %s", lineMean));
                }

                m = STANDARD_DEVIATION_PATTERN.matcher(lineSd);
                if (m.matches()) {
                    metrics.put(LATENCY_KEY_SD, m.group(3));
                } else {
                    CLog.e(String.format("Unable to find standard deviation: %s", lineSd));
                }
            }
        } catch (IOException e) {
            CLog.e(String.format("IOException reading from file: %s", e.toString()));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    CLog.e(String.format("IOException closing file: %s", e.toString()));
                }
            }
        }

        return metrics;
    }

    /**
     * Report run metrics by creating an empty test run to stick them in.
     * @param listener The {@link ITestInvocationListener} of test results
     * @param runName The test name
     * @param metrics The {@link Map} that contains metrics for the given test
     */
    private void reportMetrics(ITestInvocationListener listener, String runName,
            Map<String, String> metrics) {
        InputStreamSource bugreport = mTestDevice.getBugreport();
        listener.testLog("bugreport", LogDataType.BUGREPORT, bugreport);
        bugreport.cancel();

        CLog.d(String.format("About to report metrics: %s", metrics));
        listener.testRunStarted(runName, 0);
        listener.testRunEnded(0, metrics);
    }
}
