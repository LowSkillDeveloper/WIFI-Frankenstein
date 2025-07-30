package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeRootBinding
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel

class WelcomeRootFragment : Fragment() {

    private var _binding: FragmentWelcomeRootBinding? = null
    private val binding get() = _binding!!
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by activityViewModels()

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

        binding.radioGroupRootAccess.setOnCheckedChangeListener { _, checkedId ->
            val hasRoot = checkedId == binding.radioButtonHasRoot.id
            welcomeViewModel.setRootEnabled(hasRoot)
            settingsViewModel.setEnableRoot(hasRoot)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeRootFragment()
    }
}