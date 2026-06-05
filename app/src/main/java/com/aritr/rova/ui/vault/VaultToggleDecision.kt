package com.aritr.rova.ui.vault

/** B5 / ADR-0025 (spec R4) — only ON->OFF requires auth; turning ON is free. */
fun toggleRequiresAuth(current: Boolean, desired: Boolean): Boolean = current && !desired
