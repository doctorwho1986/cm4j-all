package com.cm4j.test.guava.consist;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ajexperience.utils.DeepCopyException;
import com.ajexperience.utils.DeepCopyUtil;
import com.cm4j.dao.hibernate.HibernateDao;
import com.cm4j.test.guava.consist.entity.IEntity;
import com.cm4j.test.guava.consist.loader.CacheDescriptor;
import com.cm4j.test.guava.consist.loader.CacheLoader;
import com.cm4j.test.guava.consist.loader.CacheValueLoader;
import com.cm4j.test.guava.consist.loader.PrefixMappping;
import com.google.common.base.Preconditions;
import com.google.common.collect.AbstractSequentialIterator;

/**
 * <pre>
 * 读写分离：
 * 读取：{@link ConcurrentCache}提供get or load、put、expire操作
 * 
 * 写入：是由{@link CacheEntry#changeDbState(DBState)}控制对象状态
 * 同时{@link PersistCache}独立维护了一份写入队列，独立于缓存操作
 * 
 * 使用流程：
 * 1.定义缓存描述信息{@link CacheDescriptor}
 * 2.定义映射{@link PrefixMappping}
 * </pre>
 * 
 * @author Yang.hao
 * @since 2013-1-30 上午11:25:47
 * 
 * @param <String>
 * @param <AbsReference>
 */
public class ConcurrentCache {

	private static final ConcurrentCache instance = new ConcurrentCache(new CacheValueLoader());

	public static ConcurrentCache getInstance() {
		return instance;
	}

	/* ---------------- Constants -------------- */
	static final int DEFAULT_INITIAL_CAPACITY = 16;
	static final float DEFAULT_LOAD_FACTOR = 0.75f;
	static final int DEFAULT_CONCURRENCY_LEVEL = 1; // TODO 默认设为16
	static final int MAXIMUM_CAPACITY = 1 << 30;
	static final int MAX_SEGMENTS = 1 << 16; // slightly conservative
	static final int RETRIES_BEFORE_LOCK = 2;
	/** 更新队列检测间隔，单位s */
	static final int UPDATE_QUEUE_CHECK_INTERVAL = 60;
	/** 达到多少个对象，可持久化 */
	static final int MAX_UNITS_IN_UPDATE_QUEUE = 50000;
	/** 间隔多少次检查，可持久化 */
	static final int PERSIST_CHECK_INTERVAL = 5;

	/* ---------------- Fields -------------- */
	final CacheLoader<String, AbsReference> loader;
	final int segmentMask;
	final int segmentShift;
	final Segment[] segments;
	// TODO 默认过期纳秒，完成时需更改为较长时间过期
	final long expireAfterAccessNanos = TimeUnit.SECONDS.toNanos(300);

	private final ConcurrentLinkedQueue<CacheEntryInUpdateQueue> updateQueue;
	private final ScheduledExecutorService service;
	private final Logger logger = LoggerFactory.getLogger(getClass());

	/* ---------------- Small Utilities -------------- */
	private static int rehash(int h) {
		h += (h << 15) ^ 0xffffcd7d;
		h ^= (h >>> 10);
		h += (h << 3);
		h ^= (h >>> 6);
		h += (h << 2) + (h << 14);
		return h ^ (h >>> 16);
	}

	final Segment segmentFor(int hash) {
		return segments[(hash >>> segmentShift) & segmentMask];
	}

	/* ---------------- Inner Classes -------------- */
	private static class HashEntry implements ReferenceEntry {
		final String key;
		final int hash;
		volatile AbsReference value;
		final HashEntry next;

		@Override
		public String getKey() {
			return key;
		}

		@Override
		public AbsReference getValue() {
			return value;
		}

		@Override
		public int getHash() {
			return hash;
		}

		@Override
		public ReferenceEntry getNext() {
			return next;
		}

		HashEntry(String key, int hash, HashEntry next, AbsReference value) {
			this.key = key;
			this.hash = hash;
			this.next = next;
			this.value = value;
		}

		static final AtomicReferenceArray<HashEntry> newArray(int i) {
			return new AtomicReferenceArray<HashEntry>(i);
		}

		volatile long accessTime = Long.MAX_VALUE;

		@Override
		public long getAccessTime() {
			return accessTime;
		}

		@Override
		public void setAccessTime(long time) {
			this.accessTime = time;
		}

		ReferenceEntry nextAccess = nullEntry();

		@Override
		public ReferenceEntry getNextInAccessQueue() {
			return nextAccess;
		}

		@Override
		public void setNextInAccessQueue(ReferenceEntry next) {
			this.nextAccess = next;
		}

		ReferenceEntry previousAccess = nullEntry();

		@Override
		public ReferenceEntry getPreviousInAccessQueue() {
			return previousAccess;
		}

		@Override
		public void setPreviousInAccessQueue(ReferenceEntry previous) {
			this.previousAccess = previous;
		}
	}

	static final class Segment extends ReentrantLock implements Serializable {
		private static final long serialVersionUID = 2249069246763182397L;
		// 作为位操作的mask，必须是(2^n)-1
		static final int DRAIN_THRESHOLD = 0x3F;
		final ConcurrentCache map;

		transient volatile int count;
		transient int modCount;
		transient int threshold;
		transient volatile AtomicReferenceArray<HashEntry> table;
		final float loadFactor;

		final AccessQueue accessQueue = new AccessQueue();
		final AtomicInteger readCount = new AtomicInteger();

		Segment(ConcurrentCache map, int initialCapacity, float lf) {
			this.map = map;
			loadFactor = lf;
			setTable(HashEntry.newArray(initialCapacity));
		}

		static final Segment[] newArray(int i) {
			return new Segment[i];
		}

		void setTable(AtomicReferenceArray<HashEntry> newTable) {
			threshold = (int) (newTable.length() * loadFactor);
			table = newTable;
		}

		HashEntry getFirst(int hash) {
			AtomicReferenceArray<HashEntry> tab = table;
			return tab.get(hash & (tab.length() - 1));
		}

		HashEntry getEntry(String key, int hash) {
			for (HashEntry e = getFirst(hash); e != null; e = e.next) {
				if (e.hash == hash && key.equals(e.key)) {
					return e;
				}
			}
			return null;
		}

		AbsReference getLiveValue(String key, int hash, long now) {
			HashEntry e = getLiveEntry(key, hash, now);
			if (e != null) {
				return e.getValue();
			}
			return null;
		}

		HashEntry getLiveEntry(String key, int hash, long now) {
			HashEntry e = getEntry(key, hash);
			if (e == null) {
				return null;
			} else if (map.isExpired(e, now)) {
				// 如果状态不是P，则会延迟生命周期
				tryExpireEntries(now);
				// 非精准查询，如果延长生命周期，这里依然返回null，get()调用时需在有锁情况下做二次检测
				if (e.getValue().isAllPersist()) {
					return null;
				}
			}
			return e;
		}

		AbsReference get(String key, int hash, CacheLoader<String, AbsReference> loader, boolean isLoad) {
			try {
				if (count != 0) { // read-volatile
					HashEntry e = getEntry(key, hash);
					if (e != null) {
						// 这里只是一次无锁情况的快速尝试查询，如果未查询到，会在有锁情况下再查一次
						AbsReference value = getLiveValue(key, hash, now());
						if (value != null) {
							recordAccess(e);
							return value;
						}
					}
				}
				if (isLoad) {
					// at this point e is either null or expired;
					return lockedGetOrLoad(key, hash, loader);
				}
			} finally {
				postReadCleanup();
			}
			return null;
		}

		AbsReference lockedGetOrLoad(String key, int hash, CacheLoader<String, AbsReference> loader) {
			lock();
			try {
				// 有锁情况下重读entry的值
				// recheck,防止2个线程同时load，都从db获取数据，导致加载了2份数据
				AbsReference value = getLiveValue(key, hash, now());
				if (value == null) {
					value = loader.load(key);
				}

				if (value != null) {
					put(key, hash, value, false);
				}
				return value;
			} finally {
				unlock();
			}
		}

		boolean containsKey(String key, int hash) {
			if (count != 0) { // read-volatile
				long now = now();

				lock();
				try {
					HashEntry e = getLiveEntry(key, hash, now);
					return e != null;
				} finally {
					unlock();
				}
			}
			return false;
		}

		AbsReference put(String key, int hash, AbsReference value, boolean onlyIfAbsent) {
			lock();
			try {
				preWriteCleanup(now());

				int c = count;
				if (c++ > threshold) { // ensure capacity
					rehash();
				}
				AtomicReferenceArray<HashEntry> tab = table;
				int index = hash & (tab.length() - 1);
				HashEntry first = tab.get(index);
				HashEntry e = first;
				while (e != null && (e.hash != hash || !key.equals(e.key)))
					e = e.next;

				AbsReference oldValue;
				if (e != null) {
					oldValue = e.value;
					if (!onlyIfAbsent) {
						e.value = value;
						recordAccess(e);
					}
				} else {
					oldValue = null;
					++modCount;
					e = new HashEntry(key, hash, first, value);
					recordAccess(e);
					tab.set(index, e);
					count = c; // write-volatile
				}

				// 在put的时候对value设置所属key
				value.setAttachedKey(key);

				// 返回旧值
				return oldValue;
			} finally {
				unlock();
				postWriteCleanup();
			}
		}

		void rehash() {
			AtomicReferenceArray<HashEntry> oldTable = table;
			int oldCapacity = oldTable.length();
			if (oldCapacity >= MAXIMUM_CAPACITY)
				return;

			AtomicReferenceArray<HashEntry> newTable = HashEntry.newArray(oldCapacity << 1);
			threshold = (int) (newTable.length() * loadFactor);
			int sizeMask = newTable.length() - 1;
			for (int i = 0; i < oldCapacity; i++) {
				// We need to guarantee that any existing reads of old Map can
				// proceed. So we cannot yet null out each bin.
				HashEntry e = oldTable.get(i);

				if (e != null) {
					HashEntry next = e.next;
					int idx = e.hash & sizeMask;

					// Single node on list
					if (next == null)
						newTable.set(idx, e);

					else {
						// Reuse trailing consecutive sequence at same slot
						HashEntry lastRun = e;
						int lastIdx = idx;
						for (HashEntry last = next; last != null; last = last.next) {
							int k = last.hash & sizeMask;
							if (k != lastIdx) {
								lastIdx = k;
								lastRun = last;
							}
						}
						newTable.set(lastIdx, lastRun);

						// Clone all remaining nodes
						for (HashEntry p = e; p != lastRun; p = p.next) {
							int k = p.hash & sizeMask;
							HashEntry n = newTable.get(k);
							newTable.set(k, new HashEntry(p.key, p.hash, n, p.value));
						}
					}
				}
			}
			table = newTable;
		}

		/**
		 * Remove; match on key only if value null, else match both.
		 */
		AbsReference remove(String key, int hash, AbsReference value) {
			lock();
			try {
				preWriteCleanup(now());

				int c = count - 1;
				AtomicReferenceArray<HashEntry> tab = table;
				int index = hash & (tab.length() - 1);
				HashEntry first = tab.get(index);
				HashEntry e = first;
				while (e != null && (e.hash != hash || !key.equals(e.key)))
					e = e.next;

				AbsReference oldValue = null;
				if (e != null) {
					AbsReference v = e.value;
					if (value == null || value.equals(v)) {
						oldValue = v;
						// All entries following removed node can stay
						// in list, but all preceding ones need to be
						// cloned.
						++modCount;
						HashEntry newFirst = e.next;
						for (HashEntry p = first; p != e; p = p.next)
							newFirst = new HashEntry(p.key, p.hash, newFirst, p.value);
						tab.set(index, newFirst);

						// 从队列移除
						accessQueue.remove(e);
						count = c; // write-volatile
					}
				}
				return oldValue;
			} finally {
				unlock();
				postWriteCleanup();
			}
		}

		void clear() {
			if (count != 0) {
				lock();
				try {
					AtomicReferenceArray<HashEntry> tab = table;
					for (int i = 0; i < tab.length(); i++)
						tab.set(i, null);

					accessQueue.clear();
					++modCount;
					count = 0; // write-volatile
				} finally {
					unlock();
				}
			}
		}

		<R> R doInSegmentUnderLock(String key, int hash, SegmentLockHandler<R> handler) {
			lock();
			preWriteCleanup(now());
			try {
				HashEntry e = getFirst(hash);
				while (e != null && (e.hash != hash || !key.equals(e.key)))
					e = e.next;

				return handler.doInSegmentUnderLock(this, e);
			} finally {
				unlock();
				postWriteCleanup();
			}
		}

		// expiration，过期相关业务
		/**
		 * Cleanup expired entries when the lock is available.
		 */
		void tryExpireEntries(long now) {
			if (tryLock()) {
				try {
					expireEntries(now);
				} finally {
					unlock();
					// don't call postWriteCleanup as we're in a read
				}
			}
		}

		// 调用方都有锁
		void expireEntries(long now) {
			ReferenceEntry e;
			while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
				if (e.getValue().isAllPersist()) {
					removeEntry((HashEntry) e, e.getHash());
				} else {
					recordAccess(e);
				}
			}
		}

		void removeEntry(HashEntry e, int hash) {
			int c = count - 1;
			AtomicReferenceArray<HashEntry> tab = table;
			int index = hash & (tab.length() - 1);
			HashEntry first = tab.get(index);

			++modCount;
			HashEntry newFirst = e.next;
			for (HashEntry p = first; p != e; p = p.next) {
				newFirst = new HashEntry(p.key, p.hash, newFirst, p.value);
			}
			tab.set(index, newFirst);
			// 从队列移除
			accessQueue.remove(e);
			count = c; // write-volatile
		}

		/**
		 * Performs routine cleanup following a read. Normally cleanup happens
		 * during writes. If cleanup is not observed after a sufficient number
		 * of reads, try cleaning up from the read thread.
		 */
		void postReadCleanup() {
			if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
				cleanUp();
			}
		}

		/**
		 * Performs routine cleanup prior to executing a write. This should be
		 * called every time a write thread acquires the segment lock,
		 * immediately after acquiring the lock.
		 * 
		 * <p>
		 * Post-condition: expireEntries has been run.
		 */
		void preWriteCleanup(long now) {
			runLockedCleanup(now);
		}

		/**
		 * Performs routine cleanup following a write.
		 */
		void postWriteCleanup() {
			runUnlockedCleanup();
		}

		void cleanUp() {
			runLockedCleanup(now());
			runUnlockedCleanup();
		}

		void runLockedCleanup(long now) {
			if (tryLock()) {
				try {
					// 过期，且移除
					expireEntries(now); // calls drainRecencyQueue
				} finally {
					unlock();
				}
			}
		}

		void runUnlockedCleanup() {
			// REMOVE 通知。。。
			if (!isHeldByCurrentThread()) {
				// map.processPendingNotifications();
			}
		}

		/**
		 * 1.记录访问时间<br>
		 * 2.增加到访问对列的尾部
		 */
		void recordAccess(ReferenceEntry e) {
			e.setAccessTime(now());
			accessQueue.add(e);
		}

	}

	/* ---------------- Public operations -------------- */

	public ConcurrentCache(CacheLoader<String, AbsReference> loader) {
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL, loader);
	}

	public ConcurrentCache(int initialCapacity, float loadFactor, int concurrencyLevel,
			CacheLoader<String, AbsReference> loader) {
		if (!(loadFactor > 0) || initialCapacity < 0 || concurrencyLevel <= 0 || loader == null)
			throw new IllegalArgumentException();

		this.loader = loader;

		if (concurrencyLevel > MAX_SEGMENTS)
			concurrencyLevel = MAX_SEGMENTS;

		// Find power-of-two sizes best matching arguments
		int sshift = 0;
		int ssize = 1;
		while (ssize < concurrencyLevel) {
			++sshift;
			ssize <<= 1;
		}
		segmentShift = 32 - sshift;
		segmentMask = ssize - 1;
		this.segments = Segment.newArray(ssize);

		if (initialCapacity > MAXIMUM_CAPACITY)
			initialCapacity = MAXIMUM_CAPACITY;
		int c = initialCapacity / ssize;
		if (c * ssize < initialCapacity)
			++c;
		int cap = 1;
		while (cap < c)
			cap <<= 1;

		for (int i = 0; i < this.segments.length; ++i) {
			this.segments[i] = new Segment(this, cap, loadFactor);
		}

		// 更新队列
		updateQueue = new ConcurrentLinkedQueue<CacheEntryInUpdateQueue>();

		// 定时处理器
		service = Executors.newScheduledThreadPool(1);
		service.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				consumeUpdateQueue(false);
			}
		}, 1, UPDATE_QUEUE_CHECK_INTERVAL, TimeUnit.SECONDS);
	}

	public int size() {
		final Segment[] segments = this.segments;
		long sum = 0;
		long check = 0;
		int[] mc = new int[segments.length];
		// Try a few times to get accurate count. On failure due to
		// continuous async changes in table, resort to locking.
		for (int k = 0; k < RETRIES_BEFORE_LOCK; ++k) {
			check = 0;
			sum = 0;
			int mcsum = 0;
			for (int i = 0; i < segments.length; ++i) {
				sum += segments[i].count;
				mcsum += mc[i] = segments[i].modCount;
			}
			if (mcsum != 0) {
				for (int i = 0; i < segments.length; ++i) {
					check += segments[i].count;
					if (mc[i] != segments[i].modCount) {
						check = -1; // force retry
						break;
					}
				}
			}
			if (check == sum)
				break;
		}
		if (check != sum) { // Resort to locking all segments
			sum = 0;
			for (int i = 0; i < segments.length; ++i)
				segments[i].lock();
			for (int i = 0; i < segments.length; ++i)
				sum += segments[i].count;
			for (int i = 0; i < segments.length; ++i)
				segments[i].unlock();
		}
		if (sum > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;
		else
			return (int) sum;
	}

	@SuppressWarnings("unchecked")
	public <V extends AbsReference> V get(CacheDescriptor<V> desc) {
		String key = desc.getKey();
		int hash = rehash(key.hashCode());
		return (V) segmentFor(hash).get(key, hash, loader, true);
	}

	/**
	 * 存在即获取
	 * 
	 * @return 不存在时返回的reference为null
	 */
	@SuppressWarnings("unchecked")
	public <V extends AbsReference> V getIfPresent(CacheDescriptor<V> desc) {
		String key = desc.getKey();
		int hash = rehash(key.hashCode());
		return (V) segmentFor(hash).get(key, hash, loader, false);
	}

	@SuppressWarnings("unchecked")
	public <V extends AbsReference> V put(CacheDescriptor<V> desc, AbsReference value) {
		Preconditions.checkArgument(!stop.get(), "缓存已关闭，无法写入缓存");
		Preconditions.checkNotNull(value);

		String key = desc.getKey();
		int hash = rehash(key.hashCode());
		return (V) segmentFor(hash).put(key, hash, value, false);
	}

	/**
	 * 不存在时放入缓存
	 */
	@SuppressWarnings("unchecked")
	public <V extends AbsReference> V putIfAbsent(CacheDescriptor<V> desc, AbsReference value) {
		Preconditions.checkArgument(!stop.get(), "缓存已关闭，无法写入缓存");
		Preconditions.checkNotNull(value);

		String key = desc.getKey();
		int hash = rehash(key.hashCode());
		return (V) segmentFor(hash).put(key, hash, value, true);
	}

	/**
	 * 先持久化再移除
	 * 
	 * @param desc
	 * @param isRemove
	 *            是否移除
	 */
	public void persistAndRemove(CacheDescriptor<? extends AbsReference> desc, boolean isRemove) {
		persistAndRemove(desc.getKey(), isRemove);
	}

	/**
	 * 持久化
	 * 
	 * @param key
	 * @param isRemove
	 *            是否移除
	 */
	public void persistAndRemove(String key, final boolean isRemove) {
		Preconditions.checkNotNull(key, "key不能为null");
		Preconditions.checkArgument(!stop.get(), "缓存已关闭，无法写入缓存");

		final int hash = rehash(key.hashCode());
		segmentFor(hash).doInSegmentUnderLock(key, hash, new SegmentLockHandler<Void>() {
			@Override
			public Void doInSegmentUnderLock(Segment segment, HashEntry e) {
				if (e != null && e.value != null && !e.value.isAllPersist()) {
					e.value.persistDB();
					if (isRemove) {
						segment.removeEntry(e, hash);
					}
				}
				return null;
			}
		});
	}

	public boolean replace(String key, final AbsReference oldValue, final AbsReference newValue) {
		if (oldValue == null || newValue == null)
			throw new NullPointerException();
		int hash = rehash(key.hashCode());
		return segmentFor(hash).doInSegmentUnderLock(key, hash, new SegmentLockHandler<Boolean>() {
			@Override
			public Boolean doInSegmentUnderLock(Segment segment, HashEntry e) {
				boolean replaced = false;
				if (e != null && oldValue.equals(e.value)) {
					replaced = true;
					e.value = newValue;
				}
				return replaced;
			}
		});
	}

	public AbsReference remove(String key) {
		int hash = rehash(key.hashCode());
		return segmentFor(hash).remove(key, hash, null);
	}

	public boolean remove(String key, AbsReference value) {
		int hash = rehash(key.hashCode());
		if (value == null)
			return false;
		return segmentFor(hash).remove(key, hash, value) != null;
	}

	public void remove(CacheDescriptor<? extends AbsReference> desc) {
		remove(desc.getKey());
	}

	public void clear() {
		for (int i = 0; i < segments.length; ++i)
			segments[i].clear();
	}

	public boolean containsKey(String key) {
		int hash = rehash(key.hashCode());
		return segmentFor(hash).containsKey(key, hash);
	}

	public boolean contains(CacheDescriptor<? extends AbsReference> cacheDesc) {
		return containsKey(cacheDesc.getKey());
	}

	public boolean isEmpty() {
		final Segment[] segments = this.segments;
		int[] mc = new int[segments.length];
		int mcsum = 0;
		for (int i = 0; i < segments.length; ++i) {
			if (segments[i].count != 0)
				return false;
			else
				mcsum += mc[i] = segments[i].modCount;
		}
		if (mcsum != 0) {
			for (int i = 0; i < segments.length; ++i) {
				if (segments[i].count != 0 || mc[i] != segments[i].modCount)
					return false;
			}
		}
		return true;
	}

	/**
	 * 在没有修改的时候，即entry.getNumInUpdateQueue().get() == 0时调用
	 * 
	 * @param entry
	 * @return 是否成功修改
	 */
	boolean changeDbStatePersist(final CacheEntry entry) {
		Preconditions.checkNotNull(entry.getAttachedKey(), "CacheEntry中attachedKey不允许为null");

		final String key = entry.getAttachedKey();
		int hash = rehash(key.hashCode());
		return segmentFor(hash).doInSegmentUnderLock(key, hash, new SegmentLockHandler<Boolean>() {
			@Override
			public Boolean doInSegmentUnderLock(Segment segment, HashEntry e) {
				if (e != null && e.value != null && !isExpired(e, now())) {
					// recheck，不等于0代表有其他线程修改了，所以不能改为P状态
					if (entry.getNumInUpdateQueue().get() != 0) {
						return false;
					}
					if (e.value.changeDbState(entry, DBState.P)) {
						return true;
					}
				}
				// 不存在或过期
				throw new RuntimeException("缓存中不存在此对象[" + key + "]，无法更改状态");
			}
		});
	}

	/**
	 * 更改db状态并发送到更新队列，缓存不应直接调用此方法
	 * 
	 * @param entry
	 * @param dbState
	 *            U or D,不允许P
	 */
	void changeDbState(final CacheEntry entry, final DBState dbState) {
		Preconditions.checkArgument(!stop.get(), "缓存已关闭，无法写入缓存");

		Preconditions.checkNotNull(entry.getAttachedKey(), "CacheEntry中attachedKey不允许为null");
		Preconditions.checkNotNull(dbState, "DbState不允许为null");
		Preconditions.checkState(DBState.P != dbState, "DbState不允许为持久化");

		final String key = entry.getAttachedKey();
		int hash = rehash(key.hashCode());
		segmentFor(hash).doInSegmentUnderLock(key, hash, new SegmentLockHandler<Void>() {
			@Override
			public Void doInSegmentUnderLock(Segment segment, HashEntry e) {
				if (e != null && e.value != null && !isExpired(e, now())) {
					// 更改CacheEntry的状态
					if (e.value.changeDbState(entry, dbState)) {
						return null;
					}
				}
				throw new RuntimeException("缓存中不存在此对象[" + key + "]，无法更改状态");
			}
		});
	}

	/* ---------------- expiration ---------------- */

	private static long now() {
		return System.nanoTime();
	}

	private boolean isExpired(ReferenceEntry entry, long now) {
		if (now - entry.getAccessTime() > expireAfterAccessNanos) {
			return true;
		}
		return false;
	}

	/* ---------------- Queue -------------- */

	interface ReferenceEntry {
		/*
		 * Used by entries that use access order. Access entries are maintained
		 * in a doubly-linked list. New entries are added at the tail of the
		 * list at write time; stale entries are expired from the head of the
		 * list.
		 * 
		 * 插入到尾部，过期从首部
		 */

		String getKey();

		AbsReference getValue();

		int getHash();

		ReferenceEntry getNext();

		/**
		 * Returns the time that this entry was last accessed, in ns.
		 */
		long getAccessTime();

		/**
		 * Sets the entry access time in ns.
		 */
		void setAccessTime(long time);

		/**
		 * Returns the next entry in the access queue.
		 */
		ReferenceEntry getNextInAccessQueue();

		/**
		 * Sets the next entry in the access queue.
		 */
		void setNextInAccessQueue(ReferenceEntry next);

		/**
		 * Returns the previous entry in the access queue.
		 */
		ReferenceEntry getPreviousInAccessQueue();

		/**
		 * Sets the previous entry in the access queue.
		 */
		void setPreviousInAccessQueue(ReferenceEntry previous);

	}

	private enum NullEntry implements ReferenceEntry {
		INSTANCE;

		@Override
		public String getKey() {
			return null;
		}

		@Override
		public AbsReference getValue() {
			return null;
		}

		@Override
		public ReferenceEntry getNext() {
			return null;
		}

		@Override
		public int getHash() {
			return 0;
		};

		@Override
		public long getAccessTime() {
			return 0;
		}

		@Override
		public void setAccessTime(long time) {
		}

		@Override
		public ReferenceEntry getNextInAccessQueue() {
			return this;
		}

		@Override
		public void setNextInAccessQueue(ReferenceEntry next) {
		}

		@Override
		public ReferenceEntry getPreviousInAccessQueue() {
			return this;
		}

		@Override
		public void setPreviousInAccessQueue(ReferenceEntry previous) {
		}

	}

	// impl never uses a parameter or returns any non-null value
	static ReferenceEntry nullEntry() {
		return (ReferenceEntry) NullEntry.INSTANCE;
	}

	static final class AccessQueue extends AbstractQueue<ReferenceEntry> {
		final ReferenceEntry head = new ReferenceEntry() {

			@Override
			public String getKey() {
				return null;
			}

			@Override
			public AbsReference getValue() {
				return null;
			}

			@Override
			public ReferenceEntry getNext() {
				return nullEntry();
			}

			public int getHash() {
				return 0;
			};

			@Override
			public long getAccessTime() {
				return Long.MAX_VALUE;
			}

			@Override
			public void setAccessTime(long time) {
			}

			ReferenceEntry nextAccess = this;

			@Override
			public ReferenceEntry getNextInAccessQueue() {
				return nextAccess;
			}

			@Override
			public void setNextInAccessQueue(ReferenceEntry next) {
				this.nextAccess = next;
			}

			ReferenceEntry previousAccess = this;

			@Override
			public ReferenceEntry getPreviousInAccessQueue() {
				return previousAccess;
			}

			@Override
			public void setPreviousInAccessQueue(ReferenceEntry previous) {
				this.previousAccess = previous;
			}
		};

		// implements Queue

		@Override
		public boolean offer(ReferenceEntry entry) {
			// unlink
			connectAccessOrder(entry.getPreviousInAccessQueue(), entry.getNextInAccessQueue());

			// add to tail
			connectAccessOrder(head.getPreviousInAccessQueue(), entry);
			connectAccessOrder(entry, head);

			return true;
		}

		@Override
		public ReferenceEntry peek() {
			ReferenceEntry next = head.getNextInAccessQueue();
			return (next == head) ? null : next;
		}

		@Override
		public ReferenceEntry poll() {
			ReferenceEntry next = head.getNextInAccessQueue();
			if (next == head) {
				return null;
			}

			remove(next);
			return next;
		}

		@Override
		public boolean remove(Object o) {
			ReferenceEntry e = (ReferenceEntry) o;
			ReferenceEntry previous = e.getPreviousInAccessQueue();
			ReferenceEntry next = e.getNextInAccessQueue();
			connectAccessOrder(previous, next);
			nullifyAccessOrder(e);

			return next != NullEntry.INSTANCE;
		}

		@Override
		public boolean contains(Object o) {
			ReferenceEntry e = (ReferenceEntry) o;
			return e.getNextInAccessQueue() != NullEntry.INSTANCE;
		}

		@Override
		public boolean isEmpty() {
			return head.getNextInAccessQueue() == head;
		}

		@Override
		public int size() {
			int size = 0;
			for (ReferenceEntry e = head.getNextInAccessQueue(); e != head; e = e.getNextInAccessQueue()) {
				size++;
			}
			return size;
		}

		@Override
		public void clear() {
			ReferenceEntry e = head.getNextInAccessQueue();
			while (e != head) {
				ReferenceEntry next = e.getNextInAccessQueue();
				nullifyAccessOrder(e);
				e = next;
			}

			head.setNextInAccessQueue(head);
			head.setPreviousInAccessQueue(head);
		}

		@Override
		public Iterator<ReferenceEntry> iterator() {
			return new AbstractSequentialIterator<ReferenceEntry>(peek()) {
				@Override
				protected ReferenceEntry computeNext(ReferenceEntry previous) {
					ReferenceEntry next = previous.getNextInAccessQueue();
					return (next == head) ? null : next;
				}
			};
		}
	}

	static void connectAccessOrder(ReferenceEntry previous, ReferenceEntry next) {
		previous.setNextInAccessQueue(next);
		next.setPreviousInAccessQueue(previous);
	}

	static void nullifyAccessOrder(ReferenceEntry nulled) {
		ReferenceEntry nullEntry = nullEntry();
		nulled.setNextInAccessQueue(nullEntry);
		nulled.setPreviousInAccessQueue(nullEntry);
	}

	static interface SegmentLockHandler<R> {
		R doInSegmentUnderLock(Segment segment, HashEntry e);
	}

	/*
	 * ================== utils =====================
	 */

	/**
	 * 发送到更新队列
	 * 
	 * @param entry
	 */
	void sendToUpdateQueue(CacheEntry entry) {
		entry.getNumInUpdateQueue().incrementAndGet();
		updateQueue.add(new CacheEntryInUpdateQueue(entry));
	}

	// 缓存关闭标识
	private final AtomicBoolean stop = new AtomicBoolean();

	/**
	 * 关闭并写入db
	 */
	public void stop() {
		stop.set(true);
		Future<?> future = service.submit(new Runnable() {
			@Override
			public void run() {
				consumeUpdateQueue(true);
			}
		});
		try {
			// 阻塞等待线程完成
			future.get();
		} catch (Exception e) {
			e.printStackTrace();
		}
		service.shutdown();
	}

	/**
	 * 更新队列消费计数器
	 */
	private long counter = 0L;

	/**
	 * 将更新队列发送给db存储<br>
	 * 
	 * @param doNow
	 *            是否立即写入
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void consumeUpdateQueue(boolean doNow) {
		logger.warn("缓存定时存储数据，队列大小：{}", updateQueue.size());
		if (doNow || updateQueue.size() >= MAX_UNITS_IN_UPDATE_QUEUE || (counter++) % PERSIST_CHECK_INTERVAL == 0) {
			CacheEntryInUpdateQueue wrapper = null;
			while ((wrapper = updateQueue.poll()) != null) {
				CacheEntry reference = wrapper.getReference();
				int num = reference.getNumInUpdateQueue().decrementAndGet();
				// 删除或者更新的num为0
				if (num == 0) {
					// TODO 此时对象更改了怎么办？下面把他设成P，然后被删除了？
					IEntity entity = wrapper.getEntity();
					if (entity != null && DBState.P != wrapper.getDbState()) {
						// TODO 发送db去批处理
						logger.debug(reference.getDbState() + " " + entity.toString());

						HibernateDao hibernate = ServiceManager.getInstance().getSpringBean("hibernateDao");
						if (DBState.U == wrapper.getDbState()) {
							hibernate.saveOrUpdate(entity);
						} else if (DBState.D == wrapper.getDbState()) {
							hibernate.delete(entity);
						}

						// recheck,有可能又有其他线程更新了对象，此时也不能重置为P
						changeDbStatePersist(wrapper.getReference());
					}
				}
			}
		}
	}

	/**
	 * 读取计数器:引用原对象<br>
	 * 读取写入对象：本类中dbState<br>
	 * 写入数据：本类中copied
	 */
	private static final class CacheEntryInUpdateQueue {
		private final CacheEntry reference;
		private final DBState dbState;
		private final IEntity entity;

		private CacheEntryInUpdateQueue(CacheEntry reference) {
			this.reference = reference;
			this.dbState = reference.getDbState();

			IEntity entity = reference.parseEntity();
			if (reference instanceof IEntity && ((IEntity) reference != entity)) {
				// 内存地址不同，创建了新对象
				this.entity = entity;
			} else {
				// 其他情况，深拷贝
				try {
					this.entity = new DeepCopyUtil().deepCopy(reference.parseEntity());
				} catch (DeepCopyException e) {
					throw new RuntimeException("CacheEntry[" + reference.getAttachedKey() + "]不能被deepCopy", e);
				}
			}
		}

		/**
		 * 引用原对象
		 */
		public CacheEntry getReference() {
			return reference;
		}

		/**
		 * 数据，是CacheEntry的数据备份
		 */
		public IEntity getEntity() {
			return entity;
		}

		public DBState getDbState() {
			return dbState;
		}
	}
}
