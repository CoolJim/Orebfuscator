package net.imprex.orebfuscator.nms;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.imprex.orebfuscator.config.CacheConfig;
import net.imprex.orebfuscator.util.ChunkPosition;
import net.imprex.orebfuscator.util.SimpleCache;

public abstract class AbstractRegionFileCache<T> {

	protected final ReadWriteLock lock = new ReentrantReadWriteLock(true);
	protected final Map<Path, T> regionFiles;

	protected final CacheConfig cacheConfig;

	public AbstractRegionFileCache(CacheConfig cacheConfig) {
		this.cacheConfig = cacheConfig;

		this.regionFiles = new SimpleCache<>(cacheConfig.maximumOpenRegionFiles(), this::remove);
	}

	protected abstract T createRegionFile(Path path) throws IOException;

	protected abstract void closeRegionFile(T t) throws IOException;

	protected abstract DataInputStream createInputStream(T t, ChunkPosition key) throws IOException;

	protected abstract DataOutputStream createOutputStream(T t, ChunkPosition key) throws IOException;

	public final DataInputStream createInputStream(ChunkPosition key) throws IOException {
		T t = this.get(this.cacheConfig.regionFile(key));
		return t != null ? this.createInputStream(t, key) : null;
	}

	public final DataOutputStream createOutputStream(ChunkPosition key) throws IOException {
		T t = this.get(this.cacheConfig.regionFile(key));
		return t != null ? this.createOutputStream(t, key) : null;
	}

	private final void remove(Map.Entry<Path, T> entry) {
		try {
			this.closeRegionFile(entry.getValue());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected final T get(Path path) throws IOException {
		this.lock.readLock().lock();
		try {
			T t = this.regionFiles.get(path);
			if (t != null) {
				return t;
			}
		} finally {
			this.lock.readLock().unlock();
		}

		if (Files.notExists(path.getParent())) {
			Files.createDirectories(path.getParent());
		}

		if (this.regionFiles.size() > this.cacheConfig.maximumOpenRegionFiles()) {
			throw new IllegalStateException(String.format("RegionFileCache got bigger than expected (%d > %d)",
					this.regionFiles.size(), this.cacheConfig.maximumOpenRegionFiles()));
		}

		T t = Objects.requireNonNull(this.createRegionFile(path));

		this.lock.writeLock().lock();
		try {
			this.regionFiles.putIfAbsent(path, t);
			return this.regionFiles.get(path);
		} finally {
			this.lock.writeLock().unlock();
		}
	}

	public final void close(Path path) throws IOException {
		T t = null;

		this.lock.writeLock().lock();
		try {
			t = this.regionFiles.remove(path);
		} finally {
			this.lock.writeLock().unlock();
		}

		if (t != null) {
			this.closeRegionFile(t);
		}
	}

	public final void clear() {
		this.lock.writeLock().lock();
		try {
			for (T t : this.regionFiles.values()) {
				try {
					if (t != null) {
						this.closeRegionFile(t);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			this.regionFiles.clear();
		} finally {
			this.lock.writeLock().unlock();
		}
	}
}
