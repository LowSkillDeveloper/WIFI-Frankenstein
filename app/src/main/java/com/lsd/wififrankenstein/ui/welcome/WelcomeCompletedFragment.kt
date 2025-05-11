package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeCompletedBinding

class  WelcomeCompletedFragment : Fragment() {

    private var _binding: FragmentWelcomeCompletedBinding? = null
    private val binding get() = _binding!!
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeCompletedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonFinish.setOnClickListener {
            welcomeViewModel.setFirstLaunch(false)
            (activity as? WelcomeActivity)?.completeOnboarding()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeCompletedFragment()
    }
}