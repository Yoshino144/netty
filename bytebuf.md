# Netty 缓冲区 ByteBuf 源码

## 概述

​		在网络传输中，字节是基本单位，NIO使用ByteBuffer作为Byte字节容器，但是其使用过于复杂。因此Netty写了一套Channel，代替了NIO的Channel。Netty缓冲区又采用了一套ByteBuf代替了NIO的ByteBuffer。

<img src="./assets/screenshot-20230519-104024.png"  />

​		NIO ByteBuffer只有一个位置指针position，在切换读/写状态时，需要手动调用flip()方法或rewind()方法，以改变position的值，而且ByteBuffer的长度是固定的，一旦分配完成就不能再进行扩容和收缩，当需要放入或存储的对象大于ByteBuffer的容量时会发生异常。每次编码时都要进行可写空间校验。Netty的AbstractByteBuf将读/写指针分离，同时在写操作时进行了自动扩容。

## 主要类

​		 Netty 比较常用的几类 ByteBuf 对象：

- `ByteBuf` 实现 `ReferenceCounted` 和 `Comparable` 两个接口，分别具有引用计数和两个 ByteBuf 比较的能力。
- `ByteBuf` 是一个抽象类，但完全可以定义成接口，但是 Netty 官方称定义为抽象类比定义为接口性能高一点。里面绝大部分都是抽象方法，由一系列操作 ByteBuf 的API 组成，下面会详细讲解。
- `AbstractByteBuf` 是重要的抽象类，它是实现一个 Buffer 的骨架。重写了绝大部分 ByteBuf 抽象类的抽象方法，封装了 ByteBuf 操作的共同逻辑，比如在获取数据前检查索引是否有效等等。在 AbstractByteBuf 中定义了两个重要的指针变量简化用户降低 ByteBuf 的难度:
  - `readerIndex`
  - `writerIndex`

- `AbstractReferenceCountedByteBuf` 也是重要的抽象类，主要实现了引用计数相关逻辑。内部维护一个 volatile int refCnt 变量。但是所有对 refCnt 的操作都需要通过 ReferenceCountUpdater 实例。AbstractReferenceCountedByteBuf 的子类实现可太多了。重要的有
  - `PooledByteBuf`: 抽象类，它是拥有池化能力的 ByteBuf 的骨架，内部定义了许多关于池化相关的类和变量。这个后续会详细讲解。
  - `UnpooledHeapByteBuf`: 实现类，非池化堆内内存ByteBuf。内部使用 byte[] array 字节数组存储数据。底层使用 HeapByteBufUtil 静态方法完成对数组的操作。为了提高性能，使用位移方式处理数据，值得好好体会。
  - `CompositeByteBuf`: 实现类，可组合的ByteBuf。底层实现通过内部类 Component 包装 ByteBuf，然后使用 Component[] 存储多个 Component 对象从而实现组合模式。
  - `UnpooledDirectByteBuf`: 实现类，非池化堆外ByteBuf。底层是持有 java.nio.ByteBuffer 对象的引用。
  - `FixedCompositeByteBuf`: 实现类，固定的可组合ByteBuf。允许以只读模式包装 ByteBuf 数组。

## ReferenceCounted

该类在`common/src/main/java/io/netty/util/ReferenceCounted.java`目录下，包名` io.netty.util`。

定义和**引用计数**相关的接口。

```java
package io.netty.util;

/**
 * A reference-counted object that requires explicit deallocation.
 *
 * 一个参考计数的对象，需要明确的去分配。
 * <p>
 * When a new {@link ReferenceCounted} is instantiated, it starts with the reference count of {@code 1}.
 * {@link #retain()} increases the reference count, and {@link #release()} decreases the reference count.
 * If the reference count is decreased to {@code 0}, the object will be deallocated explicitly, and accessing
 * the deallocated object will usually result in an access violation.
 * 
 * 当一个新的{@link ReferenceCounted}被实例化时，它以{@code 1}的引用计数开始。
 * {@link #retain()}增加引用计数，{@link #release()}减少引用计数。
 * 如果引用计数减少到｛@code 0｝，则对象将被显式释放，访问被释放的对象通常会导致访问冲突。
 * </p>
 * <p>
 * If an object that implements {@link ReferenceCounted} is a container of other objects that implement
 * {@link ReferenceCounted}, the contained objects will also be released via {@link #release()} when the container's
 * reference count becomes 0.
 * 
 * 如果实现{@link ReferenceCounted}的对象是实现{@link ReferenceCounted}的其他对象的容器，
 * 则当容器的引用计数变为0时，所包含的对象也将通过{@link #release()}释放。
 * 
 * </p>
 */
public interface ReferenceCounted {
    /**
     * Returns the reference count of this object.  If {@code 0}, it means this object has been deallocated.
     * 返回此对象的引用计数。如果｛@code 0｝，则表示此对象已被解除分配。
     */
    int refCnt();

    /**
     * Increases the reference count by {@code 1}.
     * 将引用计数增加｛@code 1｝。
     */
    ReferenceCounted retain();

    /**
     * Increases the reference count by the specified {@code increment}.
     * 将引用计数增加指定的{@code增量}。
     */
    ReferenceCounted retain(int increment);

    /**
     * Records the current access location of this object for debugging purposes.
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you
     * via {@link ResourceLeakDetector}.  This method is a shortcut to {@link #touch(Object) touch(null)}.
     * 
     * 记录此对象的当前访问位置，以便进行调试。
     * 如果确定此对象被泄露，则此操作记录的信息将通过{@link ResourceLeakDetector}提供给您。
     * 此方法是｛@link touch（Object）touch（null）｝的快捷方式。
     */
    ReferenceCounted touch();

    /**
     * Records the current access location of this object with an additional arbitrary information for debugging
     * purposes.  If this object is determined to be leaked, the information recorded by this operation will be
     * provided to you via {@link ResourceLeakDetector}.
     * 
     * 记录此对象的当前访问位置以及用于调试的附加任意信息。
     * 如果确定此对象被泄露，则此操作记录的信息将通过{@link ResourceLeakDetector}提供给您。
     */
    ReferenceCounted touch(Object hint);

    /**
     * Decreases the reference count by {@code 1} and deallocates this object if the reference count reaches at
     * {@code 0}.
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     *  
     * 将引用计数减少｛@code 1｝，如果引用计数达到｛@code0｝，
     * 当且仅当引用计数变为｛@code 0｝并且此对象已被释放
     */
    boolean release();

    /**
     * Decreases the reference count by the specified {@code decrement} and deallocates this object if the reference
     * count reaches at {@code 0}.
     * 将引用计数减少指定的{@code decrement}，如果引用计数达到{@code 0}，则释放此对象。
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     * 当且仅当引用计数变为｛@code0｝并且此对象已被释放
     */
    boolean release(int decrement);
}
```

## ByteBuf

接收数据的容器就是 `ByteBuf`，它是 Netty 实现高性能网络框架的重要的一环。并非 `java.nio.ByteBuffer` 不能直接使用，但是它编程相对复杂且功能比较弱鸡，而 ByteBuf 拥有丰富的 API 而且简单易用。

**官方注释介绍**：

​		一个由零个或多个字节（八位数）组成的随机和连续的可访问序列。这个接口为一个或多个原始字节数组（byte[]）和NIO缓冲区提供一个抽象视图。

**创建一个缓冲区**

- 建议使用Unpooled中的辅助方法创建一个新的缓冲区，而不是调用单个实现的构造函数。

**随机访问索引**
- 就像一个普通的原始字节数组一样，ByteBuf使用基于零的索引。这意味着第一个字节的索引总是0，最后一个字节的索引总是容量-1。 例如，要遍历一个缓冲区的所有字节，你可以做以下事情，不管它的内部实现如何：

- ```java
  ByteBuf buffer = ...;
    for (int i = 0; i < buffer.capacity(); i ++) {
        byte b = buffer.getByte(i);
        System.out.println((char) b);
    }
  ```

**顺序访问索引**

- ByteBuf提供了两个指针变量来支持顺序的读和写操作--分别为读操作的readerIndex和写操作的writerIndex。下图显示了一个缓冲区是如何被这两个指针分割成三个区域的：

- ```
         +-------------------+------------------+------------------+
         | discardable bytes |  readable bytes  |  writable bytes  |
         |                   |     (CONTENT)    |                  |
         +-------------------+------------------+------------------+
         |                   |                  |                  |
         0      <=      readerIndex   <=   writerIndex    <=    capacity
  ```

**可读字节**（实际内容）

- 这一段是存储实际数据的地方。任何名称以read或skip开头的操作都会在当前的readerIndex处获取或跳过数据，并通过读取的字节数来增加它。如果读取操作的参数也是一个ByteBuf，并且没有指定目标索引，那么指定的缓冲区的writerIndex会一起增加。

- 如果没有足够的剩余内容，就会引发IndexOutOfBoundsException。新分配的、包裹的或复制的缓冲区的readerIndex的默认值是0。

- ```java
    // Iterates the readable bytes of a buffer.
    ByteBuf buffer = ...;
    while (buffer.isReadable()) {
        System.out.println(buffer.readByte());
    }
  ```

**可写字节**

- 此段是一个未定义的空间，需要被填充。任何名字以写开始的操作都会在当前的writerIndex处写下数据，并以写下的字节数增加。如果写操作的参数也是一个ByteBuf，并且没有指定源索引，那么指定缓冲区的readerIndex也会一起增加。

- 如果没有足够的可写字节，会引发IndexOutOfBoundsException。新分配的缓冲区的writerIndex的默认值是0，被包裹或复制的缓冲区的writerIndex的默认值是缓冲区的容量。

- ```java
  // Fills the writable bytes of a buffer with random integers.
    ByteBuf buffer = ...;
    while (buffer.maxWritableBytes() >= 4) {
        buffer.writeInt(random.nextInt());
    }
  ```

**可丢弃的字节**

- 该段包含已经被读操作读过的字节。最初，这个段的大小为0，但随着读操作的执行，它的大小会增加到writerIndex。已读的字节可以通过调用discardReadBytes()来丢弃，以回收未使用的区域，如下图所示：

- ```
     BEFORE discardReadBytes()
      
         +-------------------+------------------+------------------+
         | discardable bytes |  readable bytes  |  writable bytes  |
         +-------------------+------------------+------------------+
         |                   |                  |                  |
         0      <=      readerIndex   <=   writerIndex    <=    capacity
      
     AFTER discardReadBytes()
      
         +------------------+--------------------------------------+
         |  readable bytes  |    writable bytes (got more space)   |
         +------------------+--------------------------------------+
         |                  |                                      |
    readerIndex (0) <= writerIndex (decreased)        <=        capacity
  ```

- 请注意，在调用discardReadBytes()后，对可写字节的内容没有任何保证。在大多数情况下，可写字节不会被移动，甚至可能被填入完全不同的数据，这取决于底层缓冲区的实现。

**清除缓冲区的索引**
- 你可以通过调用clear()将readerIndex和writerIndex都设置为0。它不会清除缓冲区的内容（例如用0填充），而只是清除这两个指针。还请注意，这个操作的语义与ByteBuffer.clear()不同。

- ```
  BEFORE clear()
   
         +-------------------+------------------+------------------+
         | discardable bytes |  readable bytes  |  writable bytes  |
         +-------------------+------------------+------------------+
         |                   |                  |                  |
         0      <=      readerIndex   <=   writerIndex    <=    capacity
   
     AFTER clear()
   
         +---------------------------------------------------------+
         |             writable bytes (got more space)             |
         +---------------------------------------------------------+
         |                                                         |
         0 = readerIndex = writerIndex            <=            capacity
  ```

**搜索操作**

- 对于简单的单字节搜索，使用indexOf(int, int, byte)和bytesBefore(int, int, byte)。bytesBefore(byte)在你处理NUL结尾的字符串时特别有用。对于复杂的搜索，使用forEachByte(int, int, ByteProcessor)与ByteProcessor实现。

**标记和重置**

- 在每个缓冲区中都有两个标记索引。一个是用于存储readerIndex，另一个是用于存储writerIndex。你总是可以通过调用重置方法来重新定位这两个索引中的一个。它的工作方式与InputStream中的mark和reset方法类似，只是没有readlimit。

**派生缓冲区**

- 你可以通过调用以下方法之一来创建一个现有缓冲区的视图：

  - duplicate()
  - slice()
  - slice(int, int)
  - readSlice(int)
  - retainedDuplicate()
  - retainedSlice()
  - retainedSlice(int, int)
  - readRetainedSlice(int)

一个派生缓冲区将有一个独立的readerIndex、writerIndex和marker索引，同时它共享其他的内部数据表示，就像一个NIO缓冲区那样。

如果需要对现有的缓冲区进行全新的复制，请调用copy()方法来代替。

**非保留的和保留的派生缓冲区**

注意 duplicate(), slice(), slice(int, int) 和 readSlice(int) 不会对返回的派生缓冲区调用 retain() ，因此它的引用计数不会增加。如果你需要创建一个引用次数增加的派生缓冲区，可以考虑使用 retainedDuplicate(), retainedSlice(), retainedSlice(int, int) 和 readRetainedSlice(int) ，它们可能返回一个产生较少垃圾的缓冲区实现。

**转换为现有的JDK类型**

- **字节数组**
  - 如果一个ByteBuf是由一个字节数组（即byte[]）支持的，你可以通过array()方法直接访问它。要确定一个缓冲区是否由一个字节数组支持，应该使用hasArray()。

- **NIO缓冲区**

  - 如果一个ByteBuf可以转换为一个NIO ByteBuffer，共享其内容（即视图缓冲区），你可以通过nioBuffer()方法获得它。要确定一个缓冲区是否可以转换为NIO缓冲区，请使用nioBufferCount()。

- **字符串**

  - 各种toString(Charset)方法将ByteBuf转换成字符串。请注意，toString()不是一个转换方法。

- **I/O流**

  - 请参考ByteBufInputStream和ByteBufOutputStream。

**和 java.nio.Buffer 设计相比：**

- Netty 的 ByteBuf 是将所有的操作（API）都集成在一起，比如 getBoolean() 、getByte()、getLong() 等等。

- 而 Java.nio.Buffer 则通过多个类达到解耦的目的。比如对于基本类型 Byte ，首先使用 java.nio.ByteBuffer 对象继承 Buffer，然后再定义与 Byte 相关的抽象方法给子类实现。至于哪种好，那就见仁见智了，Java 使用了良好的设计模式解耦各个 Buffer 的功能，但是也存在的大量的类。而 Netty 则是更为紧凑，使用 ByteBuf 来统一其他数据类型（比如 int、long），可能也是觉得后者都是以 Byte 字节为最小单元组合而成的。

**部分源码**

```java
// 立即「丢弃」所有已读数据（需要做数据拷贝，将未读内容复制到最前面）
// 即便只有一个字节剩余可写，也执行「丢弃动作」
public abstract ByteBuf discardReadBytes();

// 会判断 readerIndex 指针是否超过了 capacity的一半
// 如果超过了就执行「丢弃」动作
// 这个方法相比 discardReadBytes() 智能一点
public abstract ByteBuf discardSomeReadBytes();

// 确保 minWritableBytes 字节数可写
// 如果容量不够的话，会触发「扩容」动作
// 扩容后的容量范围[64Byte, 4MB]
public abstract ByteBuf ensureWritable(int minWritableBytes);

// 返回一个int类型的值
// 0: 当前ByteBuf有足够可写容量，capacity保持不变
// 1: 当前ByteBuf没有足够可写容量，capacity保持不变
// 2: 当前ByteBuf有足够的可写容量，capacity增加
// 3: 当前ByteBuf没有足够的可写容量，但capacity已增长到最大值
public abstract int ensureWritable(int minWritableBytes, boolean force);

/**
 * 通过set/get方法还是需要将底层数据看成一个个由byte组成的数组，
 * 索引值是根据基本类型长度而增长的。
 * set/get 并不会改变readerIndex和writerIndex的值，
 * 你可以理解为对某个位进行更改操作
 * 至于大端小端，这个根据特定需求选择的。现阶段的我对这个理解不是特别深刻
 */
public abstract int   getInt(int index);
public abstract int   getIntLE(int index);

 * 方法getBytes(int, ByteBuf, int, int)也能实现同样的功能。
 * 两者的区别是:
 * 	   「当前方法」会增加目标Buffer对象的「writerIndex」的值，
 *     getBytes(int, ByteBuf, int, int)方法不会更改。

/**
 * 从指定的绝对索引处开始，将此缓冲区的数据传输到指定的目标Buffer对象，直到目标对象变为不可写。
 * 
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 增加「writerIndex」 
 * 
 * @param index  索引值
 * @param dst    目标对象
 * @return       源对象
 */
public abstract ByteBuf getBytes(int index, ByteBuf dst);

/**
 * 从指定的绝对索引处开始，将此缓冲区的数据传输到指定的目标Buffer对象，传输长度为length
 * 方法getBytes(int, ByteBuf, int, int)也能实现同样的功能。
 * 
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 增加「writerIndex」 
 * @param index  索引值
 * @param dst    目标对象
 * @param length 拷贝长度
 * @return       源对象
 */
public abstract ByteBuf getBytes(int index, ByteBuf dst, int length);

/**
 * 把数据拷贝到目标数组中
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 无
 */
public abstract ByteBuf getBytes(int index, byte[] dst);

/**
 * 把数据拷贝到目标数组中
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 无
 */
public abstract ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length);

/**
 * 把数据拷贝到目标数组中
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 增加「writerIndex」
 */
public abstract ByteBuf getBytes(int index, ByteBuffer dst);

/**
 * 把数据拷贝到目标对象
 * 以上关于将数据复制给ByteBuf对象的方法最终还是调用此方法进行数据复制
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 都不修改
 */
public abstract ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length);

/**
 * 把数据拷贝到目标流中
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 无
 */
public abstract ByteBuf getBytes(int index, OutputStream out, int length) throws IOException;

/**
 * 把数据拷贝到指定通道
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 无
 */
public abstract int getBytes(int index, GatheringByteChannel out, int length) throws IOException;

/**
 * 把数据拷贝到指定通道，不会修改通道的「position」
 *
 * 「writerIndex」 「readerIndex」
 *   数据源: 都不修改
 * 目标对象: 无
 */
public abstract int getBytes(int index, FileChannel out, long position, int length) throws IOException;

/**
 * 把对象src的 「可读数据(writerIndex-readerIndex)」 拷贝到this.ByteBuf对象中
 * 剩下的参数凡是带有ByteBuf对象的，都和这个处理逻辑类似。
 * 但是setBytes(int index, ByteBuf src, int srcIndex, int length)这个方法就有点与众不同
 * 这个方法都不会修改这两个指针变量的值。
 * 
 * 「writerIndex」 「readerIndex」
 *    src: 增加「readerIndex」的值
 *   this: 都不修改
 */
public abstract ByteBuf setBytes(int index, ByteBuf src);
public abstract ByteBuf setBytes(int index, ByteBuf src, int length);
public abstract ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length);
public abstract ByteBuf setBytes(int index, byte[] src);
public abstract ByteBuf setBytes(int index, byte[] src, int srcIndex, int length);
public abstract ByteBuf setBytes(int index, ByteBuffer src);
public abstract int setBytes(int index, InputStream in, int length) throws IOException;
public abstract int setBytes(int index, ScatteringByteChannel in, int length) throws IOException;
public abstract int setBytes(int index, FileChannel in, long position, int length) throws IOException;
// 使用 NUL(0x00)填充
public abstract ByteBuf setZero(int index, int length);

/**
 * 以下是read操作
 * readerIndex 会按照对应类型增长。
 * 比如readByte()对应readerIndex+1，readShort()对应readerIndex+2
 */
public abstract byte  readByte();
public abstract short readShort();
public abstract short readShortLE();
public abstract int   readUnsignedShort();
public abstract int   readUnsignedShortLE();
public abstract int   readMedium();

/**
 * 从当前的 readerIndex 开始，将这个缓冲区的数据传输到一个新创建的缓冲区，
 * 并通过传输的字节数(length)增加 readerIndex。
 * 返回的缓冲区的 readerIndex 和 writerIndex 分别为0 和 length。
 *
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf readBytes(int length);

/**
 * 返回一个新的ByteBuf对象。它是一个包装对象，里面有一个指向源Buffer的引用。
 * 该对象只是一个视图，只不过有几个指针独立源Buffer
 * 但是readerIndex(0)和writerIndex(=length)的值是初始的。
 * 另外，需要注意的是当前方法并不会调用 retain()去增加引用计数
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf readSlice(int length);
public abstract ByteBuf readRetainedSlice(int length);


/**
 * 读取数据到 dst，直到不可读为止。
 *
 * 「writerIndex」 「readerIndex」
 *    dst: 增加「writerIndex」的值
 *   this: 增加「readerIndex」
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf readBytes(ByteBuf dst);
public abstract ByteBuf readBytes(ByteBuf dst, int length);

/**
 * 读取数据到 dst，直到不可读为止。
 *
 * 「writerIndex」 「readerIndex」
 *    dst: 都不修改
 *   this: 都不修改
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf readBytes(ByteBuf dst, int dstIndex, int length);

public abstract CharSequence readCharSequence(int length, Charset charset);
public abstract int readBytes(FileChannel out, long position, int length) throws IOException;
public abstract ByteBuf skipBytes(int length);


/**
 * 写入下标为 writerIndex 指向的内存。
 * 如果容量不够，会尝试扩容
 *
 * 「writerIndex」 「readerIndex」
 *    dst: 无
 *   this: 「writerIndex」 + 1
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf writeByte(int value);

/**
 * 写入下标为 writerIndex 指向的内存。
 * 如果容量不够，会尝试扩容
 *
 * 「writerIndex」 「readerIndex」
 *    dst: 无
 *   this: 「writerIndex」 + 1
 * @return 一个新创建的ByteBuf对象
 */
public abstract ByteBuf writeBytes(ByteBuf src);
public abstract ByteBuf writeBytes(ByteBuf src, int length);
public abstract ByteBuf writeBytes(ByteBuf src, int srcIndex, int length);
public abstract ByteBuf writeBytes(byte[] src);
public abstract ByteBuf writeBytes(byte[] src, int srcIndex, int length);
public abstract ByteBuf writeBytes(ByteBuffer src);
public abstract int writeBytes(FileChannel in, long position, int length) throws IOException;
public abstract ByteBuf writeZero(int length);
public abstract int writeCharSequence(CharSequence sequence, Charset charset);

/**
 * 从「fromIndex」到「toIndex」查找value并返回索引值
 * @return 首次出现的位置索引，-1表示未找到
 */
public abstract int indexOf(int fromIndex, int toIndex, byte value);

/**
 * 定位此缓冲区中指定值的第一个匹配项。
 * 搜索范围[readerIndex, writerIndex)。
 * 
 * @return -1表示未找到
 */
public abstract int bytesBefore(byte value);

/**
 * 搜索范围[readerIndex，readerIndex + length)
 *
 * @return -1表示未找到
 *
 * @throws IndexOutOfBoundsException
 */
public abstract int bytesBefore(int length, byte value);

/**
 * 搜索范围[index, idnex+length)
 *
 * @return -1表示未找到
 *
 * @throws IndexOutOfBoundsException
 */
public abstract int bytesBefore(int index, int length, byte value);

/**
 * 使用指定的处理器按升序迭代该缓冲区的「可读字节」
 *
 * @return -1表示未找到; 如果ByteProcessor.process(byte)返回false，则返回上次访问的索引值
 */
public abstract int forEachByte(ByteProcessor processor);

/**
 * 迭代范围[index, index+length-1)
 */
public abstract int forEachByte(int index, int length, ByteProcessor processor);
public abstract int forEachByteDesc(ByteProcessor processor);
public abstract int forEachByteDesc(int index, int length, ByteProcessor processor);

/**
 * 返回此缓冲区可读字节的副本。两个ByteBuf内容独立。
 * 类似 buf.copy(buf.readerIndex(), buf.readableBytes());
 * 源ByteBuf的指针都不会被修改
 */
public abstract ByteBuf copy();
public abstract ByteBuf copy(int index, int length);

/**
 * 返回该缓冲区可读字节的一个片段。
 * 修改返回的缓冲区或这个缓冲区的内容会影响彼此的内容，同时它们维护单独的索引和标记。
 * 此方法与 buf.slice (buf.readerIndex () ，buf.readableBytes ()相同。
 * 此方法不修改此缓冲区的 readerIndex 或 writerIndex。
 */
public abstract ByteBuf slice();

/**
 * 与 slice().retain() 行为一样
 */
public abstract ByteBuf retainedSlice();
public abstract ByteBuf slice(int index, int length);
public abstract ByteBuf retainedSlice(int index, int length);

/**
 * 内容共享。各自维护独立的索引的标记。
 * 新的ByteBuf的可读内容是和slice()方法返回的一样。但是由于共享底层的ByteBuf对象，
 * 所以底层的所有内容都是可见的。
 * read和write标志并不是复制的。同时也需要注意此方法并不会调用retain()给引用计数+1
 */
public abstract ByteBuf duplicate();
public abstract ByteBuf retainedDuplicate();

/**
 * 返回组成这个缓冲区的 NIO bytebuffer 的最大数目。一般默认是1，对于组合的ByteBuf则计算总和。
 * 
 * @return -1 表示底层没有ByteBuf
 * @see #nioBuffers(int, int)
 */
public abstract int nioBufferCount();

/**
 * 将该缓冲区的可读字节作为 NIO ByteBuffer 公开。共享内容。
 * buf.nioBuffer(buf.readerIndex(), buf.readableBytes()) 结果一样。
 * 请注意，如果这个缓冲区是一个动态缓冲区并调整了其容量，那么返回的NIO缓冲区将不会看到这些变化
 */
public abstract ByteBuffer nioBuffer();
public abstract ByteBuffer nioBuffer(int index, int length);

/**
 * 仅内部使用: 公开内部 NIO 缓冲区。
 */
public abstract ByteBuffer internalNioBuffer(int index, int length);
public abstract ByteBuffer[] nioBuffers();
public abstract ByteBuffer[] nioBuffers(int index, int length);

/**
 * 如果当前ByteBuf拥有支持数据则返回true
 */
public abstract boolean hasArray();
public abstract byte[] array();

/**
 * 返回此缓冲区的支撑字节数组中第一个字节的偏移量。
 */
public abstract int arrayOffset();

/**
 * 当且仅当此缓冲区具有指向「backing data」的低级内存地址的引用时才返回true
 */
public abstract boolean hasMemoryAddress();
public abstract long memoryAddress();

/**
 * 如果此 ByteBuf 内部为单个内存区域则返回true。复合类型的缓冲区必须返回false，即使只包含一个ByteBuf对象。
 */
public boolean isContiguous() {
    return false;
}

public abstract String toString(Charset charset);

public abstract String toString(int index, int length, Charset charset);

@Override
public abstract int hashCode();

@Override
public abstract boolean equals(Object obj);

@Override
public abstract int compareTo(ByteBuf buffer);

@Override
public abstract String toString();

@Override
public abstract ByteBuf retain(int increment);

@Override
public abstract ByteBuf retain();

@Override
public abstract ByteBuf touch();

@Override
public abstract ByteBuf touch(Object hint);

boolean isAccessible() {
    return refCnt() != 0;
}

```

- getXX() 从源 Buffer 复制数据到目标 Buffer。可能会修改目标 Buffer 的 writerIndex。
- setXX() 将目标 Buffer 中的数据复制到源 Buffer。可能会修改目标 Buffer 的 readerIndex。
- readXX() 表示从 Buffer 中读取数据，会根据基本类型增长源 Buffer 的 readerIndex。
- get 和 set 都是相对于 this 而言，比如 this.getXX() 意味着获取 this.buffer 的信息并复制到目标ByteBuf对象中。而 this.setXX() 表示从目标ByteBuf对象中复制数据到 this.buffer。
