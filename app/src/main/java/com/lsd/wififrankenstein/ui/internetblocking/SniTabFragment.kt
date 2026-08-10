package com.lsd.wififrankenstein.ui.internetblocking

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.FragmentTabSniBinding
import com.lsd.wififrankenstein.ui.internetblocking.adapter.SweepResultAdapter

class SniTabFragment : Fragment() {
    private var _binding: FragmentTabSniBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()
    private lateinit var sweepAdapter: SweepResultAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabSniBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sweepAdapter = SweepResultAdapter()

        binding.recyclerViewSweep.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSweep.adapter = sweepAdapter


        val savedType = viewModel.getCurrentSniListType()
        val checkedId = when (savedType) {
            SniListType.BASE -> binding.sniButtonBase.id
            SniListType.RUSSIA -> binding.sniButtonRussia.id
            SniListType.UKRAINE -> binding.sniButtonUkraine.id
            SniListType.CHINA -> binding.sniButtonChina.id
            SniListType.BELARUS -> binding.sniButtonBelarus.id
        }
        binding.sniToggleGroup.check(checkedId)


        binding.sniToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                binding.sniButtonBase.id -> viewModel.setSniListType(SniListType.BASE)
                binding.sniButtonRussia.id -> viewModel.setSniListType(SniListType.RUSSIA)
                binding.sniButtonUkraine.id -> viewModel.setSniListType(SniListType.UKRAINE)
                binding.sniButtonChina.id -> viewModel.setSniListType(SniListType.CHINA)
                binding.sniButtonBelarus.id -> viewModel.setSniListType(SniListType.BELARUS)
            }
        }

        binding.buttonSniSweep.setOnClickListener {
            viewModel.checkSniSweep()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonSniSweep.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
        }

        viewModel.sweepStatus.observe(viewLifecycleOwner) { text ->
            if (text.isNullOrBlank()) {
                binding.textSweepStatus.visibility = View.GONE
            } else {
                binding.textSweepStatus.visibility = View.VISIBLE
                binding.textSweepStatus.text = text
            }
        }

        viewModel.sweepResults.observe(viewLifecycleOwner) { results ->
            sweepAdapter.submitList(results)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
