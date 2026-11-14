package com.samuel.wirelesswhale.platform.shizuku

/**
 * Pure JVM descriptor checks for the API 30 tethering control shape.
 *
 * No Android imports: every rule here is unit-testable and the shell
 * [HotspotUserService] calls these functions on live reflection data
 * instead of hardcoding its own class-name assumptions.
 *
 * Verified against the readonly UID 2000 framework probe on the Infinix
 * (API 30, hotspot OFF, no control calls made):
 * - startTethering(int, Executor, TetheringManager$StartTetheringCallback)
 * - startTethering(TetheringManager$TetheringRequest, Executor,
 *   TetheringManager$StartTetheringCallback)
 * - TetheringRequest$Builder(int) with build() returning exactly the
 *   nested TetheringRequest.
 * - stopTethering(int); a second overload
 *   stopTethering(TetheringRequest, Executor, StopTetheringCallback)
 *   exists and must never be picked for Wi-Fi-only stop.
 *
 * There is no top-level `android.net.TetheringRequest` on this build:
 * any descriptor using it is rejected.
 */
object TetheringControlResolver {

  const val REQUEST_CLASS_NAME = "android.net.TetheringManager\$TetheringRequest"
  const val BUILDER_CLASS_NAME = "android.net.TetheringManager\$TetheringRequest\$Builder"
  const val START_CALLBACK_CLASS_NAME = "android.net.TetheringManager\$StartTetheringCallback"
  const val EXECUTOR_CLASS_NAME = "java.util.concurrent.Executor"
  const val INT_TYPE_NAME = "int"

  /**
   * Exact request overload only: (TetheringRequest, Executor,
   * StartTetheringCallback). The (int, Executor, StartTetheringCallback)
   * overload and any unrelated request class are rejected, so a request
   * object can never be passed to the int overload or vice versa.
   */
  fun isRequestStartOverload(paramTypeNames: List<String>): Boolean =
    paramTypeNames.size == 3 &&
      paramTypeNames[0] == REQUEST_CLASS_NAME &&
      paramTypeNames[1] == EXECUTOR_CLASS_NAME &&
      paramTypeNames[2] == START_CALLBACK_CLASS_NAME

  /**
   * Wi-Fi-only stop only: stopTethering(int). The
   * (TetheringRequest, Executor, StopTetheringCallback) overload has a
   * different arity and is rejected here; stopAllTethering never matches
   * by name in the caller.
   */
  fun isWifiOnlyStopOverload(paramTypeNames: List<String>): Boolean =
    paramTypeNames.size == 1 && paramTypeNames[0] == INT_TYPE_NAME

  /**
   * Builder shape from the probe: single int constructor plus build()
   * returning exactly the nested request type. Null or mismatched build
   * returns fail closed.
   */
  fun isSupportedBuilderShape(
    ctorParamTypeNames: List<String>,
    buildReturnTypeName: String?,
  ): Boolean =
    ctorParamTypeNames.size == 1 &&
      ctorParamTypeNames[0] == INT_TYPE_NAME &&
      buildReturnTypeName == REQUEST_CLASS_NAME

  /**
   * Derives the Builder descriptor from the request class of the chosen
   * start method. Only the exact nested request maps to a Builder; a
   * top-level or unrelated request class yields null so the caller fails
   * closed instead of guessing a Builder path.
   */
  fun builderClassNameFor(requestClassName: String): String? =
    if (requestClassName == REQUEST_CLASS_NAME) BUILDER_CLASS_NAME else null

  /** Reflection names for one method/constructor parameter list. */
  fun paramNames(types: Array<Class<*>>): List<String> = types.map { it.name }
}
