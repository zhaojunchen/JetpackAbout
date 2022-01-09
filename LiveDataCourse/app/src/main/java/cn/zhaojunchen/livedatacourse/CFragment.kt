package cn.zhaojunchen.livedatacourse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.zhaojunchen.livedatacourse.databinding.CFragmentBinding

class CFragment : Fragment() {

    private val viewModel by viewModels<CViewModel>()

    private var _binding: CFragmentBinding? = null
    private val binding: CFragmentBinding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataBindingUtil.inflate<CFragmentBinding>(
            inflater,
            R.layout.c_fragment,
            container,
            false
        )
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}