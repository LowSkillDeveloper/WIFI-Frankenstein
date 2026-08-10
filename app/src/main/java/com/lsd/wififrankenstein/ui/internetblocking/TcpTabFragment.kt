package com.lsd.wififrankenstein.ui.internetblocking

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.FragmentTabTcpBinding
import com.lsd.wififrankenstein.ui.internetblocking.adapter.TcpResultAdapter

class TcpTabFragment : Fragment() {
    private var _binding: FragmentTabTcpBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()
    private lateinit var tcp16Adapter: TcpResultAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabTcpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tcp16Adapter = TcpResultAdapter()

        binding.recyclerViewTcp16.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewTcp16.adapter = tcp16Adapter

        binding.buttonCheckTcp16.setOnClickListener {
            viewModel.checkTcp16()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckTcp16.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
        }

        viewModel.tcpResults.observe(viewLifecycleOwner) { results ->
            tcp16Adapter.submitList(results)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
