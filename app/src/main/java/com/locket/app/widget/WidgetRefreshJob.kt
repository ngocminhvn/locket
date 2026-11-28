package com.locket.app.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One-shot network refresh requested by widget events; daily fallback is in widget XML. */
class WidgetRefreshJob : JobService() {
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        jobs[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            val success = try { LocketWidgetUpdater.refreshAll(applicationContext) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
            jobs.remove(params.jobId)
            jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC = 41201
        private const val IMMEDIATE = 41202
        fun schedule(context: Context, immediate: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val service = ComponentName(context, WidgetRefreshJob::class.java)
            // Remove the old 30-minute job on upgrade. The app-widget provider
            // requests a daily system refresh; other refreshes are event-driven.
            scheduler.cancel(PERIODIC)
            if (immediate && scheduler.getPendingJob(IMMEDIATE) == null) {
                scheduler.schedule(JobInfo.Builder(IMMEDIATE, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.apply { cancel(PERIODIC); cancel(IMMEDIATE) }
        }
    }
}
