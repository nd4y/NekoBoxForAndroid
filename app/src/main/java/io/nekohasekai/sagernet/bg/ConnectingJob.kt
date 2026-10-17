package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.Job

internal suspend fun cancelConnectingJob(job: Job, cancelNativeStart: () -> Unit) {
    job.cancel()
    // A synchronous native Start does not observe coroutine cancellation by itself.
    cancelNativeStart()
    job.join()
}
