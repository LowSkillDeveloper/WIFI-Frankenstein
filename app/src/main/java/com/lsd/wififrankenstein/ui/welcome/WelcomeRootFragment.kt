package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.RootSelection
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.WifiApplication
import com.lsd.wififrankenstein.databinding.FragmentWelcomeRootBinding
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class WelcomeRootFragment : Fragment() {

    private var _binding: FragmentWelcomeRootBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WelcomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeRootBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? WelcomeActivity)?.setBottomHint(null)
        setupRadioButtons()
        restoreRootState()
        updateNavigationButtons()
    }

    private fun restoreRootState() {
        val rootEnabled = viewModel.rootEnabled.value ?: false
        if (rootEnabled) {
            binding.radioButtonHasRoot.isChecked = true
            setStatus(getString(R.string.root_access_granted), R.color.success_green)
        }
    }

    private fun setupRadioButtons() {
        binding.radioGroupRootAccess.setOnCheckedChangeListener { _, _ ->
            binding.textViewRootStatus.visibility = View.GONE
            updateNavigationButtons()
        }
    }

    fun goNext() {
        val checkedId = binding.radioGroupRootAccess.checkedRadioButtonId
        when (checkedId) {
            R.id.radioButtonHasRoot -> {
                viewModel.setRootSelection(RootSelection.HAS_ROOT)
                binding.textViewRootStatus.visibility = View.VISIBLE
                setStatus(getString(R.string.checking_root_access), R.color.warning_orange)
                lifecycleScope.launch {
                    val hasRoot = checkRoot()
                    if (hasRoot) {
                        setStatus(getString(R.string.root_access_granted), R.color.success_green)
                        viewModel.setRootEnabled(true)
                        fixOwnership()
                        lockSelection()
                        (activity as? WelcomeActivity)?.navigateToChrootInstall()
                    } else {
                        showRootDeniedDialog()
                    }
                }
            }

            R.id.radioButtonDontKnow -> {
                viewModel.setRootSelection(RootSelection.DONT_KNOW)
                binding.textViewRootStatus.visibility = View.VISIBLE
                setStatus(getString(R.string.checking_root_access), R.color.warning_orange)
                lifecycleScope.launch {
                    val hasRoot = checkRoot()
                    if (hasRoot) {
                        setStatus(getString(R.string.root_detected), R.color.success_green)
                        viewModel.setRootEnabled(true)
                        fixOwnership()
                        (activity as? WelcomeActivity)?.navigateToChrootInstall()
                    } else {
                        setStatus(getString(R.string.root_not_detected), R.color.warning_orange)
                        viewModel.setRootEnabled(false)
                        navigateToNext()
                    }
                }
            }

            R.id.radioButtonNoRoot -> {
                viewModel.setRootSelection(RootSelection.NO_ROOT)
                setStatus(getString(R.string.root_features_disabled), R.color.text_secondary)
                viewModel.setRootEnabled(false)
                navigateToNext()
            }

            View.NO_ID -> {
                viewModel.setRootEnabled(false)
                navigateToNext()
            }
        }
    }

    private suspend fun checkRoot(): Boolean {
        try {
            val testResult = Shell.cmd("id").exec()
            return testResult.isSuccess && testResult.out.any { it.contains("uid=0") }
        } catch (e: Exception) {
            Log.e("WelcomeRoot", "Root check failed", e)
            return false
        }
    }

    private suspend fun fixOwnership() {
        try {
            (requireContext().applicationContext as? WifiApplication)?.fixDataDirOwnershipIfNeeded()
        } catch (e: Exception) {
            Log.e("WelcomeRoot", "Ownership fix failed", e)
        }
    }

    private fun showRootDeniedDialog() {
        setStatus(getString(R.string.root_access_denied), R.color.error_red)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.root_denied_title)
            .setMessage(R.string.root_denied_message)
            .setPositiveButton(R.string.retry) { _, _ ->
                setStatus(getString(R.string.checking_root_access), R.color.warning_orange)
                lifecycleScope.launch {
                    if (checkRoot()) {
                        setStatus(getString(R.string.root_access_granted), R.color.success_green)
                        viewModel.setRootEnabled(true)
                        lockSelection()
                        (activity as? WelcomeActivity)?.navigateToChrootInstall()
                    } else {
                        showRootDeniedDialog()
                    }
                }
            }
            .setNegativeButton(R.string.continue_without_root) { _, _ ->
                setStatus(getString(R.string.root_features_disabled), R.color.text_secondary)
                viewModel.setRootEnabled(false)
                navigateToNext()
            }
            .show()
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.textViewRootStatus.text = text
        binding.textViewRootStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        binding.textViewRootStatus.visibility = View.VISIBLE
    }

    private fun lockSelection() {
        binding.radioButtonHasRoot.isEnabled = false
        binding.radioButtonDontKnow.isEnabled = false
        binding.radioButtonNoRoot.isEnabled = false
    }

    private fun navigateToNext() {
        (activity as? WelcomeActivity)?.navigateToNextFragment()
    }

    private fun updateNavigationButtons() {
        val hasSelection = binding.radioGroupRootAccess.checkedRadioButtonId != View.NO_ID
        (activity as? WelcomeActivity)?.updateNavigationButtons(
            showPrev = true,
            showNext = hasSelection,
            nextText = if (hasSelection) getString(R.string.next) else getString(R.string.skip)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeRootFragment()
    }
}
