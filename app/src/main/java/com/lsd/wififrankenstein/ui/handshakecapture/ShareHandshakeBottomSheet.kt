package com.lsd.wififrankenstein.ui.handshakecapture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.lsd.wififrankenstein.databinding.BottomSheetShareHandshakeBinding

class ShareHandshakeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetShareHandshakeBinding? = null
    private val binding get() = _binding!!

    private var hasChroot: Boolean = false
    private var fileExists: Boolean = true
    private var hasHash22000: Boolean = false
    private var hasHashPmkid: Boolean = false
    private var hasHash16800: Boolean = false
    private var onShareCap: (() -> Unit)? = null
    private var onShareHccapx: (() -> Unit)? = null
    private var onShare22000: (() -> Unit)? = null
    private var onSharePmkid: (() -> Unit)? = null
    private var onShareHccap: (() -> Unit)? = null
    private var onShare16800: (() -> Unit)? = null
    private var onShareCapChroot: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetShareHandshakeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnShareCap.setOnClickListener { dismiss(); onShareCap?.invoke() }
        binding.btnShareHccapx.setOnClickListener { dismiss(); onShareHccapx?.invoke() }
        binding.btnShare22000.setOnClickListener { dismiss(); onShare22000?.invoke() }
        binding.btnSharePmkid.setOnClickListener { dismiss(); onSharePmkid?.invoke() }
        binding.btnShareHccap.setOnClickListener { dismiss(); onShareHccap?.invoke() }
        binding.btnShare16800.setOnClickListener { dismiss(); onShare16800?.invoke() }
        binding.btnShareCapChroot.apply {
            isEnabled = hasChroot && fileExists
            alpha = if (hasChroot && fileExists) 1f else 0.4f
            setOnClickListener {
                if (hasChroot && fileExists) {
                    dismiss(); onShareCapChroot?.invoke()
                }
            }
        }

        setAvailability(binding.btnShareCap, fileExists)
        setAvailability(binding.btnShareHccapx, hasHash22000)
        setAvailability(binding.btnShare22000, hasHash22000)
        setAvailability(binding.btnSharePmkid, hasHashPmkid)
        setAvailability(binding.btnShareHccap, hasHash22000)
        setAvailability(binding.btnShare16800, hasHash16800)
    }

    private fun setAvailability(button: MaterialButton, available: Boolean) {
        button.isEnabled = available
        button.alpha = if (available) 1f else 0.4f
    }

    override fun onDestroyView() {
        arguments?.getString(KEY_CACHE_ID)?.let { clearCache(it) }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_CACHE_ID = "cache_id"
        private val callbackCache = mutableMapOf<String, ShareCallbacks>()

        data class ShareCallbacks(
            val onShareCap: () -> Unit,
            val onShareHccapx: () -> Unit,
            val onShare22000: () -> Unit,
            val onSharePmkid: () -> Unit,
            val onShareHccap: () -> Unit,
            val onShare16800: () -> Unit,
            val onShareCapChroot: () -> Unit
        )

        fun newInstance(
            item: HandshakeItem,
            hasChroot: Boolean,
            fileExists: Boolean = item.fileExists,
            hasHash22000: Boolean = item.hash22000 != null,
            hasHashPmkid: Boolean = item.hashPmkid != null,
            hasHash16800: Boolean = item.hash16800 != null,
            onShareCap: () -> Unit,
            onShareHccapx: () -> Unit,
            onShare22000: () -> Unit,
            onSharePmkid: () -> Unit,
            onShareHccap: () -> Unit,
            onShare16800: () -> Unit,
            onShareCapChroot: () -> Unit
        ): ShareHandshakeBottomSheet {
            val cacheId = java.util.UUID.randomUUID().toString()
            callbackCache[cacheId] = ShareCallbacks(
                onShareCap, onShareHccapx, onShare22000, onSharePmkid,
                onShareHccap, onShare16800, onShareCapChroot
            )
            return ShareHandshakeBottomSheet().apply {
                arguments = Bundle().apply { putString(KEY_CACHE_ID, cacheId) }
                this.hasChroot = hasChroot
                this.fileExists = fileExists
                this.hasHash22000 = hasHash22000
                this.hasHashPmkid = hasHashPmkid
                this.hasHash16800 = hasHash16800
                this.onShareCap = onShareCap
                this.onShareHccapx = onShareHccapx
                this.onShare22000 = onShare22000
                this.onSharePmkid = onSharePmkid
                this.onShareHccap = onShareHccap
                this.onShare16800 = onShare16800
                this.onShareCapChroot = onShareCapChroot
            }
        }

        fun clearCache(cacheId: String) {
            callbackCache.remove(cacheId)
        }
    }
}
