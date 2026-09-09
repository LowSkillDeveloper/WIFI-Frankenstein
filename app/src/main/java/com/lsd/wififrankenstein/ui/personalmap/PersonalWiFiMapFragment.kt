package com.lsd.wififrankenstein.ui.personalmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentPersonalWifiMapBinding

class PersonalWiFiMapFragment : Fragment() {

    private var _binding: FragmentPersonalWifiMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PersonalWiFiMapViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalWifiMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInitialState()
        setupObservers()
        setupListeners()
    }

    private fun setupInitialState() {
        // Устанавливаем интервал
        val buttonId = when (viewModel.scanInterval) {
            5000L -> R.id.btn_interval_5s
            15000L -> R.id.btn_interval_15s
            30000L -> R.id.btn_interval_30s
            else -> R.id.btn_interval_5s
        }
        binding.toggleGroupInterval.check(buttonId)
    }

    private fun setupObservers() {
        viewModel.isScanning.observe(viewLifecycleOwner) { isScanning ->
            binding.btnStart.isEnabled = !isScanning
            binding.btnStop.isEnabled = isScanning
            binding.toggleGroupInterval.isEnabled = !isScanning
            
            for (i in 0 until binding.toggleGroupInterval.childCount) {
                binding.toggleGroupInterval.getChildAt(i).isEnabled = !isScanning
            }
            
            binding.textStatus.text = if (isScanning) {
                getString(R.string.scanning_active)
            } else {
                getString(R.string.scanning_idle)
            }
        }

        viewModel.networksCount.observe(viewLifecycleOwner) { count ->
            binding.textNetworksCount.text = getString(R.string.networks_found_count, count)
        }

        viewModel.gpsHealth.observe(viewLifecycleOwner) { health ->
            val healthText = when (health) {
                "EXCELLENT" -> getString(R.string.gps_health_excellent)
                "GOOD" -> getString(R.string.gps_health_good)
                "UNRELIABLE" -> getString(R.string.gps_health_unreliable)
                else -> getString(R.string.gps_health_bad)
            }
            binding.textGpsHealth.text = getString(R.string.gps_health, healthText)
        }
    }

    private fun setupListeners() {
        binding.toggleGroupInterval.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val interval = when (checkedId) {
                    R.id.btn_interval_5s -> 5000L
                    R.id.btn_interval_15s -> 15000L
                    R.id.btn_interval_30s -> 30000L
                    else -> 5000L
                }
                viewModel.scanInterval = interval
            }
        }

        binding.btnStart.setOnClickListener {
            viewModel.startScanning(viewModel.scanInterval, true)
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopScanning()
        }

        binding.btnShowMap.setOnClickListener {
             val bundle = Bundle().apply {
                 putBoolean("select_personal_map", true)
             }
             findNavController().navigate(R.id.nav_wifi_map, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
