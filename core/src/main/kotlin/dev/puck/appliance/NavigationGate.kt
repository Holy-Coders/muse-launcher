package dev.puck.appliance

/** Package-only handoff lease. No screen contents or key events enter this policy. */
object NavigationGate {
    fun allowed(packageName:String,ownPackage:String,requested:Set<String>,now:Long,expiresAt:Long,
                enabled:Boolean,locked:Boolean,systemPackages:Set<String> = emptySet()):Boolean = !enabled || locked || packageName in systemPackages ||
        packageName in setOf(ownPackage,"android","com.android.systemui","com.google.android.permissioncontroller",
            "com.android.permissioncontroller","com.google.android.apps.googlevoice") ||
        (now<expiresAt && packageName in requested)
}
