package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWelcomeBiometricsBinding
import com.lsd.wififrankenstein.util.AppLockManager

class WelcomeBiometricsFragment : Fragment() {

    private var _binding: FragmentWelcomeBiometricsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBiometricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val available = AppLockManager.isAvailable(requireContext())

        if (!available) {
            binding.switchAppLockWelcome.isEnabled = false
            binding.switchAppLockWelcome.isChecked = false
            binding.textViewBiometricsDescription.setText(R.string.app_lock_unavailable)
            return
        }

        binding.switchAppLockWelcome.isChecked = AppLockManager.isEnabled(requireContext())

        binding.switchAppLockWelcome.setOnCheckedChangeListener { _, isChecked ->
            AppLockManager.setEnabled(requireContext(), isChecked)
            if (isChecked) {
                Toast.makeText(
                    requireContext(),
                    R.string.app_lock_enabled_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeBiometricsFragment()
    }
}
