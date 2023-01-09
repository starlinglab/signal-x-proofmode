package org.thoughtcrime.securesms.mediasend

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProofMessage(
  val taken: String = "Not defined",
  val near: String = "Not defined",
  val proofs: String = "Not defined",
  val deviceName: String = "Not defined",
  val networkType: String = "Not defined",
): Parcelable {

  fun getTakenText(): String {
    return "Taken: $taken"
  }

  fun getNearText(): String {
    return "Near: $near"
  }

  fun getProofsText(): String {
    return "Proofs: $proofs"
  }

  fun getDeviceNameText(): String {
    return "Device Name: $deviceName"
  }

  fun getNetworkTypeText(): String {
    return "Network Type: $networkType"
  }

  override fun toString(): String {
    return "Taken: $taken" +
      "\n Near: $near" +
      "\n Proofs: $proofs" +
      "\n Device Name: $deviceName"
  }
}
