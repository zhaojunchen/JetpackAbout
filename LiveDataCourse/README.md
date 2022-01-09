## LiveData

[TOC]

### 1、 抛转引玉

本文将从LiveData功能的角度去分析LiveData的源码， 回答以下问题

1. LiveData何时会刷新?
   1. 为什么通过`setValue`会开始刷新
   2. 为什么调用 `observe`之后会自动刷新（并未调用 `setValue`）
   3. `observeForver`方法的刷新方式和`observe` 刷新的不同之处
   4. 解释Navigation Fragment回退导致的二次刷新（粘滞效应）的原因
2. LiveData和生命周期组件的绑定关系
   1. LiveData observe如何和Lifecycle高度结合
   2. Livedata自动绑定和取消

问题1、2存在部分的重叠，但是阐述的角度不同，我会在每一小节做出总结并和上述问题关联起来，解答疑问。TIPS：本文需要对`Lifecycle` 有一定的了解（笔者会穿插部分这些知识、便于理解）！  

**文献阅读时请同步打开IDE LiveData源码**

### 2、 LiveData何时会刷新？

#### 1、LiveData的构造函数

LiveData的2个构造函数，一个带参数，一个不带参数。其中 `mData`显然就是它真实存储的对象，也即是我们设置的`Value`, 但是这里有个`mVersion` 成员是什么？ 先说结论： 他是记录LiveData刷新次数的一个变量，只在构造函数和 `setValue`中变化（全局搜一下即可），部分刷新的逻辑需要用到它。然后看一下这个`NOT_SET`默认值，这个是静态变量，给LiveData无参构造函数用的，没什么实际的意义。

```
public abstract class LiveData<T> {
    static final int START_VERSION = -1;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    static final Object NOT_SET = new Object();

    private SafeIterableMap<Observer<? super T>, ObserverWrapper> mObservers =
            new SafeIterableMap<>();
    // how many observers are in active state
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    int mActiveCount = 0;
    private volatile Object mData;
    // when setData is called, we set the pending data and actual data swap happens on the main
    // thread
    @SuppressWarnings("WeakerAccess") /* synthetic access */
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



下面看一个用法：主要的区别在于构造函数是否存在参数！

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

先说结论，**不带参数的LiveData构造函数在手动调用setValue之前永远不可能被触发** ， 从 `setValue`开始追踪， `setValue->dispatchingValue->considerNotify`  , `considerNotify` 函数表示考虑是否通知观察者调用onChange方法, 其中的会经过一系列的检查, `mVersion==START_VERSION`时`observer.mLastVersion >= mVersion`  始终成立，故此不会调用onChange方法!   

```
public interface Observer<T> {
    /**
     * Called when the data is changed.
     * @param t  The new data
     */
    void onChanged(T t);
}

@SuppressWarnings("unchecked")
    private void considerNotify(ObserverWrapper observer) {
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

 

**这一点的区别需要特别注意：** 例如你需要网络请求返回一个点赞数并显示到页面， 你可能有一下2中做法：

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



`likes` 会立即将0显示在界面上，而`likes1` 知道网络返回之后才会显示数据   **为什么likes会立马刷新数据在后面会提到**



#### 2、 源码分析

现在开始源代码分析！，首先是LiveData的相关成员

```
public abstract class LiveData<T> {
	// 起始版本号
    static final int START_VERSION = -1;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 无参构造时value的默认值
    static final Object NOT_SET = new Object();
	// 存储LiveData的观察者集合，观察者是 ObserverWrapper类型（经过包装的Observe对象）
    private SafeIterableMap<Observer<? super T>, ObserverWrapper> mObservers =
            new SafeIterableMap<>();
    // how many observers are in active state
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 当前Active状态的observer个数
    int mActiveCount = 0;
    // 实际存储的对象
    private volatile Object mData;
    // when setData is called, we set the pending data and actual data swap happens on the main
    // thread
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    // 版本号，通过构造函数和setValue更新
    private int mVersion;
```

构造函数就不说了，需要注意`mVersion==START_VERSION`对观察者的影响

然后找到2个主入口 `setValue` &&  `observe`方法

先跟踪`setValue`：首先是`@MainThread` 限制 只能是主线程调用 `setValue` , 然后是版本号mVersion和mData的更新！最后是对 value更新事件（`value`）进行分发， 调用的方法为 `dispatchingValue`

```
 	@MainThread
    protected void setValue(T value) {
        assertMainThread("setValue");
        mVersion++;
        mData = value;
        dispatchingValue(null);
    }
    
```

> ( 实际上`postValue` 也是通过 主线程的`Handler`将更新任务调度到主线程，然后执行`setValue` )
>
> ```
> // postValue 的Runnable, 通过handler分发到主线程
>     private final Runnable mPostValueRunnable = new Runnable() {
>         @SuppressWarnings("unchecked")
>         @Override
>         public void run() {
>             Object newValue;
>             synchronized (mDataLock) {
>                 newValue = mPendingData;
>                 mPendingData = NOT_SET;
>             }
>             setValue((T) newValue);
>         }
>     };
>     
>     protected void postValue(T value) {
>         boolean postTask;
>         synchronized (mDataLock) {
>             postTask = mPendingData == NOT_SET;
>             mPendingData = value;
>         }
>         if (!postTask) {
>             return;
>         }
>         ArchTaskExecutor.getInstance().postToMainThread(mPostValueRunnable);
>     }
>   
> ```



跟踪 `dispatchingValue` 并全局搜索它， 发现只有2中调用方式 1、传入 `initiator=null` 对`mObservers` 所有观察者进行分发， 2、 传某个具体的`ObserverWrapper` , 仅仅针对这一个观察者进行分发，显然 通过`setValue`跟新`value`时需要传入`null`，实现全部分发， 对于observe方法的更新（先剧透一下）是传递那个指定的`ObserverWrapper`， 实现单个onChange的更新触发！

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

进入函数之后 `mDispatchingValue` 设置正在更新， 循环体中根据 `initiator` 判断是进行单个分发还是全部分发，具体到单个`ObserverWrapper`的分发逻辑都是一样的！ 为啥整成循环？？？ 猜测是 `dispatchingValue`还未执行完毕时 就（**出于某种情况**）再次在此调用`dispatchingValue`， 首先检测到 

`mDispatchingValue==true`, 立马设置 `mDispatchInvalidated=true`, 首先`mDispatchInvalidated`会导致for循环体立即退出，其次会导致`while`循环体再次执行一次！ **出于某种情况** 还不知道是啥情况😁 有兴趣可以挖一下，大致表达的就上面的意思。

----

然后看一下单个`ObserverWrapper` 的`considerNotify` 逻辑，首先是检查**ObserverWrapper是否是处在激活状态的**，然后是二次检查，主要是为了确保 带有生命周期的观察者进入**STARTED** ， **这就是为什么`LiveData` 只能在大于等于START的状态才能被更新的原因！！！** 关于可以参考 [Activity生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)、[Fragment生命周期](https://developer.android.com/guide/fragments/lifecycle)

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

然后是版本号的检查，`observer.mObserver.onChanged((T) mData);` 触发之前会执行  `observer.mLastVersion = mVersion;` ，确保了二次进入时 `observer.mLastVersion >= mVersion`始终成立，避免了可能的一次`setValue`导致2次onChange的情况！ **`onChange`** 是啥？ 显示就是我们编写的**观察者方法**

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



---

`considerNotify` 关于版本号和`onChange` 的逻辑相信没啥问题，主要是  `observer.mActive` `observer.shouldBeActive()` `observer.activeStateChanged()` 这几个方法是干什么？ 

首先看一下ObserverWrapper ， 这是 LiveData的内部类：他将Observer类进行了一下包装,  添加了部分成员和方法(简单的直接通过注释说明)：

```
private abstract class ObserverWrapper {
		// Observer 本体，存储onChange方法
        final Observer<? super T> mObserver;
        // 是否激活，较为复杂
        boolean mActive;
        // 最新版本号，请查阅 considerNotify中， 当调用了onChange之前，会同步
        // 更新为LiveData的mVersion字段， considerNotify中的逻辑保证了一次setValue只会
        // 执行一次onChange
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

其中比较复杂的是 shouldBeActive抽象方法和 activeStateChanged方法， 分析到这里还需要看一下抽象类是 ObserverWrapper的实体类是什么？

---

`LifecycleBoundObserver` && `AlwaysActiveObserver` 为他们的实例，前者需要结合一个`LifecycleOwner`对象、通过生命周期自动管理观察者， 后者不需要LifecycleOwner对象、需要手动释放掉观察者 。

**`LifecycleBoundObserver` 对应于 `observe`方法**

```
class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver {
    @NonNull
    final LifecycleOwner mOwner;

    LifecycleBoundObserver(@NonNull LifecycleOwner owner, Observer<? super T> observer) {
        super(observer);
        mOwner = owner;
    }
	// 只有在lifecycle生命周期大于STARTED的时候，才会更新(可以看一下shouldBeActive的调用地方，considerNotify)
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
```

`AlwaysActiveObserver`对应于`observeForever` ,  `wrapper.activeStateChanged(true);` 让`observeForever`对应的观察者`mActive`为`true`, `shouldBeActive` 同样为true， 因此 `considerNotify`的过滤条件对`observeForever`永久观察者是无效的，只要`setValue`就会刷新，并且在`observeForever` 时候，调用 `wrapper.activeStateChanged(true);` 可以发现执行了`dispatchingValue(this);`, 永久观察者的onChange触发时机为： 任何情况下的`setValue` 以及当 observeForever执行时(**注意：LiveData.mVersion==START_VERSION、即是无参LiveData构造时，不会触发**) 都会触发!

```
private class AlwaysActiveObserver extends ObserverWrapper {

    AlwaysActiveObserver(Observer<? super T> observer) {
        super(observer);
    }
	// considerNotify中，始终处于激活状态 
    @Override
    boolean shouldBeActive() {
        return true;
    }
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


---



切换到`LifecycleBoundObserver` 根据上述的分析，**observe方法的观察者会在`setValue`调用后，且其对应的生命周期状态大于等于START时触发**， 显然如果Fragment `onViewCreated` 是添加监听时、并且同时调用`setValue`，本次`setValue`不会导致刷新! 问题来了：之前的点赞数例子中，`likes1` 会在网络请求结果显示之前将0显示到界面，但是 除网络请求之外，没有任何地方对其赋值，（构造函数虽然赋值，但是不会触发`setValue`），既然他显示了数据，说明一定执行到了onChange的界面绑定逻辑，但是 到底是如何触发的 为于`considerNotify`中的 `observer.mObserver.onChanged((T) mData);`呢？

```
private val _likes1 = MutableLiveData<Int>(0)
    val likes1: LiveData<Int>
        get() = _likes1
```

  

---

#### 生命周期变化会触发onChange

`class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver ` `LifecycleBoundObserver` 继承了   `LifecycleEventObserver` 接口，当生命周期变化变化时，会触发其(由Activity等生命周期组件内部处理，我们只需知道Lifecycle生命周期变化就是触发他就可以了)`public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event)` 

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

当处在DESTROYED销毁时，直接移除这个观察者，避免内存泄露， 其余状态时候，流转到`while` 循环, 执行 `activeStateChanged(shouldBeActive());` 方法，再次屡一下这个方法， 状态相等时,直接退出（避免触发多次）， 假设现在shouldBeActive返回true（大于等于STARTED状态）， 而observe方法调用的时候,mActive其实是为false的， ok第一个false通过, 然后将mActive更新为true(激活状态), 随后进入 `changeActiveCounter` 就是统计一下当前活跃的观察者! 最后分发this， 执行一次刷新！！! ok到此走完逻辑

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



---

源码分析的时候，我在想 `onStateChanged` 合适会触发？ 比如说： 我在Activity onRusume状态去添加LiveData观察者，最终走到 ` owner.getLifecycle().addObserver(wrapper);`

```
@MainThread
    public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        xxx
        LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer);
        xxx
        owner.getLifecycle().addObserver(wrapper);
    }
```

即是： 我是在RESUME状态才添加观察者， 当我Activity一直处在RESUME状态时候，岂不是不会执行

onStateChanged方法，从而导致在onResume中绑定的LiveData就只能响应setValue方法，而无法直接观察到通过构造函数产生的原始数据？



后来试了一下，发现 不管 何时添加 `owner.getLifecycle().addObserver(wrapper);` , `onStateChanged` 始终都会从开始状态流转到当前状态

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

打印日志：

```

```

 

这一块的逻辑在这里：`LifecycleRegistry.addObserver` ， `while`循环会分发`dispatchEvent`直到分发到`targetState` , 核心类为：`ObserverWithState statefulObserver = new ObserverWithState(observer, initialState);`

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

