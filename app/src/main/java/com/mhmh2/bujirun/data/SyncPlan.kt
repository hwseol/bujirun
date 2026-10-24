package com.mhmh2.bujirun.data

/**
 * What one sync with the server does, from three views of the records (key -> content hash):
 * what's on this phone, what's on the server, and the keys both had after the last sync.
 *  - on the phone, not (or differently) on the server: upload - the phone's copy wins
 *  - on the server only, never synced from here: download (a new phone, a reinstall)
 *  - synced before, now gone from the phone: deleted here, so delete it on the server
 *  - synced before, now gone from the server: deleted on another phone, so delete it here
 */
data class SyncPlan(
    val upload: Set<String>,
    val download: Set<String>,
    val deleteRemote: Set<String>,
    val deleteLocal: Set<String>,
) {
    val isEmpty get() = upload.isEmpty() && download.isEmpty() && deleteRemote.isEmpty() && deleteLocal.isEmpty()

    companion object {
        fun of(local: Map<String, String>, remote: Map<String, String>, synced: Set<String>): SyncPlan {
            val deleteRemote = (synced - local.keys) intersect remote.keys
            val deleteLocal = (synced - remote.keys) intersect local.keys
            // The server losing records (restored from an older backup, an accident) must not
            // wipe the phone: when more than a few go missing at once, they're sent back up instead.
            if (deleteLocal.size > 3 && deleteLocal.size * 4 > local.size) return of(local, remote, synced intersect remote.keys)
            return SyncPlan(
                upload = local.filter { (k, h) -> k !in deleteLocal && remote[k] != h }.keys,
                download = remote.keys - local.keys - synced,
                deleteRemote = deleteRemote,
                deleteLocal = deleteLocal,
            )
        }
    }
}
