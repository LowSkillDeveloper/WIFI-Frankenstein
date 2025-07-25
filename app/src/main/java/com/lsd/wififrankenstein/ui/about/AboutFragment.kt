package com.lsd.wififrankenstein.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.tabs.TabLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private var currentCrypto = CryptoType.BITCOIN
    private var lastClickTime = 0L
    private val doubleClickDelay = 200L

    private enum class CryptoType {
        BITCOIN, ETHEREUM, MONERO
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val aboutViewModel = ViewModelProvider(this)[AboutViewModel::class.java]

        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textAbout
        aboutViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        setupCryptoTabs()
        setupClickListeners()
        updateCryptoInfo(CryptoType.BITCOIN)

        return root
    }

    private fun setupCryptoTabs() {
        binding.cryptoTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        currentCrypto = CryptoType.BITCOIN
                        updateCryptoInfo(CryptoType.BITCOIN)
                    }
                    1 -> {
                        currentCrypto = CryptoType.ETHEREUM
                        updateCryptoInfo(CryptoType.ETHEREUM)
                    }
                    2 -> {
                        currentCrypto = CryptoType.MONERO
                        updateCryptoInfo(CryptoType.MONERO)
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupClickListeners() {
        binding.githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                "https://github.com/LowSkillDeveloper/WiFi-Frankenstein".toUri())
            startActivity(intent)
        }

        binding.cryptoAddress.setOnClickListener {
            copyToClipboard()
        }

        binding.appLogo.setOnClickListener {
            handleLogoClick()
        }
    }

    private fun handleLogoClick() {
        val currentTime = System.currentTimeMillis()

        shakeAnimation()

        if (currentTime - lastClickTime < doubleClickDelay) {
            openRickRoll()
        }

        lastClickTime = currentTime
    }

    private fun shakeAnimation() {
        val shakeAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.shake)
        binding.appLogo.startAnimation(shakeAnimation)
    }

    private fun openRickRoll() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, getString(R.string.rickroll_url).toUri())
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.rickroll_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateCryptoInfo(cryptoType: CryptoType) {
        when (cryptoType) {
            CryptoType.BITCOIN -> {
                binding.qrCode.setImageResource(R.drawable.qr_btc_donation)
                binding.cryptoAddressLabel.text = getString(R.string.btc_address_label)
                binding.cryptoAddress.text = getString(R.string.btc_wallet_address)
                binding.qrCode.contentDescription = getString(R.string.qr_code_btc_description)
            }
            CryptoType.ETHEREUM -> {
                binding.qrCode.setImageResource(R.drawable.qr_eth_donation)
                binding.cryptoAddressLabel.text = getString(R.string.eth_address_label)
                binding.cryptoAddress.text = getString(R.string.eth_wallet_address)
                binding.qrCode.contentDescription = getString(R.string.qr_code_eth_description)
            }
            CryptoType.MONERO -> {
                binding.qrCode.setImageResource(R.drawable.qr_xmr_donation)
                binding.cryptoAddressLabel.text = getString(R.string.xmr_address_label)
                binding.cryptoAddress.text = getString(R.string.xmr_wallet_address)
                binding.qrCode.contentDescription = getString(R.string.qr_code_xmr_description)
            }
        }
    }

    private fun copyToClipboard() {
        val address = when (currentCrypto) {
            CryptoType.BITCOIN -> getString(R.string.btc_wallet_address)
            CryptoType.ETHEREUM -> getString(R.string.eth_wallet_address)
            CryptoType.MONERO -> getString(R.string.xmr_wallet_address)
        }

        val label = when (currentCrypto) {
            CryptoType.BITCOIN -> getString(R.string.btc_address_label)
            CryptoType.ETHEREUM -> getString(R.string.eth_address_label)
            CryptoType.MONERO -> getString(R.string.xmr_address_label)
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, address)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            requireContext(),
            getString(R.string.address_copied),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}