package com.lsd.wififrankenstein.ui.internetblocking

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.FragmentTabDnsBinding
import com.lsd.wififrankenstein.ui.internetblocking.adapter.DnsResultAdapter

class DnsTabFragment : Fragment() {
    private var _binding: FragmentTabDnsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()
    private lateinit var adapter: DnsResultAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabDnsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DnsResultAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.buttonCheckDns.setOnClickListener {
            viewModel.checkDns()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckDns.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
            binding.progressText.visibility = if (checking) View.VISIBLE else View.GONE
        }

        viewModel.progress.observe(viewLifecycleOwner) { percent ->
            binding.progressBar.progress = percent
        }

        viewModel.progressText.observe(viewLifecycleOwner) { text ->
            binding.progressText.text = text
        }

        viewModel.dnsResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
