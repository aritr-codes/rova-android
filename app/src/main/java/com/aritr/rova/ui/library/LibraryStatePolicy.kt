package com.aritr.rova.ui.library

/**
 * Which Library body state to render. Error is intentionally NOT a member — there is no VM error
 * signal yet; [com.aritr.rova.ui.library.components.LibraryError] is built but stays unrouted until
 * a signal exists, so this pure resolver remains total over its three boolean inputs. When an error
 * signal lands, add it here as a 4th input + member (don't let Error drift into ad-hoc branching).
 */
enum class LibraryStateKind { Loading, Empty, SearchEmpty, Content }

/**
 * Pure resolver for the Library body state. Separates "never loaded yet" (skeleton) from "loaded but
 * no rows" (empty), and within empty distinguishes a filter/search miss (SearchEmpty — keeps the
 * discovery bar + offers clear-filters) from a genuinely empty library (Empty — record CTA).
 * Framework-free -> JVM-tested (house seam pattern).
 */
object LibraryStatePolicy {
    fun resolve(hasLoaded: Boolean, isEmpty: Boolean, hasActiveQuery: Boolean): LibraryStateKind = when {
        !hasLoaded -> LibraryStateKind.Loading
        !isEmpty -> LibraryStateKind.Content
        hasActiveQuery -> LibraryStateKind.SearchEmpty
        else -> LibraryStateKind.Empty
    }
}
