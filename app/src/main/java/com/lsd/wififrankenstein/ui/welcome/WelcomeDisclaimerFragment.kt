package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.databinding.FragmentWelcomeDisclaimerBinding
import com.lsd.wififrankenstein.util.EulaManager

class WelcomeDisclaimerFragment : Fragment() {

    private var _binding: FragmentWelcomeDisclaimerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeDisclaimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? WelcomeActivity)?.setBottomHint(null)
        (activity as? WelcomeActivity)?.updateNavigationButtons(false, false)

        binding.checkBoxEulaAccept.setOnCheckedChangeListener { _, _ ->
            updateNextButtonState()
        }

        binding.buttonNext.setOnClickListener {
            EulaManager.markAccepted(requireContext())
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }
        updateNextButtonState()
        setupAnimations()
    }

    private fun updateNextButtonState() {
        val enabled = binding.checkBoxEulaAccept.isChecked
        binding.buttonNext.isEnabled = enabled
        binding.buttonNext.alpha = if (enabled) 1f else 0.5f
    }

    private fun setupAnimations() {
        val animatedViews = listOf(
            binding.imageViewHeroIcon to 0L,
            binding.textViewWelcomeTitle to 150L,
            binding.textViewSubtitle to 300L,
            binding.cardViewDisclaimer to 450L,
            binding.textViewBottomHint to 600L,
            binding.buttonNext to 750L
        )

        animatedViews.forEach { (view, delay) ->
            val targetAlpha =
                if (view === binding.buttonNext && !binding.buttonNext.isEnabled) 0.5f else 1f
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(targetAlpha)
                .translationY(0f)
                .setDuration(600)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeDisclaimerFragment()
    }
}
