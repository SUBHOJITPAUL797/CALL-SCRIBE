package com.example.sync

import kotlinx.coroutines.sync.Mutex

/**
 * Global mutex to prevent race conditions and duplicate entries between
 * foreground folder scanning, background WorkManager sync, and onResume checks.
 */
object SyncLock {
    val mutex = Mutex()
}
