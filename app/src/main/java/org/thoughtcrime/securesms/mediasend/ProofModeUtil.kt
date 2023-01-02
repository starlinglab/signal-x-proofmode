package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.location.Location
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.ProofJson
import org.thoughtcrime.securesms.Proofs
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

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

  fun getTime(draftTime: String): String {
    return try {
      val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

      formatter.timeZone = TimeZone.getTimeZone("UTC")
      val value = formatter.parse(draftTime)
      val dateFormatter = SimpleDateFormat("h:mm a") //this format changeable
      dateFormatter.timeZone = TimeZone.getDefault()
      dateFormatter.format(value)
    } catch (e: Exception) {
      draftTime
    }
  }

  fun convertLongToTime(time: String): String {
    val df = SimpleDateFormat("yyyy-mm-dd'T'hh:mm'Z'")
    val t = df.parse(time).time
    val date = Date(t)
    val format = SimpleDateFormat("h:mm a")
    return format.format(date)
  }

  fun convert(latitude: Double, longitude: Double): String {
    val builder = StringBuilder()
    if (latitude < 0) {
      builder.append("S ")
    } else {
      builder.append("N ")
    }
    val latitudeDegrees: String = Location.convert(abs(latitude), Location.FORMAT_SECONDS)
    val latitudeSplit = latitudeDegrees.split(":").toTypedArray()
    builder.append(latitudeSplit[0])
    builder.append("°")
    builder.append(latitudeSplit[1])
    builder.append("'")
    builder.append(latitudeSplit[2])
    builder.append("\"")
    builder.append(" ")
    if (longitude < 0) {
      builder.append("W ")
    } else {
      builder.append("E ")
    }
    val longitudeDegrees: String = Location.convert(abs(longitude), Location.FORMAT_SECONDS)
    val longitudeSplit = longitudeDegrees.split(":").toTypedArray()
    builder.append(longitudeSplit[0])
    builder.append("°")
    builder.append(longitudeSplit[1])
    builder.append("'")
    builder.append(longitudeSplit[2])
    builder.append("\"")
    return builder.toString()
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

  private fun saveJson(file: File, context: Context) {
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
      latitude = json.getString("Location.Latitude"),
      time = json.getString("Proof Generated"),
      location = json.getString("Location.Bearing"),
      proofsList = proofs
    )
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PROOF_OBJECT, proofJson.toJsonObject().toString()).apply()
  }

}

fun parseProofObjectFromString(proof: String): ArrayList<String> {
  val resultList = arrayListOf<String>()
  val takenText = proof.substringAfter("\n").substringBefore("\n")
  val nearText = proof.substringAfter("Near:").substringBefore("\n")
  val proofsList = proof.substringAfter("Proofs:").substringBefore("\n")
  resultList.add(takenText)
  resultList.add(nearText)
  resultList.add(proofsList)
  return resultList
}