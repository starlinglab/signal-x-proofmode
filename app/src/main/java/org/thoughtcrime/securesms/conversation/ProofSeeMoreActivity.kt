package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import org.thoughtcrime.securesms.databinding.ProofModeSeeMoreBinding
import org.thoughtcrime.securesms.mediasend.ProofMessage

class ProofSeeMoreActivity : AppCompatActivity() {

  private lateinit var binding: ProofModeSeeMoreBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ProofModeSeeMoreBinding.inflate(layoutInflater)
    setContentView(binding.root)

    val proofMessage = intent.parcelable<ProofMessage>(PROOF_MESSAGE)

    title = "Proof Details"

    proofMessage?.let {
      binding.takenText.text = it.getTakenText()
      binding.phoneText.text = it.getDeviceNameText()
      binding.locationText.text = it.getNearText()
      binding.networkText.text = it.getNetworkTypeText()
      binding.ciCdText.text = it.getShortHash(formatHashString(it.hash.substringBefore(".zip")))
    }
  }

  private fun formatHashString(s: String): String {
    return if (s.isNotEmpty() && s.length > 12) {
      val firstSix = s.take(6)
      val lastSix = s.takeLast(6)
      "$firstSix ... $lastSix"
    } else {
      s
    }
  }

  companion object {
    const val PROOF_MESSAGE = "proof_message"

    @JvmStatic
    fun createIntent(
      context: Context,
      proofMessage: ProofMessage
    ): Intent {
      return Intent(context, ProofSeeMoreActivity::class.java).apply {
        putExtra(PROOF_MESSAGE, proofMessage)
      }
    }
  }
}

inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
  SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
  else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
}

inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = when {
  SDK_INT >= 33 -> getParcelable(key, T::class.java)
  else -> @Suppress("DEPRECATION") getParcelable(key) as? T
}
