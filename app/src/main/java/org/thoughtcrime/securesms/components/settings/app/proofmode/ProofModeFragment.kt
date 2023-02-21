package org.thoughtcrime.securesms.components.settings.app.proofmode

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import org.bouncycastle.openpgp.PGPException
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.databinding.ProofModeFragmentBinding
import org.thoughtcrime.securesms.mediasend.ProofConstants
import org.thoughtcrime.securesms.mediasend.proofmode.ProofModeUtil
import org.thoughtcrime.securesms.mediasend.setOnClickListenerWithThrottle
import org.witness.proofmode.crypto.pgp.PgpUtils
import java.io.IOException
import java.util.concurrent.Executors

class ProofModeFragment : Fragment(R.layout.proof_mode_fragment) {
  private lateinit var binding: ProofModeFragmentBinding

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    binding = ProofModeFragmentBinding.inflate(inflater, container, false)

    setupView()
    checkAndGeneratePublicKey()
    return binding.root
  }

  private fun setupView() {
    binding.notarySwitch.isChecked = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(ProofConstants.IS_PROOF_NOTARY_ENABLED_GLOBAL, true)
    binding.locationSwitch.isChecked = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(ProofConstants.IS_PROOF_LOCATION_ENABLED_GLOBAL, true)
    binding.networkSwitch.isChecked = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL, true)
    binding.toolbar.setNavigationOnClickListener {
      findNavController().navigateUp()
    }
    binding.offLayout.setOnClickListenerWithThrottle {
      ProofModeUtil.setProofSettingsGlobal(
        context = requireContext(),
        proofLocation = false,
        proofNetwork = false,
        proofNotary = false
      )
      binding.notarySwitch.isChecked = false
      binding.locationSwitch.isChecked = false
      binding.networkSwitch.isChecked = false
    }
    binding.notaryLayout.setOnClickListenerWithThrottle {
      binding.notarySwitch.performClick()
    }
    binding.locationLayout.setOnClickListenerWithThrottle {
      binding.locationSwitch.performClick()
    }
    binding.networkLayout.setOnClickListenerWithThrottle {
      binding.networkSwitch.performClick()
    }

    binding.notarySwitch.setOnCheckedChangeListener { _, isChecked ->
      ProofModeUtil.setProofSettingsGlobal(
        context = requireContext(),
        proofNotary = isChecked
      )
    }
    binding.locationSwitch.setOnCheckedChangeListener { _, isChecked ->
      ProofModeUtil.setProofSettingsGlobal(
        context = requireContext(),
        proofLocation = isChecked
      )
    }
    binding.networkSwitch.setOnCheckedChangeListener { _, isChecked ->
      ProofModeUtil.setProofSettingsGlobal(
        context = requireContext(),
        proofNetwork = isChecked
      )
    }
  }

  val TAG = "ProofMode"

  fun checkAndGeneratePublicKey() {
    Executors.newSingleThreadExecutor().execute {

      //Background work here
      var pubKey: String? = null
      try {
        pubKey = PgpUtils.getInstance(context).publicKeyFingerprint
        showToastMessage("public key generated: $pubKey")
      } catch (e: PGPException) {
        org.signal.core.util.logging.Log.e(TAG,"error getting public key",e)
        showToastMessage("error generating public key: $e.message")
      } catch (e: IOException) {
        org.signal.core.util.logging.Log.e(TAG,"error getting public key",e)
        showToastMessage("error generating public key: $e.message")
      }
    }
  }

  private fun showToastMessage(message: String) {
    val handler = Handler(Looper.getMainLooper())
    handler.post {
      //UI Thread work here
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }

}