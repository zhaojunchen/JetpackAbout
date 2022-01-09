package cn.zhaojunchen.livedatacourse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AViewModel : ViewModel() {
    // Without any params
    private val _testNoParam = MutableLiveData<String>()
    val testNoParam: LiveData<String>
        get() = _testNoParam

    private val _testHasParam = MutableLiveData<String>("Has a params")
    val testHasParam: LiveData<String>
        get() = _testHasParam
}