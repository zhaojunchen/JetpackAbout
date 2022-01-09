package cn.zhaojunchen.livedatacourse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AViewModel : ViewModel() {
    // Without any params
    private val _testNoParam = MutableLiveData<String>()
    val testNoParam: LiveData<String>
        get() = _testNoParam

    private val _testHasParam = MutableLiveData<String>("Has a params")
    val testHasParam: LiveData<String>
        get() = _testHasParam

    private val _likes = MutableLiveData<Int>()
    val likes: LiveData<Int>
        get() = _likes

    private val _likes1 = MutableLiveData<Int>(0)
    val likes1: LiveData<Int>
        get() = _likes1

    fun requestLikes() {
        viewModelScope.launch {
            // delay
            delay(2000)
            _likes.value = 100
            _likes1.value = 100
        }
    }
}