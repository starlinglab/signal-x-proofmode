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
  val proofsList: List<Proofs>
): Parcelable {


  fun toJsonObject(): JSONObject {
    val json = JSONObject()
    json.put("longitude", longitude)
    json.put("latitude", latitude)
    json.put("time", time)
    json.put("location", location)
    json.put("proofsList", proofsList.toString())
    return json
  }
}

fun JSONObject.proofFromJson(): ProofJson {
  val proofList = arrayListOf<Proofs>()
  if (!getString("proofsList").trim().isNullOrEmpty()) {
    if (getString("proofsList").dropLast(1).drop(1).isNotEmpty()) {
      getString("proofsList").split(",").map {
        proofList.add(Proofs.valueOf(it.trim()))
      }
    }
  }
  return ProofJson(
    longitude = getString("longitude"),
    latitude = getString("latitude"),
    time = getString("time"),
    location = getString("location"),
    proofsList = proofList
  )
}
