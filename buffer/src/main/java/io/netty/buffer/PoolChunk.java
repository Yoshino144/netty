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
// https://blog.csdn.net/ClarenceZero/article/details/113607481?spm=1001.2014.3001.5502
import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PoolChunk中的PageRun/PoolSubpage分配算法描述
 *
 * 提示： 以下术语对理解代码很重要
 * > page - 页是可以分配的最小的内存块单位。
 * > run - 一个运行是一个页的集合
 * > chunk - 一个chunk是一个运行的集合
 * > 在这段代码中 chunkSize = maxPages * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * 首先，我们分配一个大小=chunkSize的字节数组。
 * 每当需要创建一个给定大小的ByteBuf时，我们就在字节数组中搜索第一个位置，
 * 该位置有足够的空位来容纳所要求的大小，并且 返回一个编码这个偏移信息的（长）handle，
 * （这个内存段然后被标记为保留，所以它总是被使用。
 * 这个内存段被标记为保留，所以它总是被一个ByteBuf使用，而不是更多）
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 *
 * 为了简单起见，所有的大小都按照{@link PoolArena#size2SizeIdx(int)}方法归一化。
 * 这确保了当我们请求的内存段尺寸大于pageSize时，标准化的Capacity
 * 等于{@link SizeClasses}中下一个最接近的大小。
 *
 *
 *  一个块具有以下布局：
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 * 一个handle是一个长数字，一个运行的位布局看起来像：
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit
 * s: size (number of pages) of this run, 15bit
 * u: isUsed?, 1bit
 * e: isSubpage?, 1bit
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit  子页的bitmapIdx，如果不是子页则为零，32位
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * 一个管理所有运行（已使用和未使用）的map。
 * 对于每个run，第一个runOffset和最后一个runOffset被存储在runsAvailMap中。
 * key: runOffset
 * value: handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
*
 * 一个{@link PriorityQueue}的数组。
 * 每个队列都管理着相同大小的运行。
 * 运行是按偏移量排序的，所以我们总是分配给偏移量较小的运行。
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *   当我们分配运行时，我们会更新存储在runningAvailMap和runningAvail中的值，这样就可以保持该属性。
 *
 * Initialization - 初始化
 *  In the beginning we store the initial run which is the whole chunk.
 *  在开始的时候，我们存储初始运行，也就是整块的内容。
 *  The initial run:
 *  最初的运行：
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 *
 * 1) 根据大小，在runningAvails中找到第一个可用的运行。
 * 2) 如果运行的页数大于请求的页数，则将其分割，并将尾部的运行保存起来，以便以后使用。
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * 1) 根据大小找到一个未满的子页。
 *    如果它已经存在就返回，否则分配一个新的PoolSubpage并调用init()。
 *    注意，当我们init()时，这个subpage对象会被添加到PoolArena的subpagesPool中。
 * 2) 调用subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 * 1）如果它是一个子页，则将板块返回到这个子页中。
 * 2) 如果该子页没有被使用，或者它是一个运行，那么就开始释放这个运行
 * 3) 合并连续使用的运行
 * 4) 保存合并后的运行
 *
 */
//TODO  内存
final class PoolChunk<T> implements PoolChunkMetric {
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    // 32
    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;

    // 1+32=33
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    final PoolArena<T> arena;
    final Object base;
    final T memory;
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     * 储存每一次运行的第一页和最后一页
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     * 管理所有可用的运行
     */
    private final LongPriorityQueue[] runsAvail;

    private final ReentrantLock runsAvailLock;

    /**
     * manage all subpages in this chunk
     */
    private final PoolSubpage<T>[] subpages;

    /**
     * Accounting of pinned memory – memory that is currently in use by ByteBuf instances.
     */
    private final LongCounter pinnedBytes = PlatformDependent.newLongCounter();

    private final int pageSize;
    private final int pageShifts;
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        freeBytes = chunkSize;

        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        runsAvailLock = new ReentrantLock();
        runsAvailMap = new LongLongHashMap(-1);
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        long initHandle = (long) pages << SIZE_SHIFT;
        insertAvailRun(0, pages, initHandle);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        runsAvailLock = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        return queueArray;
    }

    /**
     * 更新 {@link PoolChunk#runsAvail} 和 {@link PoolChunk#runsAvailMap} 数据结构
     * @param runOffset 偏移量
     * @param pages     页数量
     * @param handle    句柄值
     */
    private void insertAvailRun(int runOffset, int pages, long handle) {

        // 将句柄信息写入对应的小顶堆
        // 根据页数量向下取整，获得「pageIdxFloor」，这个值即将写入对应runsAvail数组索引的值
        int pageIdxFloor = arena.pages2pageIdxFloor(pages); // pages2pageIdxFloor 将请求大小规范化为最接近的pageSize
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        // 将handle元素插入到LongPriorityQueue中
        queue.offer(handle);

        // 将首页和末页的偏移量和句柄值记录在runsAvailMap对象，待合并run时使用
        //insert first page of run 插入运行的第一页
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            // insert last page of run  插入运行的最后一页
            // 当页数量超过1时才会记录末页的偏移量和句柄值
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    // 记录末页的偏移量和句柄值 到 LongLongHashMap runsAvailMap
    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }


    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        runsAvail[pageIdxFloor].remove(handle);
        removeAvailRun0(handle);
    }

    private void removeAvailRun0(long handle) {
        int runOffset = runOffset(handle);
        int pages = runPages(handle);
        //remove first page of run
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 内存分配。可以完成Small&Normal两种级别的内存分配
     * @param buf           ByteBuf承载对象
     * @param reqCapacity    用户所需真实的内存大小
     * @param sizeIdx       内存大小对应{@link SizeClasses} 数组的索引值
     * @param cache         本地线程缓存
     * @return              {code true}: 内存分配成功，否则内存分配失败
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        // long型的handle表示分配成功的内存块的句柄值，它与jemalloc3所表示的含义不一下
        final long handle;

        // 当sizeIdx<=38（38是默认值）时，表示当前分配的内存规格是Small
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            // small 分配Small规格内存块
            handle = allocateSubpage(sizeIdx);
            if (handle < 0) {
                return false;
            }
            assert isSubpage(handle);
        } else {
            // normal  分配Normal级别内存块，runSize是pageSize的整数倍
            // runSize must be multiple of pageSize
            int runSize = arena.sizeIdx2size(sizeIdx);
            handle = allocateRun(runSize);
            if (handle < 0) {
                return false;
            }
            assert !isSubpage(handle);
        }
        // 尝试从cachedNioBuffers缓存中获取ByteBuffer对象并在ByteBuf对象初始化时使用
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;

        // 初始化ByteBuf对象
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);

        // return
        return true;
    }

    /**
     * 分配若干page
     *
     * @param runSize 规格值，该值是pageSize的整数倍
     * @return
     */
    private long allocateRun(int runSize) {

        // 根据规格值计算所需「page」的数量，这个数量值很重要:
        // 我们需要通过page数量获得搜索「LongPriorityQueue」的起始位置，并不是盲目从头开始向下搜索，
        // 这样会导致性能较差，而是在一合理的范围内找到第一个且合适的run
        int pages = runSize >> pageShifts;

        // 根据page的数量确定page的起始索引，索引值对应「runsAvail[]」数组起始位置
        // 这里就是前面所说的向上取整，从拥有多一点的空闲页的run中分配准是没错的选择
        int pageIdx = arena.pages2pageIdx(pages);

        // runsAvail 属于并发变量，需要加锁
        runsAvailLock.lock();
        try {
            // find first queue which has at least one big enough run
            // 找到第一个至少有一个足够大的运行的队列
            // 从「LongPriorityQueue[]」数组中找到最合适的run用于当前的内存分配请求。
            // 起始位置为「pageIdx」，并向后遍历直到数组的末尾或找到合适的run
            // 如果没有找到，返回-1
            int queueIdx = runFirstBestFit(pageIdx);
            if (queueIdx == -1) {
                return -1;
            }

            // get run with min offset in this queue
            // 在这个队列中获得最小偏移量的运行
            // 获取「LongPriorityQueue」，该对象包含若干个可用的 run
            LongPriorityQueue queue = runsAvail[queueIdx];

            // 从「LongPriorityQueue」小顶堆中获取可用的 run（由handle表示）
            // 小顶堆能保证始终保持从低地址开始分配
            long handle = queue.poll();
            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            // 先将「handle」从该小顶堆中移除，因为我们有可能需要对它进行修改
            removeAvailRun0(handle);

            if (handle != -1) {
                // 可能会把「run」拆分成两部分。为什么说可能呢?因为这个run可能刚好满足此次分配需求，所以不用拆分。
                // 一部分用于当前内存申请。
                // 另一部分则剩余空闲内存块，这一部分则会放到合适的LongPriorityQueue数组中，待下次分配时使用。
                // 返回的 handle 表示当前内存申请的句柄信息
                handle = splitLargeRun(handle, pages);
            }

            // 更新剩余空间值
            int pinnedSize = runSize(pageShifts, handle);
            freeBytes -= pinnedSize;
            //返回成功申请的句柄信息
            return handle;
        } finally {
            runsAvailLock.unlock();
        }
    }

    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        return runSize;
    }

    // 从pageIdx开始搜索最合适的run用于内存分配
    // 从pageIdx开始搜索 runsAvail 小顶堆数组寻找最合适的 run 用于此次内存分配。
    // 它很简单，从 pageIdx 不断遍历寻找即可。
    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            return arena.nPSizes - 1;
        }

        // 比较简单，从pageIdx向后遍历，找到queue!=null且不为空的LongPriorityQueue
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把「run」拆分成合适的两部分（如果这个run可能刚好满足此次分配需求，不用拆分，修改handle信息后直接返回）
     * @param handle       run的句柄变更
     * @param needPages    所需要page的数量
     * @return             用于当前分配申请的句柄值
     */
    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        // 获取run管理的空闲的page数量
        int totalPages = runPages(handle);
        assert needPages <= totalPages;

        // 计算剩余数量（总数-需要数量）
        int remPages = totalPages - needPages;

        // 如果还有剩余，需要重新生成run（由handle具象化）并写入两个重要的数据结构中
        // 一个是 LongLongHashMap runsAvailMap，另一个是 LongPriorityQueue[] runsAvail;
        if (remPages > 0) {
            // 获取偏移量
            int runOffset = runOffset(handle);

            // keep track of trailing unused pages for later use
            // 追踪未使用的尾部页面以便日后使用
            // 剩余空闲页偏移量=旧的偏移量+分配页数
            int availOffset = runOffset + needPages;

            // 根据偏移量、页数量以及isUsed状态生成新的句柄变量，这个变量表示一个全新未使用的run
            long availRun = toRunHandle(availOffset, remPages, 0);

            // 更新两个重要的数据结构
            insertAvailRun(availOffset, remPages, availRun);

            // not avail  没有利用
            // 生成用于此次分配的句柄变量
            return toRunHandle(runOffset, needPages, 1);
        }

        // mark it as used 标示为使用
        // 把handle的isUsed标志位置为1
        // 这段代码是将一个long类型的handle值的第34位（下标从0开始）设置为1。
        //根据代码中的常量定义，IS_USED_SHIFT的值为33，即要设置的位在long类型的二进制表示中的下标为33。
        // 该代码使用了位运算符“<<”将1左移33个二进制位，然后使用按位或运算符“|=”将1与handle的第33位进行按位或操作，
        // 实现将第34位设置为1的效果。
        handle |= 1L << IS_USED_SHIFT; //IS_USED_SHIFT为33
        // 返回
        return handle;
    }

    /**
     * 创建/初始化一个新的「PoolSubpage」对象。
     * 任何新创建的「PoolSubpage」对象都会被添加到相应的subpagePool中
     *
     * @param sizeIdx  对应{@link SizeClasses} 索引值
     * @return         句柄值
     */
    private long allocateSubpage(int sizeIdx) {
        // 获取PoolArena所拥有的PoolSubPage池的头部，并对其进行同步。
        // 这是有必要的，因为我们可能会把它加回来，从而改变链接列表的结构。

        // 从「PoolArena」获取索引对应的「PoolSubpage」。
        // 在「SizeClasses」中划分为 Small 级别的一共有 39 个，
        // 所以在 PoolArena#smallSubpagePools数组长度也为39，数组索引与sizeIdx一一对应
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

        // PoolSubpage 链表是共享变量，需要加锁
        head.lock();
        try {
            // 分配一个新的 run
            // 获取拆分规格值和pageSize的最小公倍数
            int runSize = calculateRunSize(sizeIdx);

            // 申请若干个page，runSize是pageSize的整数倍
            long runHandle = allocateRun(runSize);
            if (runHandle < 0) {
                // 分配失败
                return -1;
            }

            // 实例化「PoolSubpage」对象
            int runOffset = runOffset(runHandle);
            assert subpages[runOffset] == null;
            int elemSize = arena.sizeIdx2size(sizeIdx);

            // PoolSubpage类是用于表示内存池的一个小块，它可以被切分成多个小的子块来分配给不同的对象。
            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            // 由PoolChunk记录新创建的PoolSubpage，数组索引值是首页的偏移量，这个值是唯一的，也是记录在句柄值中
            // 因此，在归还内存时会通过句柄值找到对应的PoolSubpge对象
            subpages[runOffset] = subpage;

            // 委托PoolSubpage分配内存
            return subpage.allocate();
        } finally {
            head.unlock();
        }
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        int runSize = runSize(pageShifts, handle);
        if (isSubpage(handle)) {
            int sizeIdx = arena.size2SizeIdx(normCapacity);
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            head.lock();
            try {
                assert subpage != null && subpage.doNotDestroy;
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                subpages[sIdx] = null;
            } finally {
                head.unlock();
            }
        }

        //start free run
        runsAvailLock.lock();
        try {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            long finalRun = collapseRuns(handle);

            //set run as not used
            finalRun &= ~(1L << IS_USED_SHIFT);
            //if it is a subpage, set it to run
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            freeBytes += runSize;
        } finally {
            runsAvailLock.unlock();
        }

        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isSubpage(handle)) {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        } else {
            int maxLength = runSize(pageShifts, handle);
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                    reqCapacity, maxLength, arena.parent.threadCache());
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.doNotDestroy;
        assert reqCapacity <= s.elemSize : reqCapacity + "<=" + s.elemSize;

        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    void incrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(delta);
    }

    void decrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(-delta);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        if (this.unpooled) {
            return freeBytes;
        }
        runsAvailLock.lock();
        try {
            return freeBytes;
        } finally {
            runsAvailLock.unlock();
        }
    }

    public int pinnedBytes() {
        return (int) pinnedBytes.value();
    }

    @Override
    public String toString() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
