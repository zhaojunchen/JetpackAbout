package cn.zhaojunchen.livedatacourse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.zhaojunchen.livedatacourse.databinding.AFragmentBinding

class AFragment : Fragment() {

    private val viewModel by viewModels<AViewModel>()

    private var _binding: AFragmentBinding? = null
    private val binding: AFragmentBinding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataBindingUtil.inflate<AFragmentBinding>(
            inflater,
            R.layout.a_fragment,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Add observe

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}