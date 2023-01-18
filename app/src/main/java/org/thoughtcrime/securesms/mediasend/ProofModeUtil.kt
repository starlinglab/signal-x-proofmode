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
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NOTARY_ENABLED_GLOBAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_NOTARY_ENABLED_LOCAL
import org.thoughtcrime.securesms.mediasend.ProofConstants.PROOF_OBJECT
import org.witness.proofmode.ProofMode
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

object ProofModeUtil {

  var photoByteArray = byteArrayOf()

  fun setProofSettingsGlobal(
    context: Context,
    proofLocation: Boolean? = null,
    proofNetwork: Boolean? = null,
    proofNotary: Boolean? = null
  ) {
    proofLocation?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_GLOBAL, it).apply()
    }
    proofNetwork?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL, it).apply()
    }
    proofNotary?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_GLOBAL, it).apply()
    }
  }

  fun formatProofTimeString(time: String): String {
    val df = SimpleDateFormat("yyyy-mm-dd'T'hh:mm'Z'")
    val t = df.parse(time).time
    val date = Date(t)
    val format = SimpleDateFormat("yyyy-MM-dd h:mm a")
    return format.format(date)
  }

  fun convertLongToTime(time: Long): String {
  //  val df = SimpleDateFormat("yyyy-mm-dd'T'hh:mm'Z'")
   // val t = df.parse(time).time
    val date = Date(time)
    val format = SimpleDateFormat("yyyy-MM-dd h:mm a")
    return format.format(date)
  }

  fun convert(latitude: Double?, longitude: Double?): String {
    if (latitude == null || longitude == null) {
      return ""
    } else {
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
  }

  fun setProofSettingsLocal(
    context: Context,
    proofLocation: Boolean? = null,
    proofNetwork: Boolean? = null,
    proofNotary: Boolean? = null
  ) {
    proofLocation?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, it).apply()
    }
    proofNetwork?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL, it).apply()
    }
    proofNotary?.let {
      PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, it).apply()
    }
  }

  fun clearLocalSettings(context: Context) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL, true).apply()
    PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PROOF_OBJECT).apply()
  }

  fun getProofHash(context: Context, uri: Uri, byteArray: ByteArray, mimeType: String): String {
    val isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)
    return if (isEnabled) {
      val proofHash = ProofMode.generateProof(context, Uri.parse("$uri.jpg"), byteArray, mimeType)
      ProofMode.getProofDir(context, proofHash)
      photoByteArray = byteArray

      proofHash
    } else {
      ""
    }
  }

  private fun setProofPoints(
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
    val networkAndPhoneGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL, true)
    val notaryLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true)
    val locationLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true)
    val networkLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL, true)
    val resultNotary = if (notaryGlobal == notaryLocal) notaryGlobal else notaryLocal
    val resultLocation = if (locationGlobal == locationLocal) locationGlobal else locationLocal
    val resultNetwork = if (networkAndPhoneGlobal == networkLocal) networkAndPhoneGlobal else networkLocal

    setProofPoints(
      context = context,
      proofLocation = resultLocation,
      proofNetwork = resultNetwork,
      proofNotary = resultNotary
    )
  }

  fun createZipProof(proofHash: String, context: Context): File {
    settingsSetter(context)

    val proofDir = ProofMode.getProofDir(context, proofHash)
    val fileZip = makeProofZip(proofDir.absoluteFile, context)

    Log.e("ZIP PATH", "zip path: $fileZip")

    return fileZip

  }

  private fun makeProofZip(proofDirPath: File, context: Context): File {
    val outputZipFile = File(proofDirPath.path, proofDirPath.name + ".zip")
    var photoName = "placeholder.jpg"
    ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
      proofDirPath.walkTopDown().forEach { file ->
        if (file.name.endsWith(".json")) {
          saveJson(file, context)
          photoName = getPhotoName(file).substringAfterLast("/")
        }
        val zipFileName = file.absolutePath.removePrefix(proofDirPath.absolutePath).removePrefix("/")
        val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")
        zos.putNextEntry(entry)
        if (file.isFile) {
          file.inputStream().copyTo(zos)
        }
      }

      val keyEntry = ZipEntry("pubkey.asc")
      zos.putNextEntry(keyEntry)
      val publicKey = ProofMode.getPublicKeyString(context)
      zos.write(publicKey.orEmpty().toByteArray())

      val photoEntry = ZipEntry(photoName)
      zos.putNextEntry(photoEntry)
      zos.write(photoByteArray)

      photoByteArray = byteArrayOf()

      return outputZipFile
    }
  }

  private fun getPhotoName(file: File): String {
    val bufferedReader: BufferedReader = file.bufferedReader()
    val inputString = bufferedReader.use { it.readText() }
    val json = JSONObject(inputString)
    return json.getString("File Path")
  }

  private fun saveJson(file: File, context: Context) {
    val notaryGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_GLOBAL, true)
    val locationGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_GLOBAL, true)
    val networkAndPhoneGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL, true)
    val notaryLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NOTARY_ENABLED_LOCAL, true)
    val locationLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_LOCATION_ENABLED_LOCAL, true)
    val networkAndPhoneLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL, true)
    val resultNotary = if (notaryGlobal == notaryLocal) notaryGlobal else notaryLocal
    val resultLocation = if (locationGlobal == locationLocal) locationGlobal else locationLocal
    val resultNetworkAndPhone = if (networkAndPhoneGlobal == networkAndPhoneLocal) networkAndPhoneGlobal else networkAndPhoneLocal

    val proofs = arrayListOf<Proofs>()
    if (resultNotary) proofs.add(Proofs.NOTARIES)
    if (resultLocation) proofs.add(Proofs.LOCATION)
    if (resultNetworkAndPhone) proofs.add(Proofs.NETWORK_AND_PHONE)

    Log.e("FILE::", "$file")
    val bufferedReader: BufferedReader = file.bufferedReader()
    val inputString = bufferedReader.use { it.readText() }
    val json = JSONObject(inputString)
    val proofJson = ProofJson(
      longitude = json.getString("Location.Longitude"),
      latitude = json.getString("Location.Latitude"),
      time = json.getString("Proof Generated"),
      location = json.getString("Location.Bearing"),
      deviceName = json.getString("Hardware"),
      networkType = json.getString("NetworkType"),
      proofsList = proofs
    )
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PROOF_OBJECT, proofJson.toJsonObject().toString()).apply()
  }

}

fun parseProofObjectFromString(proof: String): ProofMessage {
  return try {
    val takenText = proof.substringAfter("Taken:").substringBefore("\n").trim()
    val nearText = proof.substringAfter("Near:").substringBefore("\n").trim()
    val proofsList = proof.substringAfter("Proofs:").substringBefore("\n").trim()
    val networkType = proof.substringAfter("Network Type:").substringBefore("\n").trim()
    val deviceName = proof.substringAfter("Device Name:").substringBefore("\n").trim()
    ProofMessage(
      taken = takenText,
      near = nearText,
      proofs = proofsList,
      deviceName = deviceName,
      networkType = networkType
    )
  } catch (ex: Exception) {
    ProofMessage()
  }
}