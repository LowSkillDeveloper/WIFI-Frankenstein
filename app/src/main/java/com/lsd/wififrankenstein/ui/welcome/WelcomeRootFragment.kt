package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeRootBinding
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class WelcomeRootFragment : Fragment() {

    private var _binding: FragmentWelcomeRootBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WelcomeViewModel by activityViewModels()
    private var rootCheckCompleted = false

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

        setupRadioButtons()
        updateNavigationButtons()
    }

    private fun setupRadioButtons() {
        binding.radioGroupRootAccess.setOnCheckedChangeListener { _, checkedId ->
            rootCheckCompleted = false
            binding.textViewRootStatus.visibility = View.GONE
            binding.buttonNext.visibility = View.GONE

            when (checkedId) {
                R.id.radioButtonHasRoot -> {
                    checkRootAccess()
                }
                R.id.radioButtonNoRoot -> {
                    showNoRootMessage()
                    rootCheckCompleted = true
                    binding.buttonNext.visibility = View.VISIBLE
                }
                R.id.radioButtonWantToSeeRoot -> {
                    showWantToSeeRootMessage()
                    rootCheckCompleted = true
                    binding.buttonNext.visibility = View.VISIBLE
                }
            }
            updateNavigationButtons()
        }

        binding.buttonNext.setOnClickListener {
            when (binding.radioGroupRootAccess.checkedRadioButtonId) {
                R.id.radioButtonHasRoot -> {
                    if (Shell.isAppGrantedRoot() == true) {
                        (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.navigateToChrootInstall()
                    } else {
                        (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.navigateToNextFragment()
                    }
                }
                else -> {
                    (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.navigateToNextFragment()
                }
            }
        }
    }

    private fun checkRootAccess() {
        binding.textViewRootStatus.text = getString(R.string.checking_root_access)
        binding.textViewRootStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val testResult = Shell.cmd("id").exec()
                val hasRoot = testResult.isSuccess && testResult.out.any { it.contains("uid=0") }

                activity?.runOnUiThread {
                    if (hasRoot) {
                        showRootGrantedMessage()
                    } else {
                        showRootDeniedMessage()
                    }
                    rootCheckCompleted = true
                    binding.buttonNext.visibility = View.VISIBLE
                    updateNavigationButtons()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    showRootDeniedMessage()
                    rootCheckCompleted = true
                    binding.buttonNext.visibility = View.VISIBLE
                    updateNavigationButtons()
                }
            }
        }
    }

    private fun showRootGrantedMessage() {
        binding.textViewRootStatus.text = getString(R.string.root_access_granted)
        binding.textViewRootStatus.setTextColor(resources.getColor(R.color.success_green, null))
        viewModel.setRootEnabled(true)
    }

    private fun showRootDeniedMessage() {
        binding.textViewRootStatus.text = getString(R.string.root_access_denied)
        binding.textViewRootStatus.setTextColor(resources.getColor(R.color.error_red, null))
        viewModel.setRootEnabled(false)
    }

    private fun showNoRootMessage() {
        binding.textViewRootStatus.text = getString(R.string.root_functions_hidden)
        binding.textViewRootStatus.visibility = View.VISIBLE
        binding.textViewRootStatus.setTextColor(resources.getColor(R.color.text_primary, null))
        viewModel.setRootEnabled(false)
    }

    private fun showWantToSeeRootMessage() {
        binding.textViewRootStatus.text = getString(R.string.root_functions_visible_but_disabled)
        binding.textViewRootStatus.visibility = View.VISIBLE
        binding.textViewRootStatus.setTextColor(resources.getColor(R.color.orange_500, null))
        viewModel.setRootEnabled(true)
    }

    private fun updateNavigationButtons() {
        (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.updateNavigationButtons(
            showPrev = true,
            showSkip = false,
            showNext = rootCheckCompleted,
            nextText = getString(R.string.next)
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