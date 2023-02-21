package org.thoughtcrime.securesms.mediasend.v2.capture

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.databinding.ProofModePreviewBinding
import org.thoughtcrime.securesms.mediasend.ProofConstants
import org.thoughtcrime.securesms.mediasend.proofmode.ProofModeUtil
import org.thoughtcrime.securesms.mediasend.setOnClickListenerWithThrottle

class ProofPreviewFragment : Fragment(R.layout.proof_mode_preview) {

  private lateinit var binding: ProofModePreviewBinding

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    binding = ProofModePreviewBinding.inflate(inflater, container, false)

    setupView()

    return binding.root
  }

  override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
    val inflater = super.onGetLayoutInflater(savedInstanceState)
    val contextThemeWrapper: Context = ContextThemeWrapper(requireContext(), R.style.Signal_DayNight_NoActionBar)
    return inflater.cloneInContext(contextThemeWrapper)
  }

  private fun setupView() {
    binding.cancelButton.setOnClickListener {
      findNavController().navigateUp()
    }

    val notaryGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_NOTARY_ENABLED_GLOBAL, true)
    val locationGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_LOCATION_ENABLED_GLOBAL, true)
    val networkGlobal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_GLOBAL, true)

    val isNotaryExist = PreferenceManager.getDefaultSharedPreferences(context).contains(ProofConstants.IS_PROOF_NOTARY_ENABLED_LOCAL)
    val isLocationExist = PreferenceManager.getDefaultSharedPreferences(context).contains(ProofConstants.IS_PROOF_LOCATION_ENABLED_LOCAL)
    val isNetworkAndPhoneExist = PreferenceManager.getDefaultSharedPreferences(context).contains(ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL)

    val notaryLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_NOTARY_ENABLED_LOCAL, true)
    val locationLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_LOCATION_ENABLED_LOCAL, true)
    val networkLocal = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_NETWORK_AND_PHONE_ENABLED_LOCAL, true)

    val resultNotary = if (isNotaryExist) notaryLocal else notaryGlobal
    val resultLocation = if (isLocationExist) locationLocal else locationGlobal
    val resultNetworkAndPhone = if (isNetworkAndPhoneExist) networkLocal else networkGlobal

    binding.checkButtonBasic.isChecked = resultLocation
    binding.checkButtonExtended.isChecked = resultNetworkAndPhone
    binding.checkButtonThird.isChecked = resultNotary

    binding.basicLayout.setOnClickListener {
      binding.checkButtonBasic.performClick()
    }
    binding.extendedLayout.setOnClickListener {
      binding.checkButtonExtended.performClick()
    }
    binding.thirdLayout.setOnClickListener {
      binding.checkButtonThird.performClick()
    }

    binding.confirmButton.setOnClickListenerWithThrottle {
      ProofModeUtil.setProofSettingsLocal(
        context = requireContext(),
        proofNotary = binding.checkButtonThird.isChecked,
        proofLocation = binding.checkButtonBasic.isChecked,
        proofNetwork = binding.checkButtonExtended.isChecked
      )
      findNavController().navigateUp()
    }
  }
}