package org.thoughtcrime.securesms.mediasend

data class ProofMessage(
  val taken: String = "Not defined",
  val near: String = "Not defined",
  val proofs: String = "Not defined",
  val deviceName: String = "Not defined",
) {

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

  override fun toString(): String {
    return "Taken: $taken" +
      "\n Near: $near" +
      "\n Proofs: $proofs" +
      "\n Device Name: $deviceName"
  }
}
