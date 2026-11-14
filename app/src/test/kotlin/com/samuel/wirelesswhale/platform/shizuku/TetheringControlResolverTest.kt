package com.samuel.wirelesswhale.platform.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-linked regressions for the API 30 tethering control shape.
 *
 * Every assertion calls [TetheringControlResolver] — the same object the
 * shell [HotspotUserService] calls on live reflection data — with the
 * exact descriptors from the readonly UID 2000 framework probe. Nothing
 * here reimplements the selection logic.
 */
class TetheringControlResolverTest {

  private val executor = TetheringControlResolver.EXECUTOR_CLASS_NAME
  private val nestedRequest = TetheringControlResolver.REQUEST_CLASS_NAME
  private val startCallback = TetheringControlResolver.START_CALLBACK_CLASS_NAME

  // Start overload: exact nested triple vs int overload and impostors.

  @Test
  fun requestStart_exactNestedTriple_isAccepted() {
    // Verbatim probe shape: startTethering(TetheringRequest, Executor,
    // StartTetheringCallback) with nested request/callback.
    assertTrue(
      TetheringControlResolver.isRequestStartOverload(
        listOf(nestedRequest, executor, startCallback),
      ),
    )
  }

  @Test
  fun requestStart_topLevelRequest_isRejected() {
    // The actual start failure: android.net.TetheringRequest does not
    // exist on API 30, so a descriptor using it must never match.
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf("android.net.TetheringRequest", executor, startCallback),
      ),
    )
  }

  @Test
  fun requestStart_intOverload_isRejected() {
    // The (int, Executor, StartTetheringCallback) overload must never
    // be picked for a request object.
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf("int", executor, startCallback),
      ),
    )
  }

  @Test
  fun requestStart_unrelatedRequest_isRejected() {
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf("android.net.wifi.WifiConfiguration", executor, startCallback),
      ),
    )
  }

  @Test
  fun requestStart_wrongArityOrTail_isRejected() {
    // Wrong executor.
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf(nestedRequest, "android.os.Handler", startCallback),
      ),
    )
    // Stop callback on a start method.
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf(nestedRequest, executor, "android.net.TetheringManager\$StopTetheringCallback"),
      ),
    )
    // Arity mismatches.
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf(nestedRequest, executor),
      ),
    )
    assertFalse(
      TetheringControlResolver.isRequestStartOverload(
        listOf(nestedRequest, executor, startCallback, "extra"),
      ),
    )
    assertFalse(TetheringControlResolver.isRequestStartOverload(emptyList()))
  }

  // Builder: (int) ctor plus exact build() return.

  @Test
  fun builderShape_probeCtorAndReturn_isAccepted() {
    // Verbatim probe shape: Builder(int), build() returns exactly the
    // nested request.
    assertTrue(
      TetheringControlResolver.isSupportedBuilderShape(
        listOf("int"),
        nestedRequest,
      ),
    )
  }

  @Test
  fun builderShape_wrongCtorOrReturn_isRejected() {
    // No-arg constructor.
    assertFalse(
      TetheringControlResolver.isSupportedBuilderShape(emptyList(), nestedRequest),
    )
    // Wrong constructor parameter.
    assertFalse(
      TetheringControlResolver.isSupportedBuilderShape(listOf("java.lang.String"), nestedRequest),
    )
    // build() returning something else.
    assertFalse(
      TetheringControlResolver.isSupportedBuilderShape(listOf("int"), "java.lang.String"),
    )
    // build() returning the top-level impostor.
    assertFalse(
      TetheringControlResolver.isSupportedBuilderShape(listOf("int"), "android.net.TetheringRequest"),
    )
    // Missing build().
    assertFalse(
      TetheringControlResolver.isSupportedBuilderShape(listOf("int"), null),
    )
  }

  @Test
  fun builderClass_derivedFromRequestClassOnly() {
    assertEquals(
      "android.net.TetheringManager\$TetheringRequest\$Builder",
      TetheringControlResolver.builderClassNameFor(nestedRequest),
    )
    // Top-level and unrelated request classes map to nothing: the
    // caller fails closed instead of guessing a Builder path.
    assertNull(TetheringControlResolver.builderClassNameFor("android.net.TetheringRequest"))
    assertNull(TetheringControlResolver.builderClassNameFor("android.net.wifi.WifiConfiguration"))
    assertNull(TetheringControlResolver.builderClassNameFor(""))
  }

  // Wi-Fi-only stop: int only.

  @Test
  fun wifiOnlyStop_intArity_isAccepted() {
    assertTrue(TetheringControlResolver.isWifiOnlyStopOverload(listOf("int")))
  }

  @Test
  fun wifiOnlyStop_requestOverload_isRejected() {
    // The (TetheringRequest, Executor, StopTetheringCallback) overload
    // must never be picked for Wi-Fi-only stop.
    assertFalse(
      TetheringControlResolver.isWifiOnlyStopOverload(
        listOf(nestedRequest, executor, "android.net.TetheringManager\$StopTetheringCallback"),
      ),
    )
    assertFalse(TetheringControlResolver.isWifiOnlyStopOverload(emptyList()))
    assertFalse(
      TetheringControlResolver.isWifiOnlyStopOverload(listOf("int", executor)),
    )
  }

  // Probe descriptors themselves: the resolver constants match the phone.

  @Test
  fun descriptors_matchActualApi30Probe() {
    assertEquals("android.net.TetheringManager\$TetheringRequest", nestedRequest)
    assertEquals(
      "android.net.TetheringManager\$TetheringRequest\$Builder",
      TetheringControlResolver.BUILDER_CLASS_NAME,
    )
    assertEquals(
      "android.net.TetheringManager\$StartTetheringCallback",
      startCallback,
    )
  }

  // Production helper: reflection names incl. the int primitive.

  @Suppress("unused")
  private fun probeFixture(number: Int, text: String) = "$number$text"

  @Test
  fun paramNames_mapsLiveReflectionTypes() {
    val method = javaClass.declaredMethods.first { it.name == "probeFixture" }
    assertEquals(
      listOf("int", "java.lang.String"),
      TetheringControlResolver.paramNames(method.parameterTypes),
    )
  }
}
