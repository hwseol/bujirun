package com.mhmh2.bujirun

/**
 * The app's language: Korean (the default) or English, chosen in the settings. Every text in
 * the app is written as [tr] with both, side by side - a few hundred short lines don't need a
 * resource system, and keeping the two together keeps them in step. Changing it recreates the
 * screen; the voice switches language with it.
 */
object Lang {
    @Volatile
    var english = false
        internal set
}

/** [ko] or [en], by the language chosen in the settings. */
fun tr(ko: String, en: String): String = if (Lang.english) en else ko
