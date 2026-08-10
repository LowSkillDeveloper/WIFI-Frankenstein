package com.lsd.wififrankenstein.ui.internetblocking

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import com.lsd.wififrankenstein.databinding.FragmentInternetBlockingBinding

class InternetBlockingFragment : Fragment() {
    private var _binding: FragmentInternetBlockingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInternetBlockingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = ViewPagerAdapter(childFragmentManager)
        binding.viewPager.adapter = adapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ViewPagerAdapter(fm: androidx.fragment.app.FragmentManager) :
        FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                0 -> MainTabFragment()
                1 -> DnsTabFragment()
                2 -> DomainTabFragment()
                3 -> TcpTabFragment()
                4 -> SniTabFragment()
                5 -> TelegramTabFragment()
                6 -> YouTubeTabFragment()
                else -> throw IllegalArgumentException("Unknown tab $position")
            }
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return when (position) {
                0 -> "Main"
                1 -> "DNS"
                2 -> "Domains"
                3 -> "TCP"
                4 -> "SNI"
                5 -> "Telegram"
                6 -> "YouTube"
                else -> throw IllegalArgumentException("Unknown tab $position")
            }
        }

        override fun getCount(): Int = 7
    }
}
