package com.clipd

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // 服务未运行就拉起来；运行中无操作
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val running = am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ClipSyncService::class.java.name }
            val prefs = getSharedPreferences(Sync.PREFS_NAME, Context.MODE_PRIVATE)
            val manuallyStopped = prefs.getBoolean("manually_stopped", false)
            if (!running && !manuallyStopped) {
                startForegroundService(Intent(this, ClipSyncService::class.java))
            }
        } catch (_: Exception) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        const val JOB_ID = 8888

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            val cn = ComponentName(ctx, KeepAliveJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, cn)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // 15 分钟最小周期；系统会做合理 batching
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }
    }
}
