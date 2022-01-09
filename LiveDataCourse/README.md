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