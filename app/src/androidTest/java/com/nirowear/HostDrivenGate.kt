package com.nirowear

/** Separate host phases own process death/fixture mutation; ordinary connected run excludes these. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HostDrivenGate
