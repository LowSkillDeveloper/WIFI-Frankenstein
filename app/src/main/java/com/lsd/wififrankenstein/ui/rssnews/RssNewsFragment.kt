package com.lsd.wififrankenstein.ui.rssnews

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lsd.wififrankenstein.databinding.FragmentRssNewsBinding

class RssNewsFragment : Fragment() {

    private var _binding: FragmentRssNewsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rssNewsViewModel = ViewModelProvider(this)[RssNewsViewModel::class.java]

        _binding = FragmentRssNewsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textRssNews
        rssNewsViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}