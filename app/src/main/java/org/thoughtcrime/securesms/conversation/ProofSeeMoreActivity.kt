package org.thoughtcrime.securesms.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.protobuf.ByteString
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.databinding.ProofModeSeeMoreBinding
import org.thoughtcrime.securesms.mediasend.proofmode.MobileCoinNotaryUtil
import org.thoughtcrime.securesms.mediasend.proofmode.MobileCoinProofUtil
import org.thoughtcrime.securesms.mediasend.proofmode.ProofMessage
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentViewModel


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

      var notaryTx = it.getNotaryTxText()

      val proofHash = it.hash.substringBefore(".zip")

      if (notaryTx.isEmpty())
      {
        notaryTx = MobileCoinProofUtil.getTxFromHash(proofHash)
      }

      binding.notaryText.text = getString(R.string.noteries_text) + ": ${notaryTx}";


      binding.notaryText.setOnClickListener {
        copyToClipboard("prooftx", notaryTx)
      }


      binding.ciCdText.text = it.getShortHash(formatHashString(proofHash))

      binding.ciCdText.setOnClickListener {
        copyToClipboard("proofhash", proofHash)
      }

    }
  }

  private fun copyToClipboard (label: String, value: String) {
    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(this, "Copied $label", Toast.LENGTH_SHORT).show()
  }

  private fun formatHashString(s: String): String {
    return if (s.isNotEmpty() && s.length > 12) {
      val firstSix = s.take(6)
      val lastSix = s.takeLast(6)
      "$firstSix...$lastSix"
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
