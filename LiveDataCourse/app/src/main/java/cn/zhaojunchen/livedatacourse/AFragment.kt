package cn.zhaojunchen.livedatacourse

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
        viewModel.requestLikes()
        // Add observe
        viewModel.likes.observe(viewLifecycleOwner) {
            binding.tv2.text = it.toString()
        }

        viewModel.likes.observe(viewLifecycleOwner) { t ->
            binding.tv3.text = t.toString()
        }

        viewModel.likes1.observe(viewLifecycleOwner) {
            binding.tv3.text = it.toString()
        }

    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        lifecycle.addObserver(testLife)
    }


    val testLife = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            Log.d("LifecycleEventObserver", "${source.lifecycle.currentState}:$event")
        }
    }


}