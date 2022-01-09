package cn.zhaojunchen.livedatacourse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.zhaojunchen.livedatacourse.databinding.BFragmentBinding

class BFragment : Fragment() {

    private val viewModel by viewModels<BViewModel>()

    private var _binding: BFragmentBinding? = null
    private val binding: BFragmentBinding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataBindingUtil.inflate(
            inflater,
            R.layout.b_fragment,
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