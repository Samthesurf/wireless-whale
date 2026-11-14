package com.samuel.wirelesswhale.core.domain

import com.samuel.wirelesswhale.core.model.WifiNetwork
import com.samuel.wirelesswhale.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSortTest {

  private fun network(id: String, ssid: String, rssi: Int) =
    WifiNetwork(
      id = id,
      ssid = ssid,
      bssid = id,
      rssiDbm = rssi,
      frequencyMhz = 2412,
      security = WifiSecurity.WPA2_PERSONAL,
      lastSeenAtMillis = 1_000L,
    )

  @Test
  fun sorts_strongestFirst() {
    val sorted =
      sortWifiNetworks(
        listOf(
          network("aa:aa:aa:aa:aa:aa", "Weak", -80),
          network("bb:bb:bb:bb:bb:bb", "Strong", -50),
          network("cc:cc:cc:cc:cc:cc", "Mid", -65),
        ),
      )
    assertEquals(listOf("Strong", "Mid", "Weak"), sorted.map { it.ssid })
  }

  @Test
  fun connectedNetwork_sortsFirst() {
    val sorted =
      sortWifiNetworks(
        listOf(
          network("aa:aa:aa:aa:aa:aa", "Strong", -50),
          network("bb:bb:bb:bb:bb:bb", "Weak", -80),
        ),
        connectedBssid = "BB:BB:BB:BB:BB:BB",
      )
    assertEquals(listOf("Weak", "Strong"), sorted.map { it.ssid })
  }

  @Test
  fun ties_breakBySsid() {
    val sorted =
      sortWifiNetworks(
        listOf(
          network("bb:bb:bb:bb:bb:bb", "zebra", -60),
          network("aa:aa:aa:aa:aa:aa", "Alpha", -60),
        ),
      )
    assertEquals(listOf("Alpha", "zebra"), sorted.map { it.ssid })
  }
}
