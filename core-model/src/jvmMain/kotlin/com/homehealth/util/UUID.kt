package com.homehealth.util

import java.util.UUID

actual fun randomUUIDString(): String = UUID.randomUUID().toString()
