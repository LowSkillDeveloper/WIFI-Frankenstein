package com.lsd.wififrankenstein.ui.internetblocking

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.FragmentTabDomainBinding
import com.lsd.wififrankenstein.ui.internetblocking.adapter.DomainResultAdapter

class DomainTabFragment : Fragment() {
    private var _binding: FragmentTabDomainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()
    private lateinit var adapter: DomainResultAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabDomainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DomainResultAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.buttonCheckDomains.setOnClickListener {
            viewModel.checkDomains()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckDomains.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
        }

        viewModel.domainResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
