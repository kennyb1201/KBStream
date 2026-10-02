package com.kennyb1201.kbstream.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The process-wide scope for speculative background work: lazy-initialization
 * priming, cache pre-warm, and cache maintenance.
 *
 * None of this work is awaited and none of it is cancelled — the app lives in
 * these `object` singletons for the whole process — but every call site used to
 * build its own `CoroutineScope(Dispatchers.IO)` at the point of use. That had
 * two problems: there was no single place to reason about the work, and the work
 * was unbounded. A cold start primes TMDB, Simkl and the hero-artwork stack at
 * once; spread across their own scopes they could occupy every thread of
 * [Dispatchers.IO] and push a foreground fetch — a rail the viewer is actually
 * waiting on — behind a cache prune.
 *
 * One scope with a [SupervisorJob] (one failing task must not take the rest of
 * the chain down with it) on a deliberately small slice of IO keeps that from
 * happening: this work is never on the critical path, so it yields to it.
 *
 * NOT for anything that must be awaited or cancelled with its caller. A screen,
 * a ViewModel, or a worker uses its own scope (`viewModelScope`,
 * `lifecycleScope`, `CoroutineWorker`); those tie their work to a lifetime, and
 * moving them here would make it leak past it.
 */
object BackgroundWork {

    /**
     * Caps concurrent speculative work. Small on purpose: the foreground keeps
     * the rest of [Dispatchers.IO], so background priming can never starve it.
     * A value this low can only make cold-start priming start in waves, which
     * costs nothing the viewer can see.
     */
    private const val PARALLELISM = 4

    val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(PARALLELISM))

    /** Shorthand for [scope]`.launch`; the scope is exposed for `async`/`withContext` too. */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}
