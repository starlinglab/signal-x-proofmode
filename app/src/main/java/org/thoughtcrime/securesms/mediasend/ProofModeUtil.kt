package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import androidx.preference.PreferenceManager
import kotlinx.parcelize.Parcelize
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_ENABLED
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_LOCATION_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_LOCATION_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NETWORK_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NETWORK_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NOTARY_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NOTARY_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_PHONE_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_PHONE_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.PROOF_OBJECT
import org.witness.proofmode.ProofMode
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProofModeUtil {

  fun setProofSettingsGlobal(
    context: Context,
    proofDeviceIds: Boolean? = null,
    proofLocation: Boolean? = null,
    proofNetwork: Boolean? = null,
    proofNotary: Boolean? = null
  ) {
    proofDeviceIds?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_PHONE_ENABLED_GLOBAL, it).apply()
    }
    proofLocation?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_GLOBAL, it).apply()
    }
    proofNetwork?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_ENABLED_GLOBAL, it).apply()
    }
    proofNotary?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_GLOBAL, it).apply()
    }
  }

  fun setProofSettingsLocal(
    context: Context,
    proofDeviceIds: Boolean? = null,
    proofLocation: Boolean? = null,
    proofNetwork: Boolean? = null,
    proofNotary: Boolean? = null
  ) {
    proofDeviceIds?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_PHONE_ENABLED_LOCAL, it).apply()
    }
    proofLocation?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, it).apply()
    }
    proofNetwork?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_ENABLED_LOCAL, it).apply()
    }
    proofNotary?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, it).apply()
    }
  }

  fun clearLocalSettings(context: Context) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_PHONE_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PROOF_OBJECT).apply()
  }

  fun getProofHash(context: Context, uri: Uri, byteArray: ByteArray, mimeType: String): String {
    val isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)
    return if (isEnabled) {
      val proofHash = ProofMode.generateProof(context, uri, byteArray, mimeType)
      ProofMode.getProofDir(context, proofHash)

      proofHash
    } else {
      ""
    }
  }

  fun setProofPoints(
    context: Context,
    proofDeviceIds: Boolean = true,
    proofLocation: Boolean = true,
    proofNetwork: Boolean = true,
    proofNotary: Boolean = true
  ) {
    ProofMode.setProofPoints(context, proofDeviceIds, proofLocation, proofNetwork, proofNotary)
  }

  private fun settingsSetter(context: Context) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PROOF_OBJECT).apply()
    val notaryGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_GLOBAL, true)
    val locationGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_GLOBAL, true)
    val phoneGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_PHONE_ENABLED_GLOBAL, true)
    val networkGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_ENABLED_GLOBAL, true)
    val notaryLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true)
    val locationLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true)
    val phoneLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_PHONE_ENABLED_LOCAL, true)
    val networkLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_ENABLED_LOCAL, true)
    val resultNotary = if (notaryGlobal == notaryLocal) notaryGlobal else notaryLocal
    val resultLocation = if (locationGlobal == locationLocal) locationGlobal else locationLocal
    val resultPhone = if (phoneGlobal == phoneLocal) phoneGlobal else phoneLocal
    val resultNetwork = if (networkGlobal == networkLocal) networkGlobal else networkLocal

    setProofPoints(
      context = context,
      proofDeviceIds = resultPhone,
      proofLocation = resultLocation,
      proofNetwork = resultNetwork,
      proofNotary = resultNotary
    )
  }

  fun createZipProof(proofHash: String, context: Context): File {
    settingsSetter(context)

    var proofDir = ProofMode.getProofDir(context, proofHash)
    var fileZip = makeProofZip(proofDir.absoluteFile, context)

    Log.e("ZIP PATH", "zip path: $fileZip")

    return fileZip

  }

  private fun makeProofZip(proofDirPath: File, context: Context): File {
    val outputZipFile = File(proofDirPath.path, proofDirPath.name + ".zip")
    ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
      proofDirPath.walkTopDown().forEach { file ->
        if (file.name.endsWith(".json")) {
          saveJson(file, context)
        }
        val zipFileName = file.absolutePath.removePrefix(proofDirPath.absolutePath).removePrefix("/")
        val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")
        zos.putNextEntry(entry)
        if (file.isFile) {
          file.inputStream().copyTo(zos)
        }
      }

      val keyEntry = ZipEntry("pubkey.asc");
      zos.putNextEntry(keyEntry);
      var publicKey = ProofMode.getPublicKey(context)
      zos.write(publicKey.toByteArray())

      return outputZipFile
    }
  }

  fun saveJson(file: File, context: Context) {
    val notaryGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_GLOBAL, true)
    val locationGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_GLOBAL, true)
    val phoneGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_PHONE_ENABLED_GLOBAL, true)
    val networkGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_ENABLED_GLOBAL, true)
    val notaryLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true)
    val locationLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true)
    val phoneLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_PHONE_ENABLED_LOCAL, true)
    val networkLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_ENABLED_LOCAL, true)
    val resultNotary = if (notaryGlobal == notaryLocal) notaryGlobal else notaryLocal
    val resultLocation = if (locationGlobal == locationLocal) locationGlobal else locationLocal
    val resultPhone = if (phoneGlobal == phoneLocal) phoneGlobal else phoneLocal
    val resultNetwork = if (networkGlobal == networkLocal) networkGlobal else networkLocal

    val proofs = arrayListOf<Proofs>()
    if (resultNotary) proofs.add(Proofs.NOTARIES)
    if (resultPhone) proofs.add(Proofs.DEVICE_ID)
    if (resultLocation) proofs.add(Proofs.LOCATION)
    if (resultNetwork) proofs.add(Proofs.NETWORK)

    Log.e("FILE::", "$file")
    val bufferedReader: BufferedReader = file.bufferedReader()
    val inputString = bufferedReader.use { it.readText() }
    val json = JSONObject(inputString)
    val proofJson = ProofJson(
      longitude = json.getString("Location.Longitude"),
      latitude = json.getString("Location.Altitude"),
      time = json.getString("Proof Generated"),
      location = json.getString("Location.Bearing"),
      proofsList = proofs
    )
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PROOF_OBJECT, proofJson.toJsonObject().toString()).apply()
    Log.e("JSONN:", proofJson.toString())
  }
}

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
    getString("proofsList").dropLast(1).drop(1).split(",").map {
      proofList.add(Proofs.valueOf(it.trim()))
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

enum class Proofs {
  DEVICE_ID,
  NOTARIES,
  LOCATION,
  NETWORK
}