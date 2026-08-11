package com.newax.aegis.db

import kotlinx.datetime.Clock

actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
