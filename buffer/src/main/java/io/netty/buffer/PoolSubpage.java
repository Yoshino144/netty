/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;
import static io.netty.buffer.SizeClasses.LOG2_QUANTUM;

// PoolSubpage类是用于表示内存池的一个小块，它可以被切分成多个小的子块来分配给不同的对象。
// 其中，pageShifts和runSize是PoolSubpage类中的两个重要属性。
final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk; //当前分配内存的chunk
    final int elemSize; //切分后每段大小
    private final int pageShifts; // pageShifts是一个常量，表示每个内存块的大小为2的pageShifts次方字节。
    // 它通常用于通过位运算操作快速计算出内存块的偏移量（即内存地址）。
    private final int runOffset; // 当前page在chunk的memorymap中的下标id

    private final int runSize;/** runSize是指当前PoolSubpage中可供分配的最大连续内存块的大小，也就是所谓的“连续子段”。
    当PoolSubpage中的内存块被分配出去一部分后，剩余的内存块可能是不连续的，此时就需要重新计算runSize，
     以确保能够正确地分配和回收内存块，并且避免内存碎片的问题。 */

    private final long[] bitmap; //poolSubpage每段内存的占用状态，采用二进制位来标识

    PoolSubpage<T> prev;  //指向前一个PoolSubpage
    PoolSubpage<T> next;  //指向后一个PoolSubpage

    boolean doNotDestroy;   // 表示该page在使用中，不能被清除
    private int maxNumElems; //段的总数
    private int bitmapLength; // bitmap需要用到的长度
    private int nextAvail;  //下一个可用位置
    private int numAvail;  // 可用的段的数量

    private final ReentrantLock lock = new ReentrantLock();

    // TODO: 测试添加填充是否有助于解决争用问题
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** 创建链表头的特殊构造函数 */
    PoolSubpage() {
        chunk = null;  // 当前分配内存的chunk
        pageShifts = -1;  // 当前page在chunk的memorymap中的下标id
        runOffset = -1;  // 当前page在chunk的memorymap中的下标id
        elemSize = -1;  // 切分后每段大小
        runSize = -1;
        bitmap = null; // poolSubpage每段内存的占用状态，采用二进制位来标识
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.chunk = chunk;
        this.pageShifts = pageShifts;
        this.runOffset = runOffset;
        this.runSize = runSize;
        this.elemSize = elemSize;
        // 这里为什么是16,64两个数字呢，elemSize是经过normCapacity处理的数字，最小值为16；
        // 所以一个page最多可能被分成pageSize/16段内存，而一个long可以表示64个内存段的状态；
        // 因此最多需要pageSize/16/64个元素就能保证所有段的状态都可以管理
        bitmap = new long[runSize >>> 6 + LOG2_QUANTUM]; // runSize / 64 / QUANTUM

        doNotDestroy = true;
        if (elemSize != 0) {
            maxNumElems = numAvail = runSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * 返回子页面分配的位图索引。
     */
    // 分配一个可用的element并标记
    long allocate() {
        // 没有可用的内存或者已经被销毁
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
        // 找到当前page中分配的段的index
        final int bitmapIdx = getNextAvail();
        if (bitmapIdx < 0) {
            // 子页面似乎处于无效状态。删除以防止重复出现错误。
            removeFromPool(); // Subpage appear to be in an invalid state. Remove to prevent repeated errors.
            // 断言错误
            throw new AssertionError("No next available bitmap index found (bitmapIdx = " + bitmapIdx + "), " +
                    "even though there are supposed to be (numAvail = " + numAvail + ") " +
                    "out of (maxNumElems = " + maxNumElems + ") available indexes.");
            /**
             * throw new AssertionError("找不到下一个可用的位图索引 (bitmapIdx = " + bitmapIdx + "), " +
             *                     "即使应该有 (numAvail = " + numAvail + ") " +
             *                     "即使从 (maxNumElems = " + maxNumElems + ")里 可用索引.");
             */
        }
        // 算出对应index的标志位在数组中的位置q
        int q = bitmapIdx >>> 6;
        // 将>=64的那一部分二进制抹掉得到一个小于64的数
        int r = bitmapIdx & 63;
        //确认该位没有被占用
        assert (bitmap[q] >>> r & 1) == 0;
        //将该位设置为1，表示已占用，此处1L<<r 表示将r位设为1
        bitmap[q] |= 1L << r;
        // 如果当前page没有可用的内存则从arena的pool中移除
        if (-- numAvail == 0) {
            removeFromPool();
        }
        // 把当前page的索引和poolSubPage的索引一起返回
        // 低32位表示page的index，高32位表示PoolSubPage的index
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use. 如果正在使用此子页面。
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *         如果这个子页面没有被它的区块使用，因此它可以被释放
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        // BitSet，其实就是bitSet.set(q, false)
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        // 将这个index设置为可用, 下次分配时会直接分配这个位置的内存
        setNextAvail(bitmapIdx);

        // numAvail=0说明之前已经从arena的pool中移除了，现在变回可用，则再次交给arena管理
        if (numAvail ++ == 0) {
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1.
             *
             * 当maxNumElems==1时，最大numAvail也是1。
             * 当这些PoolSubpage进行免费操作时，它们中的每一个都将进入此处。
             * 如果它们直接从这里返回true，那么代码的其余部分将无法访问，并且它们实际上不会被回收。
             * 因此，仅在maxNumElems>1时返回true。
             * */
            if (maxNumElems > 1) {
                return true;
            }
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            //子页面未使用 (numAvail == maxNumElems)
            // 注意这里的特殊处理，如果arena的pool中没有可用的subpage，则保留，否则将其从pool中移除。
            // 这样尽可能的保证arena分配小内存时能直接从pool中取，而不用再到chunk中去获取。
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                // 如果此子页面是池中唯一剩下的子页面，请不要删除。
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            // 如果池中还有其他子页，请从池中删除此子页。
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    // chunk在分配page时，如果是8K以下的段则交给subpage管理，然而chunk并没有将subpage暴露给外部，subpage只好自谋生路，
// 在初始化或重新分配时将自己加入到chunk.arena的pool中，通过arena进行后续的管理（包括复用subpage上的其他element，arena目前还没讲到，后面会再提到）
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    /**
     * removeFromPool是PoolChunkList类的一个方法，用于从内存池中移除已经完全空闲且不再被使用的PoolChunk。
     * 当一个PoolChunk中的所有内存块都被分配出去并且全部释放时，这个PoolChunk就变得完全空闲了。
     * 此时，为了避免内存泄漏和浪费，应该将这个PoolChunk从内存池中移除，以便其所占用的内存可以被操作系统回收。
     * removeFromPool方法就是用于执行这个操作的。它会将指定的PoolChunk从相应的PoolChunkList中删除，并且将其所占用的内存归还给操作系统。
     * 同时，如果PoolChunk所属的Arena也已经完全空闲，那么整个Arena也会被释放掉。这样可以最大限度地减少内存的占用，提高系统的性能和稳定性。
     */
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    /**
     * 用于设置下一个可用的内存块的索引，即标记PoolSubpage中哪些内存块已经被分配出去了。
     * 每当有一个内存块被分配出去时，就可以通过setNextAvail方法来更新PoolSubpage的状态，以便下次分配内存块时能够快速找到可用的内存块。
     * 这样可以提高内存分配的效率，并且避免重复的扫描操作。
     * 同时，setNextAvail方法也可以用于PoolSubpage中内存块的回收操作，以便将已经释放的内存块重新标记为可用状态。
     */
     private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 用于获取下一个可用的内存块的索引，即查找PoolSubpage中哪些内存块还没有被分配出去。
     * 每当需要分配一个新的内存块时，就可以通过getNextAvail方法来查找PoolSubpage中尚未被分配的内存块。
     * 如果所有的内存块都已经被分配出去了，那么getNextAvail方法会返回-1，表示当前没有可用的内存块可以分配。
     * 同时，getNextAvail方法也可以用于回收PoolSubpage中已经分配出去的内存块。
     * 具体来说，它可以将指定的内存块的索引标记为可用状态，以便下次分配内存块时可以重新使用这个内存块。
     * 这样可以最大限度地利用内存池中的内存资源，提高内存分配和回收的效率。
     * @return
     */
    private int getNextAvail() {
        // nextAvail>=0时，表示明确的知道这个element未被分配，此时直接返回就可以了
        // >=0 有两种情况：1、刚初始化；2、有element被释放且还未被分配
        // 每次分配完成nextAvail就被置为-1，因为这个时候除非计算一次，否则无法知道下一个可用位置在哪
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    /**
     * 通过遍历所有的内存块来查找下一个可用的内存块的索引。
     * 具体来说，它从nextAvail位置开始逐个检查位图，直到找到一个未被分配的内存块为止。
     * 如果所有的内存块都已经被分配出去了，那么findNextAvail()方法会返回-1，表示当前没有可用的内存块可以分配。
     * @return
     */
    private int findNextAvail() {
        // 没有明确的可用位置时则挨个查找
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 说明这个位置段中还有可以分配的element
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * findNextAvail0()方法与findNextAvail()方法的实现方式基本相同，唯一的区别是在处理特殊情况时使用了不同的策略。
     * 具体来说，在findNextAvail0()方法中，如果从nextAvail位置开始检查位图时，发现该位置的位图已经为0，则会跳过这个位置继续往后检查。
     * 这个特殊的处理方式可以让findNextAvail0()方法更快地找到下一个可用的内存块，从而提高内存分配和回收的效率。
     * @param i
     * @param bits
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            // 如果该位置的值为0，表示还未分配
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            // 这是头，所以根本不需要同步，因为这些永远不会改变。
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            chunk.arena.lock();
            try {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    // 不用于创建字符串。
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            } finally {
                chunk.arena.unlock();
            }
        }

        if (!doNotDestroy) {
            return "(" + runOffset + ": not in use)";
        }

        return "(" + runOffset + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + runSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            // 是头。
            return 0;
        }
        chunk.arena.lock();
        try {
            return maxNumElems;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        chunk.arena.lock();
        try {
            return numAvail;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        chunk.arena.lock();
        try {
            return elemSize;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
