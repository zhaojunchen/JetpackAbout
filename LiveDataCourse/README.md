## 带着问题分析LiveData源码

[TOC]

### 0、导读

本文将从LiveData实际用法的角度去分析LiveData的源码， 回答以下问题：

1. LiveData何时会刷新?
   1. 为什么通过`setValue`会开始刷新
   2. 为什么调用 `observe`之后会自动刷新（并未调用 `setValue`）
   3. `observeForver`方法的刷新方式和`observe` 刷新的不同之处
   4. 解释Navigation Fragment回退导致的二次刷新（粘滞效应）的原因
   
2. LiveData和生命周期组件的绑定关系
   1. LiveData observe如何和Lifecycle高度结合
   2. Livedata自动绑定和取消
      

### 1、 抛转引玉

 **LiveData观察者何时会执行onChange方法？**

先说结论，方便大家对着问题看源码:

- 对于**observe**方法添加的观察者 ( `void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) `)
  - 当调用`setValue`方法，会将本次的`value`变更分发到所有处在活跃（(生命周期至少为**START**)）的观察者 ， 非活跃状态的观察者丢弃本次`value`变更
  - `observe` 方法执行时，会（隐式地、通过生命周期观察者监听）执行一次, 可以简单认为`observe` 调用后、一旦对应 `LifecycleOwner` 生命周期转到**START**状态，必定执行一次（即便是添加监听时处在**INITIAL**状态）,  存在这样的一个**粘滞**效应，**这和前面的`setValue`是有不同之处的**
- 对于**observeForever**方法添加的观察者( `void observeForever(@NonNull Observer<? super T> observer)`)
  - 当调用`setValue`方法，立即刷新 （没有生命周期的相关限制）
  - `observeForever` 方法执行时，会立即调用一次，因为其没和生命周期组件绑定无法自动销毁，**故千万记得手动销毁**

**（PS: 后期除非强调，默认为observe方法添加的带有生命周期的观察者）**



**LiveData为何很难 “内存泄露”？他是如何和生命周期高度绑定?**

内存泄露常见于生命周期组件（Activity、Fragment等），当这些组件销毁时，我们需要清除组件中已经存在的任务（回调、线程等）避免内存泄露。LiveData作为为UI服务的组件，生命周期对他的重要性不言而喻。因此为避免内存泄露，我们需要在`Activity/Fragment/(Navigation中的ViewLifecycle)`销毁的时候移除这些带有`onChange`执行逻辑的LiveData观察者。  `Lifecycle` 那一套组件就是专门干这事情的， 事实上LiveData高度依赖这个`Lifecycle` , 在LiveData添加的观察者会被封装为 `LifecycleBoundObserver` （实现了 `LifecycleEventObserver` 接口）,  这个观察者被添加到LiveData时，会自动的将这个 **包装之后的观察者** 绑定到`Lifecycle`， 在`DESTROY`时被自动移除, **因此只要添加正确的生命周期，它就不会泄露**

**TIPS：本文需要对`Lifecycle` 有一定的了解。**

### 2、 源码分析

#### 1、构造函数及相关成员

LiveData的2个构造函数，一个带参数，一个不带参数。其中 `mData`显然就是它真实存储的对象，也即是我们设置的`Value`, 但是这里的`mVersion` 成员是什么？ 先说结论： 他是记录LiveData刷新次数的一个变量，只在构造函数和 `setValue`中变化(源码中搜一下即可），部分刷新的逻辑需要用到它。然后看一下这个`NOT_SET`默认值，这个是静态变量，给LiveData无参构造函数用的，没什么实际的意义**（但是这也是为啥LiveData的Value可能是为空的原因）**

```
public abstract class LiveData<T> {
    // 起始版本号
    static final int START_VERSION = -1;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 无参构造时value的默认值
    static final Object NOT_SET = new Object();
    // 存储LiveData的观察者集合，观察者是ObserverWrapper类型（经过包装的Observe对象）
    private SafeIterableMap<Observer<? super T>, ObserverWrapper> mObservers =
            new SafeIterableMap<>();
    // how many observers are in active state
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 当前处在Active状态的observer个数
    int mActiveCount = 0;
    // 实际存储的对象（value）
    private volatile Object mData;
    // when setData is called, we set the pending data and actual data swap happens on the main
    // thread
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 版本号，通过构造函数和setValue更新
    private int mVersion;

    /**
     * Creates a LiveData initialized with the given {@code value}.
     *
     * @param value initial value
     */
    public LiveData(T value) {
        mData = value;
        mVersion = START_VERSION + 1;
    }

    /**
     * Creates a LiveData with no value assigned to it.
     */
    public LiveData() {
        mData = NOT_SET;
        mVersion = START_VERSION;
    }
```

`LiveData.getValue` 其Value可能为空， **大家千万要注意这一点(不然!!操作可能出现NPE问题)**

```
@Nullable
public T getValue() {
    Object data = mData;
    if (data != NOT_SET) {
        return (T) data;
    }
    return null;
}
```



下面看一个用法：主要的区别在于构造函数是**否存在参数**

```
class AViewModel : ViewModel() {
    // Without any params
    private val _testNoParam = MutableLiveData<String>()
    val testNoParam: LiveData<String>
        get() = _testNoParam

    private val _testHasParam = MutableLiveData<String>("Has a params")
    val testHasParam: LiveData<String>
        get() = _testHasParam
}
```

先说结论，**不带参数的LiveData构造函数在手动调用setValue之前永远不可能被触发** ， 从 `setValue`开始追踪， `setValue->dispatchingValue->considerNotify`  , `considerNotify` 函数表示考虑是否应该通知观察者调用`onChange`方法（`onChange`的调用只会在这个函数中触发）, 其中的会经过一系列的逻辑判断和检查, `mVersion==START_VERSION`时`observer.mLastVersion >= mVersion`  始终成立，故此不会调用`onChange`方法!   

```
	@SuppressWarnings("unchecked")
    private void considerNotify(ObserverWrapper observer) {
        if (!observer.mActive) {
            return;
        }
        // xxx ...
        if (observer.mLastVersion >= mVersion) {
            return;
        }
        observer.mLastVersion = mVersion;
        observer.mObserver.onChanged((T) mData);
    }
	
public interface Observer<T> {
    /**
     * Called when the data is changed.
     * @param t  The new data
     */
    void onChanged(T t);
}

```

**mVersion导致的区别需要特别注意：** 例如你需要网络请求返回一个点赞数并显示到页面， 你可能有一下二种做法：

`ViewModel`申明2个LiveData，前者不带参数、后者带参数

```
class AViewModel : ViewModel() {
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
```

Fragment中添加监听:

```
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    viewModel.requestLikes()
    // Add observe
    viewModel.likes.observe(viewLifecycleOwner) {
        binding.tv2.text = it.toString()
    }

    viewModel.likes1.observe(viewLifecycleOwner) {
        binding.tv3.text = it.toString()
    }
}
```

`likes1` 会立即将0显示在界面上，而`likes1`直到网络返回之后才会显示数据，这个时候可能需要你去决定没有网络数据时应该显示什么?  **为什么likes1会立马刷新数据在后面会提到。**

#### 2、 刷新逻辑分析

首先从 `setValue` &&  `observe` 这个2个API入手分析：

查看`setValue` 源码，首先是`@MainThread` 注解限制只能是主线程调用 `setValue` （子线程更新请使用`postValue` 方法）, 然后是版本号`mVersion`和`mData`的更新, 最后是对 `value`更新事件进行分发，调用的方法为 `dispatchingValue`

```
@MainThread
protected void setValue(T value) {
    assertMainThread("setValue");
    mVersion++;
    mData = value;
    dispatchingValue(null);
}
    
```

**PS：** 实际上`postValue` 也是通过主线程的`Handler`将更新任务调度到主线程，然后执行`setValue` ， 所以后面就不再说`postValue`了。

```
 // postValue 的Runnable, 通过handler分发到主线程
 private final Runnable mPostValueRunnable = new Runnable() {
     @SuppressWarnings("unchecked")
     @Override
     public void run() {
         Object newValue;
         synchronized (mDataLock) {
             newValue = mPendingData;
             mPendingData = NOT_SET;
         }
         setValue((T) newValue);
     }
 };

 protected void postValue(T value) {
     boolean postTask;
     synchronized (mDataLock) {
         postTask = mPendingData == NOT_SET;
         mPendingData = value;
     }
     if (!postTask) {
         return;
     }
     ArchTaskExecutor.getInstance().postToMainThread(mPostValueRunnable);
 }
```

跟踪 `dispatchingValue` 并全局搜索它， 发现只有2中调用方式 1、传入 `initiator=null` 对`mObservers` 所有观察者进行分发， 2、 传某个具体的`ObserverWrapper` , 仅仅针对这一个特定的观察者进行分发，显然通过`setValue`更新`value`时需要传入`null`，实现对全部观察者的分发， 对于observe方法的更新（先剧透一下）是传递那个指定的`ObserverWrapper` 作为参数，实现单个观察者的onChange触发。

```
void dispatchingValue(@Nullable ObserverWrapper initiator) {
    if (mDispatchingValue) {
        mDispatchInvalidated = true;
        return;
    }
    mDispatchingValue = true;
    do {
        mDispatchInvalidated = false;
        if (initiator != null) {
            considerNotify(initiator);
            initiator = null;
        } else {
            for (Iterator<Map.Entry<Observer<? super T>, ObserverWrapper>> iterator =
                    mObservers.iteratorWithAdditions(); iterator.hasNext(); ) {
                considerNotify(iterator.next().getValue());
                if (mDispatchInvalidated) {
                    break;
                }
            }
        }
    } while (mDispatchInvalidated);
    mDispatchingValue = false;
}
```

进入函数之后 `mDispatchingValue` 立即设置正在更新， 循环体中根据 `initiator` 判断是进行单个分发还是全部分发，具体到单个`ObserverWrapper`的分发逻辑都是一样的， 为啥整成 `do-while`  循环？？？ 猜测是 `dispatchingValue`还未执行完毕时就（**出于某种情况**）再次在此调用`dispatchingValue`， 首先检测到 `mDispatchingValue==true`, 立马设置 `mDispatchInvalidated=true`, 首先`mDispatchInvalidated`会导致for循环体立即退出，其次会导致`while`循环体再次执行一次！ **出于某种情况** 还不知道是啥情况😁 有兴趣可以挖一下，大致表达的就上面的意思。

----

然后看一下单个`ObserverWrapper` 的`considerNotify` 逻辑，首先是检查**ObserverWrapper是否是处在激活状态的(ObserverWrapper.mActive)**，然后是二次检查 `shouldBeActive`，主要是为了确保带有生命周期的观察者进入**STARTED** ，**这就是为什么`LiveData` 只能在大于等于START的状态才能被更新的原因！！！** 关于生命周期的内容可以参考 [Activity生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)、[Fragment生命周期](https://developer.android.com/guide/fragments/lifecycle)

```
private void considerNotify(ObserverWrapper observer) {
    // ObserverWrapper 是否激活
    if (!observer.mActive) {
        return;
    }
    // Check latest state b4 dispatch. Maybe it changed state but we didn't get the event yet.
    //
    // we still first check observer.active to keep it as the entrance for events. So even if
    // the observer moved to an active state, if we've not received that event, we better not
    // notify for a more predictable notification order.
    if (!observer.shouldBeActive()) {
        observer.activeStateChanged(false);
        return;
    }
    if (observer.mLastVersion >= mVersion) {
        return;
    }
    observer.mLastVersion = mVersion;
    observer.mObserver.onChanged((T) mData);
}
```

`shouldBeActive` 是`ObserverWrapper`的抽象方法：具体的实现在`AlwaysActiveObserver`(对应于`ObserveForever`) 和 `LifecycleBoundObserver` （对应于`observe`）, 前者始终是 `shouldBeActive=true` ，后者只会在`Lifecycle`对应的状态为至少`STARTED`，才会变为 `shouldBeActive=true`

```
private abstract class ObserverWrapper {
    abstract boolean shouldBeActive();
}
```

`AlwaysActiveObserver`

```
private class AlwaysActiveObserver extends ObserverWrapper {
        @Override
        boolean shouldBeActive() {
            return true;
        }
    }
```

`LifecycleBoundObserver`

```
class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver {
        @Override
        boolean shouldBeActive() {
            return mOwner.getLifecycle().getCurrentState().isAtLeast(STARTED);
        }
}
```

然后是版本号的检查，`observer.mLastVersion >= mVersion`, 主要是2个作用  

- 确保二次进入`considerNotify`时， `observer.mLastVersion >= mVersion`始终成立，避免可能出现一次`setValue`导致2次onChange的情况（更新一次之后会存在赋值操作 `observer.mLastVersion = mVersion;`）
- 确保对应无参构造的LiveData在未设置`setValue`时（`mVersion = -1`）, 使用`observe` 或者 `observeForever` 不会触发 `onChange`  ，（`ObserverWrapper.mLastVersion` 初始化时也是`-1`） 

当检查通过后，`observer.mObserver.onChanged((T) mData);` 代码触发 **`onChange`** 。

#### 3、 观察者分析

前面提到了`onChange`, 其实就是我们编写的**观察者方法** , 下面看一下片段，如何添加观察者： 

```
// kotlin语法糖简化版本
viewModel.likes.observe(viewLifecycleOwner) {
    binding.tv2.text = it.toString()
}
// 原始版本
viewModel.likes.observe(viewLifecycleOwner, object : Observer<Int> {
    override fun onChanged(t: Int?) {
        binding.tv2.text = t.toString()
    }
})
```

实际上添加的是`Observer`接口实例， 本身就只是 保存了 **数据刷新（onChange）** 这个**单一职责**

```
public interface Observer<T> {
    /**
     * Called when the data is changed.
     * @param t  The new data
     */
    void onChanged(T t);
}
```

看一下`LiveData`的`mObservers` 成员, 他维护了`LiveData`的所有观察者, 可以看到实际上我们添加 `Observer` 对象只是作为一个 Map中的`Key`, 实际上`Value`为 `ObserverWrapper`对象，现在就是 

```
private SafeIterableMap<Observer<? super T>, ObserverWrapper> mObservers =
        new SafeIterableMap<>();
```

看一下`ObserverWrapper`, 可以看到构造函数就是传入的`Observer`对象(保存`onChange`的执行逻辑)， 进行了一下包装，比如添加了版本号`mLastVersion`, `mActive`, 这些变量都出现在了之前的 **刷新逻辑分析**中

```
private abstract class ObserverWrapper {
    final Observer<? super T> mObserver;
    boolean mActive;
    int mLastVersion = START_VERSION;

    ObserverWrapper(Observer<? super T> observer) {
        mObserver = observer;
    }

    abstract boolean shouldBeActive();

    boolean isAttachedTo(LifecycleOwner owner) {
        return false;
    }

    void detachObserver() {
    }

    void activeStateChanged(boolean newActive) {
        if (newActive == mActive) {
            return;
        }
        // immediately set active state, so we'd never dispatch anything to inactive
        // owner
        mActive = newActive;
        changeActiveCounter(mActive ? 1 : -1);
        if (mActive) {
            dispatchingValue(this);
        }
    }
}
```

这个包装过程  `Observer->ObserverWrapper` 是在 `observe` 和 `observeForever`中实现的，他们分别封装了`LifecycleBoundObserver`和`AlwaysActiveObserver`

```
@MainThread
public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
    assertMainThread("observe");
    if (owner.getLifecycle().getCurrentState() == DESTROYED) {
        // ignore
        return;
    }
    LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer);
    ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);
    if (existing != null && !existing.isAttachedTo(owner)) {
        throw new IllegalArgumentException("Cannot add the same observer"
                + " with different lifecycles");
    }
    if (existing != null) {
        return;
    }
    owner.getLifecycle().addObserver(wrapper);
}

@MainThread
public void observeForever(@NonNull Observer<? super T> observer) {
    assertMainThread("observeForever");
    AlwaysActiveObserver wrapper = new AlwaysActiveObserver(observer);
    ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);
    if (existing instanceof LiveData.LifecycleBoundObserver) {
        throw new IllegalArgumentException("Cannot add the same observer"
                + " with different lifecycles");
    }
    if (existing != null) {
        return;
    }
    wrapper.activeStateChanged(true);
}
```

`AlwaysActiveObserver`逻辑很简单，就是简单的继承了ObserverWrapper， `shouldBeActive`始终返回`true` 。

```
private class AlwaysActiveObserver extends ObserverWrapper {

    AlwaysActiveObserver(Observer<? super T> observer) {
        super(observer);
    }

    @Override
    boolean shouldBeActive() {
        return true;
    }
}
```

`LifecycleBoundObserver` 这就很复杂了，`mOwner` 是`observe`方法中添加的`LifecycleOwner`， 通过他实现 生命周期相关的监听、自动解绑等； 其实现了`LifecycleEventObserver` 接口，重写了`onStateChanged`， 可以看到`DESTROYED` 会执行自动移除观察者，避免内存泄露， 监听生命周期状态，**判断是否立即出发对应的刷新逻辑（后面会提到）** 

```
class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver {
    @NonNull
    final LifecycleOwner mOwner;

    LifecycleBoundObserver(@NonNull LifecycleOwner owner, Observer<? super T> observer) {
        super(observer);
        mOwner = owner;
    }

    @Override
    boolean shouldBeActive() {
        return mOwner.getLifecycle().getCurrentState().isAtLeast(STARTED);
    }

    @Override
    public void onStateChanged(@NonNull LifecycleOwner source,
            @NonNull Lifecycle.Event event) {
        Lifecycle.State currentState = mOwner.getLifecycle().getCurrentState();
        if (currentState == DESTROYED) {
            removeObserver(mObserver);
            return;
        }
        Lifecycle.State prevState = null;
        while (prevState != currentState) {
            prevState = currentState;
            activeStateChanged(shouldBeActive());
            currentState = mOwner.getLifecycle().getCurrentState();
        }
    }

    @Override
    boolean isAttachedTo(LifecycleOwner owner) {
        return mOwner == owner;
    }

    @Override
    void detachObserver() {
        mOwner.getLifecycle().removeObserver(this);
    }
}
```



#### 4、 生命周期分析

现在对上面的`LifecycleBoundObserver`进行更加细致的分析：`LifecycleBoundObserver`继承   `LifecycleEventObserver` 接口，当生命周期变化时，会触发(由Activity等生命周期组件内部处理，我们只需知道`Lifecycle`生命周期变化会触发`onStateChanged`就可以了)`public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event)` 

```
	@Override
    public void onStateChanged(@NonNull LifecycleOwner source,
            @NonNull Lifecycle.Event event) {
        Lifecycle.State currentState = mOwner.getLifecycle().getCurrentState();
        if (currentState == DESTROYED) {
            removeObserver(mObserver);
            return;
        }
        Lifecycle.State prevState = null;
        while (prevState != currentState) {
            prevState = currentState;
            activeStateChanged(shouldBeActive());
            currentState = mOwner.getLifecycle().getCurrentState();
        }
    }
```

当处在`DESTROYED`销毁时，直接移除这个观察者，避免内存泄露， 其余状态时候，流转到`while` 循环, 执行 `activeStateChanged(shouldBeActive());` 方法，屡一下这个方法， `newActive`（其值为`shouldBeActive()`、即状态是否大于等于`STARTED`）相等时, 直接退出（避免触发多次），假设现在`shouldBeActive`返回`true`（大于等于STARTED状态）， 而`observe`方法调用的时候,`mActive`其实是为`false`的， ok第一个`false`通过, 然后将`mActive`更新为`true`(激活状态), 随后进入 `changeActiveCounter` 就是统计一下当前活跃的观察者! 最后分发`this`， 执行一次刷新！！! ok到此走完逻辑

```
void activeStateChanged(boolean newActive) {
    if (newActive == mActive) {
        return;
    }
    // immediately set active state, so we'd never dispatch anything to inactive
    // owner
    mActive = newActive;
    changeActiveCounter(mActive ? 1 : -1);
    if (mActive) {
        dispatchingValue(this);
    }
}
```



源码分析的时候，我在想 `onStateChanged` **何时会触发**？ 比如说： 我在`Activity onRusume`方法去添加`LiveData`观察者，最终走到 ` owner.getLifecycle().addObserver(wrapper);`

```
@MainThread
public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
    xxx
    LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer);
    xxx
    owner.getLifecycle().addObserver(wrapper);
}
```

即是我是在`RESUME`状态才添加观察者， 但是之后的`Activity`一直处在`RESUME`状态时候，岂不是不会执行`onStateChanged`方法(因为此时状态没有变化：`RESUME->RESUME`)，从而导致在`onResume`中绑定的`LiveData`就只能响应`setValue`方法，而无法直接在接在observe方法观察到最新的数据？我想了下这和之前LiveData的用法不相符合, 后来试了一下，发现 **不管何时添加调用**`observe` , `onStateChanged` 始终都会从开始状态流转到当前状态

```
override fun onResume() {
    super.onResume()
    lifecycle.addObserver(testLife)
}

val testLife = object : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        Log.d("LifecycleEventObserver", "${source.lifecycle.currentState}:$event")
    }
}
```

这一块的逻辑在这里：`LifecycleRegistry.addObserver` ， `while`循环会分发`dispatchEvent`直到分发到`targetState` , 核心类为：`ObserverWithState statefulObserver = new ObserverWithState(observer, initialState);`  `initialState` 可以看到在非`DESTROYED` 时候是`INITIALIZED`, 然后在`while`循环中和`targetState` (当前状态)比较并且分发这个状态 `dispatchEvent` , **如当前我在RESUME状态添加LiveData观察者，但是他会从INITIALIZED一直分发的RESUME，并且分别依次调用LifecycleBoundObserver的onStateChanged方法，当分发到STARTED状态时,`activeStateChanged(shouldBeActive());` 成功执行，最终调用到`dispatchingValue(this);`, 立即触发一次`onChange`**

```
	@Override
public void addObserver(@NonNull LifecycleObserver observer) {
    enforceMainThreadIfNeeded("addObserver");
    State initialState = mState == DESTROYED ? DESTROYED : INITIALIZED;
    ObserverWithState statefulObserver = new ObserverWithState(observer, initialState);
    ObserverWithState previous = mObserverMap.putIfAbsent(observer, statefulObserver);

    if (previous != null) {
        return;
    }
    LifecycleOwner lifecycleOwner = mLifecycleOwner.get();
    if (lifecycleOwner == null) {
        // it is null we should be destroyed. Fallback quickly
        return;
    }

    boolean isReentrance = mAddingObserverCounter != 0 || mHandlingEvent;
    State targetState = calculateTargetState(observer);
    mAddingObserverCounter++;
    while ((statefulObserver.mState.compareTo(targetState) < 0
            && mObserverMap.contains(observer))) {
        pushParentState(statefulObserver.mState);
        final Event event = Event.upFrom(statefulObserver.mState);
        if (event == null) {
            throw new IllegalStateException("no event up from " + statefulObserver.mState);
        }
        statefulObserver.dispatchEvent(lifecycleOwner, event);
        popParentState();
        // mState / subling may have been changed recalculate
        targetState = calculateTargetState(observer);
    }

    if (!isReentrance) {
        // we do sync only on the top level.
        sync();
    }
    mAddingObserverCounter--;
}
```



### 3、 总结

导读问题回到：

1. LiveData何时会刷新? 
   1. 为什么通过`setValue`会开始刷新
   2. 为什么调用 `observe`之后会自动刷新（并未调用 `setValue`）
   3. `observeForver`方法的刷新方式和`observe` 刷新的不同之处
   4. 解释Navigation Fragment回退导致的二次刷新（粘滞效应）的原因
2. LiveData和生命周期组件的绑定关系
   1. LiveData observe如何和Lifecycle高度结合
   2. Livedata自动绑定和取消





问题1：

- 对于**observe**方法添加的观察者 
  - 当调用`setValue`方法，会将本次的`value`变更分发到所有处在活跃（(生命周期至少为**STARTED**)）的观察者，非活跃状态的观察者丢弃本次`value`变更
  - `observe` 方法执行时，会（隐式地、通过生命周期观察者监听）执行一次, 可以简单认为`observe` 调用后、一旦对应 `LifecycleOwner` 生命周期转到**STARTED**状态，必定执行一次（即便是添加监听时处在**INITIAL或者RESUME**状态）,  存在这样的一个**粘滞**效应，**这和前面的`setValue`是有不同之处的**
- 对于**observeForever**方法添加的观察者( `void observeForever(@NonNull Observer<? super T> observer)`)
  - 当调用`setValue`方法，立即刷新 （没有生命周期的相关限制）
  - `observeForever` 方法执行时，会立即调用一次
- 注意在LiveData的版本号 `mVersion==-1`的情况下永远不会触发**onChange**（LiveData是无参构造并且并未调用setValue时）

问题1-1：

`LiveData`遍历其`mObservers` 逐一分发变更事件

问题1-2：

通过`LifecycleEventObserve` 组件在生命周期变化时驱动并调用`LiveData`事件分发

问题1-3：

无生命周期相关、需要自行解绑，变更分发更加的直接

问题1-4：

问题1-2中描述的现象就是所谓的粘滞效应, 当返回时，界面重建会在此执行`observe` 获取LiveData之前保存的最新值



问题2:

`observe` 方法将`Observer`包装为`LifecycleBoundObserver`（`Observer->ObserverWrapper->LifecycleBoundObserver`）、从而使得Observe和生命周期组件高度绑定、自动销毁、仅在STARTED状态进行触发



