import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A custom bounded blocking queue backed by a circular array.
 * Uses ReentrantLock + Conditions instead of java.util.concurrent collection
 * classes.
 *
 * @param <T> element type
 */

public class BoundedBlockingQueue<T> {

	private final Object[] buffer;
	private final int capacity;
	private int head = 0; // next read position
	private int tail = 0; // next write position
	private int count = 0;

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();
	private final Condition notFull = lock.newCondition();

	public BoundedBlockingQueue(int capacity) {
		if (capacity <= 0)
			throw new IllegalArgumentException("capacity must be > 0");
		this.capacity = capacity;
		this.buffer = new Object[capacity];
	}

	/**
	 * Inserts the item if space is available; returns false immediately if full.
	 * Mirrors LinkedBlockingQueue.offer().
	 */
	public boolean offer(T item) {
		lock.lock();
		try {
			if (count == capacity)
				return false;
			enqueue(item);
			return true;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Inserts the item, blocking until space becomes available.
	 * Mirrors LinkedBlockingQueue.put().
	 */
	public void put(T item) throws InterruptedException {
		lock.lock();
		try {
			while (count == capacity)
				notFull.await();
			enqueue(item);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Retrieves and removes the head, blocking until an item is available.
	 * Mirrors LinkedBlockingQueue.take().
	 */
	public T take() throws InterruptedException {
		lock.lock();
		try {
			while (count == 0)
				notEmpty.await();
			return dequeue();
		} finally {
			lock.unlock();
		}
	}

	/** Current number of elements. */
	public int size() {
		lock.lock();
		try {
			return count;
		} finally {
			lock.unlock();
		}
	}

	// ── private helpers ──────────────────────────────────────────────────────

	private void enqueue(T item) {
		buffer[tail] = item;
		tail = (tail + 1) % capacity;
		count++;
		notEmpty.signal();
	}

	@SuppressWarnings("unchecked")
	private T dequeue() {
		T item = (T) buffer[head];
		buffer[head] = null; // allow GC
		head = (head + 1) % capacity;
		count--;
		notFull.signal();
		return item;
	}
}
