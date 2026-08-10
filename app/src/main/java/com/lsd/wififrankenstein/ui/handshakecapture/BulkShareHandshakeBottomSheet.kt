package com.lsd.wififrankenstein.ui.handshakecapture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetShareBulkBinding

class BulkShareHandshakeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetShareBulkBinding? = null
    private val binding get() = _binding!!

    private var hasChroot: Boolean = false
    private var anyFileExists: Boolean = false
    private var anyHash22000: Boolean = false
    private var anyHashPmkid: Boolean = false
    private var anyHash16800: Boolean = false
    private var onShareOriginal: (() -> Unit)? = null
    private var onShareOriginalZip: (() -> Unit)? = null
    private var onShare22000: (() -> Unit)? = null
    private var onShareHccapx: (() -> Unit)? = null
    private var onSharePmkid: (() -> Unit)? = null
    private var onShareHccap: (() -> Unit)? = null
    private var onShare16800: (() -> Unit)? = null
    private var onShareCapChroot: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetShareBulkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val count = arguments?.getInt(KEY_COUNT) ?: 0
        binding.txtBulkShareTitle.text =
            getString(R.string.handshake_bulk_share_title_count, count)
        binding.btnBulkShareOriginal.text =
            getString(R.string.handshake_bulk_share_original, count)
        binding.btnBulkShareOriginal.setOnClickListener { dismiss(); onShareOriginal?.invoke() }
        binding.btnBulkShareOriginalZip.setOnClickListener { dismiss(); onShareOriginalZip?.invoke() }
        binding.btnBulkShare22000.setOnClickListener { dismiss(); onShare22000?.invoke() }
        binding.btnBulkShareHccapx.setOnClickListener { dismiss(); onShareHccapx?.invoke() }
        binding.btnBulkSharePmkid.setOnClickListener { dismiss(); onSharePmkid?.invoke() }
        binding.btnBulkShareHccap.setOnClickListener { dismiss(); onShareHccap?.invoke() }
        binding.btnBulkShare16800.setOnClickListener { dismiss(); onShare16800?.invoke() }
        binding.btnBulkShareCapChroot.apply {
            val capEnabled = hasChroot && anyFileExists
            isEnabled = capEnabled
            alpha = if (capEnabled) 1f else 0.4f
            setOnClickListener {
                if (capEnabled) {
                    dismiss(); onShareCapChroot?.invoke()
                }
            }
        }

        setAvailability(binding.btnBulkShareOriginal, anyFileExists)
        setAvailability(binding.btnBulkShareOriginalZip, anyFileExists)
        setAvailability(binding.btnBulkShare22000, anyHash22000)
        setAvailability(binding.btnBulkShareHccapx, anyHash22000)
        setAvailability(binding.btnBulkShareHccap, anyHash22000)
        setAvailability(binding.btnBulkSharePmkid, anyHashPmkid)
        setAvailability(binding.btnBulkShare16800, anyHash16800)
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
        private const val KEY_COUNT = "count"
        private val callbackCache = mutableMapOf<String, ShareCallbacks>()

        data class ShareCallbacks(
            val onShareOriginal: () -> Unit,
            val onShareOriginalZip: () -> Unit,
            val onShare22000: () -> Unit,
            val onShareHccapx: () -> Unit,
            val onSharePmkid: () -> Unit,
            val onShareHccap: () -> Unit,
            val onShare16800: () -> Unit,
            val onShareCapChroot: () -> Unit
        )

        fun newInstance(
            count: Int,
            hasChroot: Boolean,
            anyFileExists: Boolean,
            anyHash22000: Boolean,
            anyHashPmkid: Boolean,
            anyHash16800: Boolean,
            onShareOriginal: () -> Unit,
            onShareOriginalZip: () -> Unit,
            onShare22000: () -> Unit,
            onShareHccapx: () -> Unit,
            onSharePmkid: () -> Unit,
            onShareHccap: () -> Unit,
            onShare16800: () -> Unit,
            onShareCapChroot: () -> Unit
        ): BulkShareHandshakeBottomSheet {
            val cacheId = java.util.UUID.randomUUID().toString()
            callbackCache[cacheId] = ShareCallbacks(
                onShareOriginal, onShareOriginalZip, onShare22000, onShareHccapx,
                onSharePmkid, onShareHccap, onShare16800, onShareCapChroot
            )
            return BulkShareHandshakeBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(KEY_CACHE_ID, cacheId)
                    putInt(KEY_COUNT, count)
                }
                this.hasChroot = hasChroot
                this.anyFileExists = anyFileExists
                this.anyHash22000 = anyHash22000
                this.anyHashPmkid = anyHashPmkid
                this.anyHash16800 = anyHash16800
                this.onShareOriginal = onShareOriginal
                this.onShareOriginalZip = onShareOriginalZip
                this.onShare22000 = onShare22000
                this.onShareHccapx = onShareHccapx
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
