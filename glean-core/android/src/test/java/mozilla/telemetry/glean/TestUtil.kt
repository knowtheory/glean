/* This Source Code Form is subject to the terms of the Mozilla Public
* License, v. 2.0. If a copy of the MPL was not distributed with this
* file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import mozilla.telemetry.glean.config.Configuration
import mozilla.telemetry.glean.scheduler.PingUploadWorker
import mozilla.telemetry.glean.private.PingType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert
import org.robolectric.shadows.ShadowLog
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * Checks ping content against the Glean ping schema.
 *
 * This uses the Python utility, glean_parser, to perform the actual checking.
 * This is installed in its own Miniconda environment as part of the build
 * configuration in sdk_generator.gradle.
 *
 * @param content The JSON content of the ping
 * @throws AssertionError If the JSON content is not valid
 */
internal fun checkPingSchema(content: JSONObject) {
    val os = System.getProperty("os.name")?.toLowerCase()
    val pythonExecutable =
        if (os?.indexOf("win")?.compareTo(0) == 0)
            "${BuildConfig.GLEAN_MINICONDA_DIR}/python"
        else
            "${BuildConfig.GLEAN_MINICONDA_DIR}/bin/python"

    val proc = ProcessBuilder(
        listOf(
            pythonExecutable,
            "-m",
            "glean_parser",
            "check",
            "-s",
            "${BuildConfig.GLEAN_PING_SCHEMA_URL}"
        )
    ).redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    val process = proc.start()

    val jsonString = content.toString()
    with(process.outputStream.bufferedWriter()) {
        write(jsonString)
        newLine()
        flush()
        close()
    }

    val exitCode = process.waitFor()
    assert(exitCode == 0)
}

/**
 * Checks ping content against the Glean ping schema.
 *
 * This uses the Python utility, glean_parser, to perform the actual checking.
 * This is installed in its own Miniconda environment as part of the build
 * configuration in sdk_generator.gradle.
 *
 * @param content The JSON content of the ping
 * @return the content string, parsed into a JSONObject
 * @throws AssertionError If the JSON content is not valid
 */
internal fun checkPingSchema(content: String): JSONObject {
    val jsonContent = JSONObject(content)
    checkPingSchema(jsonContent)
    return jsonContent
}

/**
 * Collects a specified ping type and checks it against the Glean ping schema.
 *
 * @param ping The ping to check
 * @return the ping contents, in a JSONObject
 * @throws AssertionError If the JSON content is not valid
 */
internal fun collectAndCheckPingSchema(ping: PingType): JSONObject {
    val jsonString = Glean.testCollect(ping)!!
    return checkPingSchema(jsonString)
}

/**
 * Resets the Glean state and trigger init again.
 *
 * @param context the application context to init Glean with
 * @param config the [Configuration] to init Glean with
 * @param clearStores if true, clear the contents of all stores
 */
internal fun resetGlean(
    context: Context = ApplicationProvider.getApplicationContext(),
    config: Configuration = Configuration(),
    clearStores: Boolean = true,
    redirectRobolectricLogs: Boolean = true
) {
    if (redirectRobolectricLogs) {
        ShadowLog.stream = System.out
    }

    // We're using the WorkManager in a bunch of places, and Glean will crash
    // in tests without this line. Let's simply put it here.
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    Glean.resetGlean(context, config, clearStores)
}

/**
 * Get a context that contains [PackageInfo.versionName] mocked to
 * "glean.version.name".
 *
 * @return an application [Context] that can be used to init Glean
 */
internal fun getContextWithMockedInfo(): Context {
    val context = Mockito.spy<Context>(ApplicationProvider.getApplicationContext<Context>())
    val packageInfo = Mockito.mock(PackageInfo::class.java)
    packageInfo.versionName = "glean.version.name"
    val packageManager = Mockito.mock(PackageManager::class.java)
    Mockito.`when`(
        packageManager.getPackageInfo(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyInt()
        )
    ).thenReturn(packageInfo)
    Mockito.`when`(context.packageManager).thenReturn(packageManager)
    return context
}

/**
 * Represents the Worker status returned by [getWorkerStatus]
 */
internal class WorkerStatus(val isEnqueued: Boolean, val workerId: UUID? = null)

/**
 * Helper function to check to see if a worker has been scheduled with the [WorkManager] and return
 * the status along with the worker ID in a [WorkerStatus] object.
 *
 * @param tag a string representing the worker tag
 * @return [WorkerStatus] that contains the enqueued state along with the ID
 */
internal fun getWorkerStatus(tag: String): WorkerStatus {
    val instance = WorkManager.getInstance()
    val statuses = instance.getWorkInfosByTag(tag)
    try {
        val workInfoList = statuses.get()
        for (workInfo in workInfoList) {
            val state = workInfo.state
            if ((state === WorkInfo.State.RUNNING) || (state === WorkInfo.State.ENQUEUED)) {
                return WorkerStatus(true, workInfo.id)
            }
        }
    } catch (e: ExecutionException) {
        // Do nothing but will return false
    } catch (e: InterruptedException) {
        // Do nothing but will return false
    }

    return WorkerStatus(false, null)
}

/**
 * Wait for a specifically tagged [WorkManager]'s Worker to be enqueued.
 *
 * @param workTag the tag of the expected Worker
 * @param timeoutMillis how log before stopping the wait. This defaults to 5000ms (5 seconds).
 */
internal fun waitForEnqueuedWorker(workTag: String, timeoutMillis: Long = 5000) = runBlocking {
    runBlocking {
        withTimeout(timeoutMillis) {
            do {
                if (getWorkerStatus(workTag).isEnqueued) {
                    return@withTimeout
                }
            } while (true)
        }
    }
}

/**
 * Helper function to simulate WorkManager being triggered since there appears to be a bug in
 * the current WorkManager test utilites that prevent it from being triggered by a test.  Once this
 * is fixed, the contents of this can be amended to trigger WorkManager directly.
 */
internal fun triggerWorkManager() {
    // Check that the work is scheduled
    val status = getWorkerStatus(PingUploadWorker.PING_WORKER_TAG)
    Assert.assertTrue("A scheduled PingUploadWorker must exist",
        status.isEnqueued)

    // Trigger WorkManager using TestDriver
    val workManagerTestInitHelper = WorkManagerTestInitHelper.getTestDriver()
    workManagerTestInitHelper.setAllConstraintsMet(status.workerId!!)
}

/**
 * Create a mock webserver that accepts all requests.
 * @return a [MockWebServer] instance
 */
internal fun getMockWebServer(): MockWebServer {
    val server = MockWebServer()
    server.setDispatcher(object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return MockResponse().setBody("OK")
        }
    })
    return server
}
