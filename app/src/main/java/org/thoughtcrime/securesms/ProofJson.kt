package org.thoughtcrime.securesms

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

@Parcelize
data class ProofJson(
  val longitude: String,
  val latitude: String,
  val time: String,
  val location: String,
  val deviceName: String,
  val proofsList: List<Proofs>
): Parcelable {


  fun toJsonObject(): JSONObject {
    val json = JSONObject()
    json.put("longitude", longitude)
    json.put("latitude", latitude)
    json.put("time", time)
    json.put("location", location)
    json.put("deviceName", deviceName)
    json.put("proofsList", proofsList.toString())
    return json
  }
}

fun JSONObject.proofFromJson(): ProofJson {
  val proofList = arrayListOf<Proofs>()
  var proofString = getString("proofsList").trim()
  if (!proofString.isNullOrEmpty()) {
    proofString = proofString.replace("[","").replace("]", "")
    if (proofString.isNotEmpty()) {
      proofString.split(",").map {
        proofList.add(Proofs.valueOf(it.trim()))
      }
    }
  }
  return ProofJson(
    longitude = getString("longitude"),
    latitude = getString("latitude"),
    time = getString("time"),
    location = getString("location"),
    deviceName = getString("deviceName"),
    proofsList = proofList
  )
}
