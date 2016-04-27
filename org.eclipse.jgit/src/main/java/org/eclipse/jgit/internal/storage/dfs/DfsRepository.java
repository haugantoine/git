/*
 * Copyright (C) 2011, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.internal.storage.dfs;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.attributes.AttributesRule;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.RefList;

/** A Git repository on a DFS. */
public class DfsRepository extends Repository {
	/** Builder for in-memory repositories. */
	static final AtomicInteger packId = new AtomicInteger();

	private final DfsObjDatabase objdb;

	private final RefDatabase refdb;

	private boolean performsAtomicTransactions = true;
	private final DfsConfig config;

	private final DfsRepositoryDescription description;

	/**
	 * Initialize a new in-memory repository.
	 *
	 * @param repoDesc
	 *            description of the repository.
	 * @since 2.0
	 */
	public DfsRepository(DfsRepositoryDescription repoDesc) {
		super();
		this.config = new DfsConfig();
		this.description = repoDesc;
		objdb = new MemObjDatabase(this);
		refdb = new MemRefDatabase();
	}

	@Override
	public DfsObjDatabase getObjectDatabase() {
		return objdb;
	}

	@Override
	public RefDatabase getRefDatabase() {
		return refdb;
	}

	/**
	 * Enable (or disable) the atomic reference transaction support.
	 * <p>
	 * Useful for testing atomic support enabled or disabled.
	 *
	 * @param atomic
	 */
	public void setPerformsAtomicTransactions(boolean atomic) {
		performsAtomicTransactions = atomic;
	}

	/** @return a description of this repository. */
	public DfsRepositoryDescription getDescription() {
		return description;
	}

	/**
	 * Check if the repository already exists.
	 *
	 * @return true if the repository exists; false if it is new.
	 * @throws IOException
	 *             the repository cannot be checked.
	 */
	public boolean exists() throws IOException {
		if (getRefDatabase() instanceof DfsRefDatabase) {
			return ((DfsRefDatabase) getRefDatabase()).exists();
		}
		return true;
	}

	@Override
	public void create() throws IOException {
		if (exists())
			throw new IOException(MessageFormat.format(
					JGitText.get().repositoryAlreadyExists, "")); //$NON-NLS-1$

		String master = Constants.R_HEADS + Constants.MASTER;
		RefUpdate.Result result = updateRef(Constants.HEAD, true).link(master);
		if (result != RefUpdate.Result.NEW)
			throw new IOException(result.name());
	}

	@Override
	public StoredConfig getConfig() {
		return config;
	}

	@Override
	public void scanForRepoChanges() throws IOException {
		getRefDatabase().refresh();
		getObjectDatabase().clearCache();
	}

	@Override
	public void notifyIndexChanged() {
		// Do not send notifications.
		// There is no index, as there is no working tree.
	}

	@Override
	public ReflogReader getReflogReader(String refName) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public AttributesNodeProvider createAttributesNodeProvider() {
		// TODO Check if the implementation used in FileRepository can be used
		// for this kind of repository
		return new EmptyAttributesNodeProvider();
	}

	private static class EmptyAttributesNodeProvider implements
			AttributesNodeProvider {
		private EmptyAttributesNode emptyAttributesNode = new EmptyAttributesNode();

		public AttributesNode getInfoAttributesNode() throws IOException {
			return emptyAttributesNode;
		}

		public AttributesNode getGlobalAttributesNode() throws IOException {
			return emptyAttributesNode;
		}

		private static class EmptyAttributesNode extends AttributesNode {

			public EmptyAttributesNode() {
				super(Collections.<AttributesRule> emptyList());
			}

			@Override
			public void parse(InputStream in) throws IOException {
				// Do nothing
			}
		}
	}

	private class MemObjDatabase extends DfsObjDatabase {
		private List<DfsPackDescription> packs = new ArrayList<DfsPackDescription>();

		MemObjDatabase(DfsRepository repo) {
			super(repo, new DfsReaderOptions());
		}

		@Override
		protected synchronized List<DfsPackDescription> listPacks() {
			return packs;
		}

		@Override
		protected DfsPackDescription newPack(PackSource source) {
			int id = packId.incrementAndGet();
			DfsPackDescription desc = new MemPack(
					"pack-" + id + "-" + source.name(), //$NON-NLS-1$ //$NON-NLS-2$
					getRepository().getDescription());
			return desc.setPackSource(source);
		}

		@Override
		protected synchronized void commitPackImpl(
				Collection<DfsPackDescription> desc,
				Collection<DfsPackDescription> replace) {
			List<DfsPackDescription> n;
			n = new ArrayList<DfsPackDescription>(desc.size() + packs.size());
			n.addAll(desc);
			n.addAll(packs);
			if (replace != null)
				n.removeAll(replace);
			packs = n;
		}

		@Override
		protected void rollbackPack(Collection<DfsPackDescription> desc) {
			// Do nothing. Pack is not recorded until commitPack.
		}

		@Override
		protected ReadableChannel openFile(DfsPackDescription desc, PackExt ext)
				throws FileNotFoundException, IOException {
			MemPack memPack = (MemPack) desc;
			byte[] file = memPack.fileMap.get(ext);
			if (file == null)
				throw new FileNotFoundException(desc.getFileName(ext));
			return new ByteArrayReadableChannel(file);
		}

		@Override
		protected DfsOutputStream writeFile(DfsPackDescription desc,
				final PackExt ext) throws IOException {
			final MemPack memPack = (MemPack) desc;
			return new Out() {
				@Override
				public void flush() {
					memPack.fileMap.put(ext, getData());
				}
			};
		}
	}

	private static class MemPack extends DfsPackDescription {
		final Map<PackExt, byte[]> fileMap = new HashMap<PackExt, byte[]>();

		MemPack(String name, DfsRepositoryDescription repoDesc) {
			super(repoDesc, name);
		}
	}

	private abstract static class Out extends DfsOutputStream {
		private final ByteArrayOutputStream dst = new ByteArrayOutputStream();

		private byte[] data;

		@Override
		public void write(byte[] buf, int off, int len) {
			data = null;
			dst.write(buf, off, len);
		}

		@Override
		public int read(long position, ByteBuffer buf) {
			byte[] d = getData();
			int n = Math.min(buf.remaining(), d.length - (int) position);
			if (n == 0)
				return -1;
			buf.put(d, (int) position, n);
			return n;
		}

		byte[] getData() {
			if (data == null)
				data = dst.toByteArray();
			return data;
		}

		@Override
		public abstract void flush();

		@Override
		public void close() {
			flush();
		}

	}

	private static class ByteArrayReadableChannel implements ReadableChannel {
		private final byte[] data;

		private int position;

		private boolean open = true;

		ByteArrayReadableChannel(byte[] buf) {
			data = buf;
		}

		public int read(ByteBuffer dst) {
			int n = Math.min(dst.remaining(), data.length - position);
			if (n == 0)
				return -1;
			dst.put(data, position, n);
			position += n;
			return n;
		}

		public void close() {
			open = false;
		}

		public boolean isOpen() {
			return open;
		}

		public long position() {
			return position;
		}

		public void position(long newPosition) {
			position = (int) newPosition;
		}

		public long size() {
			return data.length;
		}

		public int blockSize() {
			return 0;
		}

		public void setReadAheadBytes(int b) {
			// Unnecessary on a byte array.
		}
	}

	private class MemRefDatabase extends DfsRefDatabase {
		private final ConcurrentMap<String, Ref> refs = new ConcurrentHashMap<String, Ref>();

		private final ReadWriteLock lock = new ReentrantReadWriteLock(
				true /* fair */);

		MemRefDatabase() {
			super(DfsRepository.this);
		}

		@Override
		public boolean performsAtomicTransactions() {
			return performsAtomicTransactions;
		}

		@Override
		public BatchRefUpdate newBatchUpdate() {
			return new BatchRefUpdate(this) {
				@Override
				public void execute(RevWalk walk, ProgressMonitor monitor)
						throws IOException {
					if (performsAtomicTransactions()) {
						try {
							lock.writeLock().lock();
							batch(walk, getCommands());
						} finally {
							lock.writeLock().unlock();
						}
					} else {
						super.execute(walk, monitor);
					}
				}
			};
		}

		@Override
		protected RefCache scanAllRefs() throws IOException {
			RefList.Builder<Ref> ids = new RefList.Builder<Ref>();
			RefList.Builder<Ref> sym = new RefList.Builder<Ref>();
			try {
				lock.readLock().lock();
				for (Ref ref : refs.values()) {
					if (ref.isSymbolic())
						sym.add(ref);
					ids.add(ref);
				}
			} finally {
				lock.readLock().unlock();
			}
			ids.sort();
			sym.sort();
			return new RefCache(ids.toRefList(), sym.toRefList());
		}

		private void batch(RevWalk walk, List<ReceiveCommand> cmds) {
			// Validate that the target exists in a new RevWalk, as the RevWalk
			// from the RefUpdate might be reading back unflushed objects.
			Map<ObjectId, ObjectId> peeled = new HashMap<>();
			try (RevWalk rw = new RevWalk(getRepository())) {
				for (ReceiveCommand c : cmds) {
					if (c.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) {
						ReceiveCommand.abort(cmds);
						return;
					}

					if (!ObjectId.zeroId().equals(c.getNewId())) {
						try {
							RevObject o = rw.parseAny(c.getNewId());
							if (o instanceof RevTag) {
								peeled.put(o, rw.peel(o).copy());
							}
						} catch (IOException e) {
							c.setResult(
									ReceiveCommand.Result.REJECTED_MISSING_OBJECT);
							ReceiveCommand.abort(cmds);
							return;
						}
					}
				}
			}

			// Check all references conform to expected old value.
			for (ReceiveCommand c : cmds) {
				Ref r = refs.get(c.getRefName());
				if (r == null) {
					if (c.getType() != ReceiveCommand.Type.CREATE) {
						c.setResult(ReceiveCommand.Result.LOCK_FAILURE);
						ReceiveCommand.abort(cmds);
						return;
					}
				} else {
					ObjectId objectId = r.getObjectId();
					if (r.isSymbolic() || objectId == null
							|| !objectId.equals(c.getOldId())) {
						c.setResult(ReceiveCommand.Result.LOCK_FAILURE);
						ReceiveCommand.abort(cmds);
						return;
					}
				}
			}

			// Write references.
			for (ReceiveCommand c : cmds) {
				if (c.getType() == ReceiveCommand.Type.DELETE) {
					refs.remove(c.getRefName());
					c.setResult(ReceiveCommand.Result.OK);
					continue;
				}

				ObjectId p = peeled.get(c.getNewId());
				Ref r;
				if (p != null) {
					r = new ObjectIdRef.PeeledTag(Storage.PACKED,
							c.getRefName(), c.getNewId(), p);
				} else {
					r = new ObjectIdRef.PeeledNonTag(Storage.PACKED,
							c.getRefName(), c.getNewId());
				}
				refs.put(r.getName(), r);
				c.setResult(ReceiveCommand.Result.OK);
			}
			clearCache();
		}

		@Override
		protected boolean compareAndPut(Ref oldRef, Ref newRef)
				throws IOException {
			try {
				lock.writeLock().lock();
				ObjectId id = newRef.getObjectId();
				if (id != null) {
					try (RevWalk rw = new RevWalk(getRepository())) {
						// Validate that the target exists in a new RevWalk, as
						// the RevWalk
						// from the RefUpdate might be reading back unflushed
						// objects.
						rw.parseAny(id);
					}
				}
				String name = newRef.getName();
				if (oldRef == null)
					return refs.putIfAbsent(name, newRef) == null;

				Ref cur = refs.get(name);
				Ref toCompare = cur;
				if (toCompare != null) {
					if (toCompare.isSymbolic()) {
						// Arm's-length dereference symrefs before the compare,
						// since
						// DfsRefUpdate#doLink(String) stores them
						// undereferenced.
						Ref leaf = toCompare.getLeaf();
						if (leaf.getObjectId() == null) {
							leaf = refs.get(leaf.getName());
							if (leaf.isSymbolic())
								// Not supported at the moment.
								throw new IllegalArgumentException();
							toCompare = new SymbolicRef(name,
									new ObjectIdRef.Unpeeled(Storage.NEW,
											leaf.getName(),
											leaf.getObjectId()));
						} else
							toCompare = toCompare.getLeaf();
					}
					if (eq(toCompare, oldRef))
						return refs.replace(name, cur, newRef);
				}

				if (oldRef.getStorage() == Storage.NEW)
					return refs.putIfAbsent(name, newRef) == null;

				return false;
			} finally {
				lock.writeLock().unlock();
			}
		}

		@Override
		protected boolean compareAndRemove(Ref oldRef) throws IOException {
			try {
				lock.writeLock().lock();
				String name = oldRef.getName();
				Ref cur = refs.get(name);
				if (cur != null && eq(cur, oldRef))
					return refs.remove(name, cur);
				else
					return false;
			} finally {
				lock.writeLock().unlock();
			}
		}

		private boolean eq(Ref a, Ref b) {
			if (!Objects.equals(a.getName(), b.getName()))
				return false;
			// Compare leaf object IDs, since the oldRef passed into
			// compareAndPut
			// when detaching a symref is an ObjectIdRef.
			return Objects.equals(a.getLeaf().getObjectId(),
					b.getLeaf().getObjectId());
		}
	}

}
