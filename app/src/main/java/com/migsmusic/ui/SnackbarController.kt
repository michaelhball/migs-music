package com.migsmusic.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * App-wide snackbar trigger, accessible from any composable via [LocalSnackbarController].
 *
 * Designed for action-confirmation toasts ("Added to queue", "Saved as playlist") where:
 * - We don't want to queue many at once — a fresh tap supersedes any pending toast.
 * - Optional Undo action runs the supplied callback when tapped.
 *
 * Backed by [SnackbarHostState] + a coroutine scope from the host composition.
 */
class SnackbarController internal constructor(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    private var inflightJob: Job? = null

    fun show(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        // Cancel any in-flight snackbar so a fresh tap immediately replaces stale feedback.
        inflightJob?.cancel()
        // Dismiss what's already on screen so the new message animates in cleanly.
        hostState.currentSnackbarData?.dismiss()
        inflightJob =
            scope.launch {
                val result =
                    hostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        duration = SnackbarDuration.Short,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    onAction?.invoke()
                }
            }
    }
}

val LocalSnackbarController =
    compositionLocalOf<SnackbarController> {
        error(
            "SnackbarController not provided. Wrap your composable hierarchy with " +
                "CompositionLocalProvider(LocalSnackbarController provides …) — see MigsMusicApp.",
        )
    }
