package com.lsd.wififrankenstein.ui.about

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.lsd.wififrankenstein.util.SignatureVerifier
import java.util.Locale

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
        setupModificationWarning()

        return root
    }

    private fun setupModificationWarning() {
        if (!SignatureVerifier.isOfficialBuild(requireContext())) {
            binding.modificationWarning.visibility = View.VISIBLE
            binding.modificationWarning.text = getWarningText()
            binding.modificationWarning.setTextColor(Color.RED)
            binding.modificationWarning.setOnClickListener {
                showModificationDialog()
            }
        } else {
            binding.modificationWarning.visibility = View.GONE
        }
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

        binding.licenseButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, getString(R.string.license_url).toUri())
            startActivity(intent)
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

    private fun getWarningText(): String {
        val isRussian = isRussianLocale()
        val encoded = if (isRussian) {
            "0J3QtdC+0YTQuNGG0LjQsNC70YzQvdCw0Y8g0LzQvtC00LjRhNC40YbQuNGA0L7QstCw0L3QvdCw0Y8g0LLQtdGA0YHQuNGP"
        } else {
            "VW5vZmZpY2lhbCBtb2RpZmllZCB2ZXJzaW9u"
        }
        return decodeBase64(encoded)
    }

    private fun showModificationDialog() {
        val isRussian = isRussianLocale()

        val titleEncoded = if (isRussian) {
            "0J/RgNC10LTRg9C/0YDQtdC20LTQtdC90LjQtSDQviDQvNC+0LTQuNGE0LjQutCw0YbQuNC4INC/0YDQuNC70L7QttC10L3QuNGP"
        } else {
            "TW9kaWZpZWQgQXBwbGljYXRpb24gV2FybmluZw=="
        }

        val messageEncoded = if (isRussian) {
            "0K3RgtC+INC/0YDQuNC70L7QttC10L3QuNC1INCx0YvQu9C+INC80L7QtNC40YTQuNGG0LjRgNC+0LLQsNC90L4g0Lgg0LzQvtC20LXRgiDRgdC+0LTQtdGA0LbQsNGC0Ywg0L3QtdCw0LLRgtC+0YDQuNC30L7QstCw0L3QvdGL0LUg0LjQt9C80LXQvdC10L3QuNGPLiDQmNGB0L/QvtC70YzQt9GD0LnRgtC1INC90LAg0YHQstC+0Lkg0YHRgtGA0LDRhSDQuCDRgNC40YHQui4="
        } else {
            "VGhpcyBhcHBsaWNhdGlvbiBoYXMgYmVlbiBtb2RpZmllZCBhbmQgbWF5IGNvbnRhaW4gdW5hdXRob3JpemVkIGNoYW5nZXMuIFVzZSBhdCB5b3VyIG93biByaXNrLg=="
        }

        val title = decodeBase64(titleEncoded)
        val message = decodeBase64(messageEncoded)
        val okText = if (isRussian) "OK" else "OK"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okText) { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun decodeBase64(encoded: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } else {
                val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                String(decoded, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Modified version"
        }
    }

    private fun isRussianLocale(): Boolean {
        val locale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
        return locale.language == "ru"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}